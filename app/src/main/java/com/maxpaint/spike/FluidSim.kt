package com.maxpaint.spike

import android.content.Context
import android.opengl.GLES31

/**
 * The gaseous medium: 2D Navier-Stokes on GLES 3.1 compute shaders, plus the
 * bake that turns settled fluid into permanent paint, and the fields the other
 * media hang off (watercolor's shallow-water solver, the FLIP particle pool).
 *
 * Fields:
 *   velocity  RGBA16F  (xy used) at simRes, bilinear-filterable
 *   dye       RGBA16F  at simRes * dyeScale, bilinear-filterable
 *   pressure  R32F     at simRes, image load/store only (R32F is not filterable on ES)
 *   curl/div  R32F     at simRes
 *
 * Velocity is stored in normalised-UV units per second, which makes advection
 * independent of grid resolution and lets the dye grid be finer than the
 * velocity grid for free.
 */
class FluidSim(private val ctx: Context) {

    // ---- tunables (driven by the UI) ----
    /** The resolution the artist picks: a cell budget, not a side length. */
    var simRes = 768; private set
    var dyeScale = 1; private set

    /**
     * The grid matches the canvas aspect rather than being square, so a circle
     * stays a circle. Sides are chosen as N*sqrt(aspect) x N/sqrt(aspect), which
     * keeps cells square in world space AND keeps the cell count near N^2, so a
     * given resolution costs about what it always did.
     */
    var simW = 768; private set
    var simH = 768; private set
    var pressureIterations = 30
    /** Red-black Gauss-Seidel converges ~2x faster per sweep than Jacobi at the
     *  same thread count, and needs one pressure texture instead of two.
     *  See tools/compare_solvers.py for the measurement. */
    var useRedBlack = true
    var vorticity = 22f      // Smoke, the default preset
    var velocityDrag = 3.0f      // "drag" from the PRD; higher = paint sets sooner
    var dyeDissipation = 0.05f
    var splatRadius = 0.02f
    /** Coverage deposited per stroke sample, before pressure scales it. */
    var inkPerStroke = 1.0f
    var velocityGain = 1.0f
    /** CFL guard, in UV per second. Keeps a held brush from blowing up the field. */
    var maxSpeed = 4.0f
    /**
     * Second-order MacCormack advection. Off by default, and measurably so:
     * it is ~3% sharper but roughly 50% worse for ink conservation, because
     * reducing numerical diffusion sharpens the dye peaks and the mass-
     * duplication artefact under shear is driven by peak sampling. For a paint
     * app, conserving ink beats a marginally crisper edge. Exposed as a toggle
     * because that trade-off may reverse for a smoke-like preset.
     */
    var useMacCormack = false

    // --- M1: the bake ---
    /** Speed at or below which paint begins to set. */
    var settleSpeed = 0.35f
    /** How fast settled dye transfers to the background, per second. */
    var bakeRate = 0f
    /** "Hold": seconds paint must stay live before it may bake at all. */
    var settleMinAge = 5.0f
    /** Set by Freeze Now; consumed on the next step. */
    @Volatile var freezeRequested = false
    /** Set by global Thaw; consumed on the next step. */
    @Volatile var thawRequested = false

    // --- brushes ---
    @Volatile var brush = Brush.GAS
    var forceMode = ForceMode.SWIRL
    /** Sustained stirring inflates ink (see README); keep the default gentle. */
    var forceStrength = 1.0f
    var combFrequency = 14f
    /** Fraction of pigment the solvent leaves behind at its centre. */
    var solventBite = 0.45f

    // --- nib ---
    var nibRadius = 0.006f
    var nibHardness = 0.9f
    var nibInk = 1.0f
    var nibSoak = 0.9f
    var nibDry = 0.7f
    var nibGrain = 0.6f
    var nibPaperScale = 0.25f
    var nibThreshold = 0.02f

    // --- watercolor ---
    var wcFlow = 6.0f
    var wcGrain = 0.35f
    var wcAdsorb = 0.12f
    var wcDesorb = 0.05f
    var wcCapacity = 1.2f
    var wcEvaporate = 0.22f
    var wcEdge = 6.0f
    var wcPaperScale = 0.09f
    /** Below this depth a cell is dry and commits what it holds. */
    var wcDry = 0.002f
    var wcLoadWater = 0.55f
    var wcLoadPigment = 0.30f

    // ---- resources ----
    private lateinit var velocity: DoubleTex
    private lateinit var dye: DoubleTex
    private lateinit var pressure: DoubleTex
    private lateinit var background: DoubleTex
    private lateinit var age: DoubleTex
    private lateinit var curl: Tex
    private lateinit var divergence: Tex

    private lateinit var pAdvect: ComputeProgram
    private lateinit var pAdvectMc: ComputeProgram
    private lateinit var pSplat: ComputeProgram
    private lateinit var pCurl: ComputeProgram
    private lateinit var pVorticity: ComputeProgram
    private lateinit var pDivergence: ComputeProgram
    private lateinit var pPressure: ComputeProgram
    private lateinit var pPressureRB: ComputeProgram
    private lateinit var pClear: ComputeProgram
    private lateinit var pGradSub: ComputeProgram
    private lateinit var pBake: ComputeProgram
    private lateinit var pStats: ComputeProgram
    private lateinit var pForce: ComputeProgram
    private lateinit var pWatercolor: ComputeProgram
    private lateinit var pWet: ComputeProgram
    private lateinit var pNib: ComputeProgram
    private lateinit var pSoak: ComputeProgram
    private lateinit var nibInkField: DoubleTex

    private lateinit var water: DoubleTex
    private lateinit var flipInk: Tex
    val flip = FlipSystem(ctx)
    private lateinit var statsPartial: Tex
    private var partialW = 1
    private var partialH = 1

    private var aspect = 1f
    private var allocated = false

    val dyeW get() = simW * dyeScale
    val dyeH get() = simH * dyeScale
    /** Kept for reporting: the nominal budget, not a side length. */
    val dyeRes get() = simRes * dyeScale
    val dyeTexture get() = dye.read
    val backgroundTexture get() = background.read
    val waterTexture get() = water.read
    val velocityTexture get() = velocity.read

    fun vramBytes(): Long =
        if (!allocated) 0
        else velocity.bytes() + dye.bytes() + background.bytes() + age.bytes() + water.bytes() +
             flipInk.bytes() + flip.bytes() + nibInkField.bytes() +
             pressure.bytes() + curl.bytes() + divergence.bytes()

    fun initPrograms() {
        pAdvect = ComputeProgram(ctx, "shaders/advect.comp")
        pAdvectMc = ComputeProgram(ctx, "shaders/advect_mc.comp")
        pSplat = ComputeProgram(ctx, "shaders/splat.comp")
        pCurl = ComputeProgram(ctx, "shaders/curl.comp")
        pVorticity = ComputeProgram(ctx, "shaders/vorticity.comp")
        pDivergence = ComputeProgram(ctx, "shaders/divergence.comp")
        pPressure = ComputeProgram(ctx, "shaders/pressure.comp")
        pPressureRB = ComputeProgram(ctx, "shaders/pressure_rb.comp")
        pClear = ComputeProgram(ctx, "shaders/clearp.comp")
        pGradSub = ComputeProgram(ctx, "shaders/gradsub.comp")
        pBake = ComputeProgram(ctx, "shaders/bake.comp")
        pStats = ComputeProgram(ctx, "shaders/stats.comp")
        pForce = ComputeProgram(ctx, "shaders/force.comp")
        pWatercolor = ComputeProgram(ctx, "shaders/watercolor.comp")
        pWet = ComputeProgram(ctx, "shaders/wet.comp")
        queryMaxTexture()
        pNib = ComputeProgram(ctx, "shaders/nib.comp")
        pSoak = ComputeProgram(ctx, "shaders/soak.comp")
        flip.init()
    }

    /**
     * The canvas takes its shape once and keeps it. A painting is not a view
     * that reflows: rotating the device, or the window changing size, must not
     * reshape the surface being painted on -- that would reallocate every field
     * and throw the artwork away. Only [newCanvas] changes the shape.
     */
    fun setAspect(a: Float) {
        if (canvasShaped) return
        aspect = a
    }

    /** Starts a fresh canvas at a new shape. Discards what is on the old one. */
    fun newCanvas(a: Float) {
        aspect = a
        canvasShaped = false
        if (allocated) allocate(simRes, dyeScale)
    }

    private var canvasShaped = false

    private fun even(v: Float) = (v.toInt() / 2 * 2).coerceAtLeast(8)

    /**
     * GL_MAX_TEXTURE_SIZE, queried once. Shaping the grid to the canvas makes
     * the long side much longer than the old square grid, and with 2x ink detail
     * a 1536 budget on a 2.2:1 screen wants a 4556px texture -- past the 4096
     * many mobile GPUs report, which fails allocation and leaves a black canvas.
     */
    private var maxTexture = 4096

    private fun queryMaxTexture() {
        val v = IntArray(1)
        GLES31.glGetIntegerv(GLES31.GL_MAX_TEXTURE_SIZE, v, 0)
        if (v[0] > 0) maxTexture = v[0]
    }

    /** (Re)allocate all fields. Safe to call at runtime when the user picks a new resolution. */
    fun allocate(newSimRes: Int, newDyeScale: Int) {
        if (allocated) releaseTextures()
        simRes = newSimRes
        dyeScale = newDyeScale

        val root = kotlin.math.sqrt(aspect.coerceIn(0.2f, 5f))
        var w = simRes * root
        var h = simRes / root

        // every field is allocated at dyeScale times these, so the limit
        // applies to the product
        val limit = (maxTexture / dyeScale).toFloat()
        val over = maxOf(w / limit, h / limit)
        if (over > 1f) {
            w /= over
            h /= over
        }
        simW = even(w)
        simH = even(h)
        canvasShaped = true

        velocity = DoubleTex(simW, simH, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)
        dye = DoubleTex(dyeW, dyeH, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)
        // Baked paint lives at dye resolution for the spike. The PRD wants it at
        // full canvas resolution (7.2); that arrives with the document model in M3.
        background = DoubleTex(dyeW, dyeH, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)
        age = DoubleTex(dyeW, dyeH, GLES31.GL_RGBA16F, GLES31.GL_NEAREST)

// RGBA32F: watercolor fluxes are small relative to the depth they modify,
        // and fp16 rounds them away outright
        water = DoubleTex(dyeW, dyeH, GLES31.GL_RGBA32F, GLES31.GL_NEAREST)
        // live particles are drawn here each frame, then composited
        flipInk = Tex(dyeW, dyeH, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)
        nibInkField = DoubleTex(dyeW, dyeH, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)

        partialW = (dyeW + STATS_TILE - 1) / STATS_TILE
        partialH = (dyeH + STATS_TILE - 1) / STATS_TILE
        statsPartial = Tex(partialW, partialH, GLES31.GL_RGBA32F, GLES31.GL_NEAREST)
        pressure = DoubleTex(simW, simH, GLES31.GL_R32F, GLES31.GL_NEAREST)
        curl = Tex(simW, simH, GLES31.GL_R32F, GLES31.GL_NEAREST)
        divergence = Tex(simW, simH, GLES31.GL_R32F, GLES31.GL_NEAREST)

        allocated = true
        GLUtil.checkError("allocate(${simW}x${simH}, dye=${dyeW}x${dyeH})")
    }

    fun clear() {
        if (!allocated) return
        velocity.clear(); dye.clear(); background.clear(); age.clear(); water.clear()
        flipInk.clear(); flip.clear(); nibInkField.clear()
        waterActive = false; nibActive = false
        pressure.clear(); curl.clear(); divergence.clear()
    }

    /**
     * A stroke sample. Coordinates and delta are in UV space; the brush decides
     * what that means — pigment, pure force, or a local freeze.
     */
    fun stroke(
        u: Float, v: Float, du: Float, dv: Float,
        r: Float, g: Float, b: Float,
        pressure: Float = 1f, tiltSpread: Float = 1f,
        prevU: Float = u, prevV: Float = v
    ) {
        if (!allocated) return

        // FR-6: pressure sets how much ink lands, tilt widens the footprint.
        val baseRadius = splatRadius
        val baseInk = inkPerStroke
        splatRadius = baseRadius * tiltSpread
        inkPerStroke = baseInk * pressure
        try {
            strokeInner(u, v, du, dv, r, g, b, prevU, prevV)
        } finally {
            splatRadius = baseRadius
            inkPerStroke = baseInk
        }
    }

    private fun strokeInner(
        u: Float, v: Float, du: Float, dv: Float,
        r: Float, g: Float, b: Float, prevU: Float, prevV: Float
    ) {
        when (brush) {
            Brush.GAS -> splat(u, v, du, dv, r, g, b)
            Brush.NIB -> nib(u, v, prevU, prevV)
            Brush.FLIP -> {
                // a little momentum into the grid too, so the pour interacts
                // with fluid already on the canvas
                flip.emit(u, v, du, dv, splatRadius * 0.5f, inkPerStroke)
                force(u, v, du, dv, ForceMode.PUSH.code, 0.4f)
            }
            Brush.WATERCOLOR -> wet(u, v)
            Brush.VORTEX -> force(u, v, du, dv, forceMode.code, forceStrength)
            Brush.SOLVENT -> solvent(u, v)
            Brush.FREEZE -> transfer(1f / 60f, force = true, thaw = false, maskAt = u to v)
            Brush.THAW -> transfer(1f / 60f, force = false, thaw = true, maskAt = u to v)
        }
    }

    /**
     * A nib mark. Drawn as a capsule from the previous sample so a fast stroke
     * stays unbroken rather than dotting, and written to its own field so the
     * fluid cannot smear it.
     */
    fun nib(u: Float, v: Float, prevU: Float, prevV: Float) {
        if (!allocated) return
        nibActive = true
        pNib.use()
        pNib.set("uPoint", u, v)
        pNib.set("uPrev", prevU, prevV)
        pNib.set("uRadius", nibRadius * inkPerStroke.coerceAtLeast(0.25f))
        pNib.set("uAspect", aspect)
        pNib.set("uInk", nibInk * inkPerStroke)
        pNib.set("uHardness", nibHardness)
        nibInkField.read.bindImage(0, GLES31.GL_READ_ONLY)
        nibInkField.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pNib.dispatch(dyeW, dyeH)
        nibInkField.swap()
    }

    /** Capillary soak plus drying into the background. */
    private fun stepNib(dt: Float) {
        pSoak.use()
        pSoak.set("uDt", dt)
        pSoak.set("uSoak", nibSoak)
        pSoak.set("uDry", nibDry)
        pSoak.set("uGrain", nibGrain)
        pSoak.set("uPaperScale", nibPaperScale)
        pSoak.set("uThreshold", nibThreshold)
        pSoak.set("uInkSrc", 0)
        pSoak.set("uBgSrc", 1)
        nibInkField.read.bindSampler(0)
        background.read.bindSampler(1)
        nibInkField.write.bindImage(0, GLES31.GL_WRITE_ONLY)
        background.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pSoak.dispatch(dyeW, dyeH)
        nibInkField.swap(); background.swap()
    }

    val nibTexture get() = nibInkField.read

    /** Loads the paper with water and pigment; the watercolor solver takes it from there. */
    fun wet(u: Float, v: Float) {
        if (!allocated) return
        waterActive = true
        pWet.use()
        pWet.set("uPoint", u, v)
        pWet.set("uRadius", splatRadius)
        pWet.set("uAspect", aspect)
        pWet.set("uWater", wcLoadWater * inkPerStroke)
        pWet.set("uPigment", wcLoadPigment * inkPerStroke)
        water.read.bindImage(0, GLES31.GL_READ_ONLY)
        water.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pWet.dispatch(dyeW, dyeH)
        water.swap()
    }

    /** One watercolor step: flow, transport, adsorb, evaporate, commit. */
    fun stepWatercolor(dt: Float) {
        if (!allocated) return
        pWatercolor.use()
        pWatercolor.set("uDt", dt)
        pWatercolor.set("uFlow", wcFlow)
        pWatercolor.set("uGrain", wcGrain)
        pWatercolor.set("uAdsorb", wcAdsorb)
        pWatercolor.set("uDesorb", wcDesorb)
        pWatercolor.set("uCapacity", wcCapacity)
        pWatercolor.set("uEvaporate", wcEvaporate)
        pWatercolor.set("uEdge", wcEdge)
        pWatercolor.set("uPaperScale", wcPaperScale)
        pWatercolor.set("uDry", wcDry)
        pWatercolor.set("uWaterSrc", 0)
        pWatercolor.set("uBgSrc", 1)
        water.read.bindSampler(0)
        background.read.bindSampler(1)
        water.write.bindImage(0, GLES31.GL_WRITE_ONLY)
        background.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pWatercolor.dispatch(dyeW, dyeH)
        water.swap(); background.swap()
    }

    /**
     * Advances the particle pool and renders it. Particles that have dried are
     * drawn once into the background — permanent, and off the simulation's
     * books — before being retired.
     */
    private fun stepFlip(dt: Float) {
        flip.step(dt, velocity.read, aspect)

        // freshly dried particles land in the background layer, permanently
        flip.draw(state = 2f, target = background.read)

        // live particles are redrawn from scratch each frame
        flipInk.clear()
        flip.draw(state = 1f, target = flipInk)
    }

    val flipTexture get() = flipInk

    /** Momentum with no pigment: stir, shove, pinch or comb what is already there. */
    fun force(u: Float, v: Float, du: Float, dv: Float, mode: Int, strength: Float) {
        if (!allocated) return
        pForce.use()
        pForce.set("uPoint", u, v)
        pForce.set("uDir", du * aspect, dv)
        pForce.set("uRadius", splatRadius * 1.5f)
        pForce.set("uAspect", aspect)
        pForce.set("uStrength", strength)
        pForce.set("uMode", mode)
        pForce.set("uCombFreq", combFrequency)
        pForce.set("uDt", 1f / 60f)
        velocity.read.bindImage(0, GLES31.GL_READ_ONLY)
        velocity.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pForce.dispatch(simW, simH)
        velocity.swap()
    }

    /**
     * Solvent: lifts pigment where it bites and drives the rest outward, which
     * is what produces the alcohol-drop halo rather than a plain erase.
     */
    fun solvent(u: Float, v: Float) {
        if (!allocated) return

        pSplat.use()
        pSplat.set("uPoint", u, v)
        pSplat.set("uAspect", aspect)
        pSplat.set("uRadius", splatRadius)
        pSplat.set("uMode", 2)
        pSplat.set("uValue", solventBite, 0f, 0f, 0f)
        dye.read.bindImage(0, GLES31.GL_READ_ONLY)
        dye.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pSplat.dispatch(dyeW, dyeH)
        dye.swap()
        pSplat.set("uMode", 0)

        // divergent push: negative pinch drives pigment away from the drop
        force(u, v, 0f, 0f, ForceMode.PINCH.code, -forceStrength)
    }

    /** Inject momentum and colour. Coordinates and delta are in UV space. */
    fun splat(u: Float, v: Float, du: Float, dv: Float, r: Float, g: Float, b: Float) {
        if (!allocated) return

        pSplat.use()
        pSplat.set("uPoint", u, v)
        pSplat.set("uAspect", aspect)
        pSplat.set("uMode", 0)

        // velocity
        pSplat.set("uRadius", splatRadius)
        // touch delta arrives in UV; velocity is stored in world units
        pSplat.set("uValue", du * velocityGain * aspect, dv * velocityGain, 0f, 0f)
        velocity.read.bindImage(0, GLES31.GL_READ_ONLY)
        velocity.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pSplat.dispatch(simW, simH)
        velocity.swap()

        // dye - a slightly tighter splat reads as a crisper mark.
        // Colour is premultiplied by coverage so compositing is a plain "over".
        pSplat.set("uRadius", splatRadius * 0.6f)
        pSplat.set("uValue", r * inkPerStroke, g * inkPerStroke, b * inkPerStroke, inkPerStroke)
        dye.read.bindImage(0, GLES31.GL_READ_ONLY)
        dye.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pSplat.dispatch(dyeW, dyeH)
        dye.swap()

        // Freshly injected paint is new, so its age restarts. Lerp rather than
        // add, or "Hold" would never apply to a stroke drawn over an old one.
        pSplat.set("uMode", 1)
        pSplat.set("uValue", 0f, 0f, 0f, 0f)
        age.read.bindImage(0, GLES31.GL_READ_ONLY)
        age.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pSplat.dispatch(dyeW, dyeH)
        age.swap()
        pSplat.set("uMode", 0)
    }

    /** Bake every bit of live fluid into the background, now. */
    fun freezeNow() { freezeRequested = true }

    /** Lift the most recent baked paint back into the simulation. */
    fun thaw() { thawRequested = true }

    /**
     * Each extra medium is a full-grid pass (and, for FLIP, a particle update
     * plus two point draws) every frame. Running them before they have been
     * touched was pure waste, so each stays dormant until first use and then
     * keeps running -- paper must go on drying after you switch brushes.
     */
    var waterActive = false; private set
    var nibActive = false; private set

    /** True when there is no live fluid left to simulate. */
    fun isIdle(): Boolean = false

    fun step(dt: Float) {
        if (!allocated) return

        // 1. curl
        pCurl.use()
        velocity.read.bindImage(0, GLES31.GL_READ_ONLY)
        curl.bindImage(1, GLES31.GL_WRITE_ONLY)
        pCurl.dispatch(simW, simH)

        // 2. vorticity confinement
        if (vorticity > 0f) {
            pVorticity.use()
            pVorticity.set("uStrength", vorticity)
            pVorticity.set("uDt", dt)
            velocity.read.bindImage(0, GLES31.GL_READ_ONLY)
            velocity.write.bindImage(1, GLES31.GL_WRITE_ONLY)
            curl.bindImage(2, GLES31.GL_READ_ONLY)
            pVorticity.dispatch(simW, simH)
            velocity.swap()
        }

        // 3-6. make the field incompressible again
        computeDivergence()
        decayPressure(0.8f)     // warm start beats a cold clear
        solvePressure()
        subtractGradient(dt, velocityDrag)

        // 7. advect velocity
        val pAdv = if (useMacCormack) pAdvectMc else pAdvect
        pAdv.use()
        pAdv.set("uDt", dt)
        pAdv.set("uAspect", aspect)
        pAdv.set("uDstTexel", 1f / simW, 1f / simH)
        pAdv.set("uDissipation", 0f)
        velocity.read.bindSampler(0)
        velocity.read.bindSampler(1)
        velocity.write.bindImage(0, GLES31.GL_WRITE_ONLY)
        pAdv.dispatch(simW, simH)
        velocity.swap()

        // 8. advect dye
        pAdv.set("uDstTexel", 1f / dyeW, 1f / dyeH)
        pAdv.set("uDissipation", dyeDissipation)
        dye.read.bindSampler(0)
        velocity.read.bindSampler(1)
        dye.write.bindImage(0, GLES31.GL_WRITE_ONLY)
        pAdv.dispatch(dyeW, dyeH)
        dye.swap()

        // 9. advect age along with the dye it belongs to
        pAdv.set("uDissipation", 0f)
        age.read.bindSampler(0)
        velocity.read.bindSampler(1)
        age.write.bindImage(0, GLES31.GL_WRITE_ONLY)
        pAdv.dispatch(dyeW, dyeH)
        age.swap()

        // 10. bake: settled fluid moves out of the sim and into the background
        bake(dt)

        // 11-13. the other media, each dormant until it has been used
        if (waterActive) stepWatercolor(dt)
        if (flip.inUse) stepFlip(dt)
        if (nibActive) stepNib(dt)
    }

    private fun computeDivergence() {
        pDivergence.use()
        velocity.read.bindImage(0, GLES31.GL_READ_ONLY)
        divergence.bindImage(1, GLES31.GL_WRITE_ONLY)
        pDivergence.dispatch(simW, simH)
    }

    private fun decayPressure(factor: Float) {
        pClear.use()
        pClear.set("uValue", factor)
        pressure.read.bindImage(0, GLES31.GL_READ_WRITE)
        pClear.dispatch(simW, simH)
    }

    private fun solvePressure() {
        if (useRedBlack) {
            // In-place red-black Gauss-Seidel. Legal because pressure is r32f,
            // the one format ES 3.1 allows read-write images for. Each sweep is
            // two half-width dispatches, so thread count matches one Jacobi pass.
            pPressureRB.use()
            divergence.bindImage(2, GLES31.GL_READ_ONLY)
            val halfWidth = (simW + 1) / 2
            for (i in 0 until pressureIterations) {
                for (parity in 0..1) {
                    pressure.read.bindImage(0, GLES31.GL_READ_WRITE)
                    pPressureRB.set("uParity", parity)
                    pPressureRB.dispatch(halfWidth, simH)
                }
            }
        } else {
            pPressure.use()
            divergence.bindImage(2, GLES31.GL_READ_ONLY)
            for (i in 0 until pressureIterations) {
                pressure.read.bindImage(0, GLES31.GL_READ_ONLY)
                pressure.write.bindImage(1, GLES31.GL_WRITE_ONLY)
                pPressure.dispatch(simW, simH)
                pressure.swap()
            }
        }
    }

    private fun subtractGradient(dt: Float, drag: Float) {
        pGradSub.use()
        pGradSub.set("uDrag", drag)
        pGradSub.set("uDt", dt)
        pGradSub.set("uMaxSpeed", maxSpeed)
        velocity.read.bindImage(0, GLES31.GL_READ_ONLY)
        velocity.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pressure.read.bindImage(2, GLES31.GL_READ_ONLY)
        pGradSub.dispatch(simW, simH)
        velocity.swap()
    }

    // ---------------- measurement ----------------

    /** Total ink in the live dye field, and the RMS divergence of the velocity field. */
    data class Stats(val ink: Float, val divergenceRms: Float)

    /**
     * Reads back a tile-reduced summary. One small readback, not a full-grid one:
     * at 2048x2048 the partial texture is 128x128.
     */
    fun measure(): Stats {
        if (!allocated) return Stats(0f, 0f)

        pStats.use()
        pStats.set("uTile", STATS_TILE)
        pStats.set("uDyeScale", dyeScale)
        dye.read.bindImage(0, GLES31.GL_READ_ONLY)
        divergence.bindImage(1, GLES31.GL_READ_ONLY)
        statsPartial.bindImage(2, GLES31.GL_WRITE_ONLY)
        pStats.dispatch(partialW, partialH)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)

        val n = partialW * partialH * 4
        val buf = java.nio.ByteBuffer.allocateDirect(n * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()

        val fbo = IntArray(1)
        GLES31.glGenFramebuffers(1, fbo, 0)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, fbo[0])
        GLES31.glFramebufferTexture2D(
            GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0,
            GLES31.GL_TEXTURE_2D, statsPartial.id, 0
        )
        GLES31.glReadPixels(
            0, 0, partialW, partialH,
            GLES31.GL_RGBA, GLES31.GL_FLOAT, buf
        )
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glDeleteFramebuffers(1, fbo, 0)

        var ink = 0.0
        var div2 = 0.0
        for (i in 0 until partialW * partialH) {
            ink += buf.get(i * 4)
            div2 += buf.get(i * 4 + 1)
        }
        val cells = (simW.toDouble() * simH.toDouble()).coerceAtLeast(1.0)
        return Stats(ink.toFloat(), Math.sqrt(div2 / cells).toFloat())
    }

    /**
     * Fraction of divergence a single cold-start solve removes, at the current
     * iteration count. This is the quality number that frame time hides: an
     * under-solved field looks worse AND manufactures ink, however fast it runs.
     */
    fun measureConvergence(): Float {
        if (!allocated) return 0f

        computeDivergence()
        val before = measure().divergenceRms
        if (before <= 1e-9f) return 1f

        decayPressure(0f)          // cold start: measure this solve alone
        solvePressure()
        subtractGradient(1f / 60f, 0f)
        computeDivergence()
        val after = measure().divergenceRms

        return (1f - after / before).coerceIn(0f, 1f)
    }

    private fun bake(dt: Float) {
        val doFreeze = freezeRequested
        val doThaw = thawRequested
        freezeRequested = false
        thawRequested = false
        transfer(dt, force = doFreeze, thaw = doThaw, maskAt = null)
    }

    /**
     * Moves paint between the simulation and the background layer. Forward is
     * the bake; [thaw] runs it backwards. [maskAt] limits it to a brush
     * footprint, which is what the local Freeze and Thaw brushes are.
     */
    private fun transfer(
        dt: Float,
        force: Boolean,
        thaw: Boolean,
        maskAt: Pair<Float, Float>?
    ) {
        if (!allocated) return

        pBake.use()
        pBake.set("uDt", dt)
        pBake.set("uSettleSpeed", settleSpeed)
        pBake.set("uBakeRate", if (thaw) bakeRate * 3f else bakeRate)
        pBake.set("uSettleMinAge", settleMinAge)
        pBake.set("uForce", if (force) 1 else 0)
        pBake.set("uThaw", if (thaw) 1 else 0)
        pBake.set("uAspect", aspect)
        if (maskAt != null) {
            pBake.set("uMaskPoint", maskAt.first, maskAt.second)
            pBake.set("uMaskRadius", splatRadius * 1.5f)
        } else {
            pBake.set("uMaskPoint", 0.5f, 0.5f)
            pBake.set("uMaskRadius", -1f)
        }

        // reads via samplers, writes via images: ES 3.1 guarantees only four
        // compute image units
        velocity.read.bindSampler(0)
        dye.read.bindSampler(1)
        background.read.bindSampler(2)
        age.read.bindSampler(3)
        dye.write.bindImage(0, GLES31.GL_WRITE_ONLY)
        background.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        age.write.bindImage(2, GLES31.GL_WRITE_ONLY)

        pBake.dispatch(dyeW, dyeH)
        dye.swap(); background.swap(); age.swap()
    }

    private fun releaseTextures() {
        velocity.release(); dye.release(); background.release(); age.release()
        water.release(); flipInk.release(); nibInkField.release()
        pressure.release(); curl.release(); divergence.release()
        statsPartial.release()
        allocated = false
    }

    fun release() {
        if (allocated) releaseTextures()
    }

    companion object {
        /** Reduction tile size for [measure]. */
        const val STATS_TILE = 16

        /** Selectable simulation resolutions for the headroom sweep. */
        val RESOLUTIONS = intArrayOf(128, 256, 384, 512, 768, 1024, 1536, 2048)
    }
}
