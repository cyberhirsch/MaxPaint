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
    var simRes = 512; private set
    var dyeScale = 2; private set

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
    var velocityDrag = 0f        // "drag" from the PRD; higher = paint sets sooner
    var dyeDissipation = 0.05f
    /**
     * Footprint size, per brush. It used to be one shared field with a slider
     * only on the gas panel, so setting the gas brush silently resized the
     * drip, the wash and both force brushes -- a drip is not a puff of gas and
     * has no business inheriting its size.
     *
     * The accessor keeps every reader in the solver unchanged: they all want
     * the size of whichever brush is painting, which is what they now get.
     */
    private val brushSize = FloatArray(Brush.entries.size) { 0.02f }
    var splatRadius: Float
        get() = brushSize[brush.ordinal]
        set(v) { brushSize[brush.ordinal] = v }

    // ---- the contact patch ----
    //
    // What the digitiser reports about the finger actually touching the glass,
    // in world units, for the sample being drawn. Zero means the device told us
    // nothing and every brush stays round.
    var contactMajor = 0f
    var contactMinor = 0f
    /** Direction of the long axis, radians, in canvas space. */
    var contactAngle = 0f

    /**
     * The normalised 0..1 contact area, which is a different thing from
     * [contactMajor] and has to stay separate. It is the oldest and most widely
     * reported of the touch axes, but it is scaled against a device-specific
     * maximum rather than being a length, so it can drive a size relative to
     * the brush and never an absolute one.
     */
    var contactSize = 0f

    /** What the device is actually giving us, which decides what can be done with it. */
    enum class ContactSource { NONE, MEASURED, NORMALISED }

    val contactSource: ContactSource
        get() = when {
            contactMajor > 0f -> ContactSource.MEASURED
            contactSize > 0f -> ContactSource.NORMALISED
            else -> ContactSource.NONE
        }

    /**
     * Fingerprint mode: the mark is the size of the finger, and Brush size
     * stops applying. Off by default, because the measurement is in
     * device-calibrated absolute units and a panel that reports a constant
     * would turn the mark into a fixed size with no way to change it. The
     * Probe brush says whether this device reports anything usable.
     */
    var fingerprint = false

    /**
     * How much the measured patch ovals the dab. On by default, because a ratio
     * is dimensionless and bounded: a device that reports circles simply keeps
     * the mark round rather than getting it wrong.
     */
    var contactShapeAmount = 1f
    /** Coverage deposited per stroke sample, before pressure scales it. */
    var inkPerStroke = 3.47f
    var velocityGain = 1.0f
    /** Sweeps for the particle grid's own projection. */
    /**
     * 40 rather than 20. Sweeps needed scale with grid width, and on the old
     * ink-sized grid 20 was drastically under-solved; on the coarse particle
     * grid the same 20 removes 92.6% of interior divergence and 40 removes
     * 99.1%, at half the cost the under-solved version used to pay.
     */
    var flipIterations = 160
    /** Below this accumulated mass a cell counts as air, not liquid. */
    var flipMinMass = 0.08f

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
    /** Speed at or below which paint begins to set. Generous by default, so
     *  most of a stroke qualifies as settled almost immediately. */
    var settleSpeed = 0.6f
    /** How fast settled dye transfers to the background, per second. Maximum
     *  by default: paint should become permanent early, which is also what
     *  gives the force brushes something to pick up. */
    var bakeRate = 3.9f
    /** "Hold": seconds paint must stay live before it may bake at all. Zero by
     *  default; any hold directly contradicts baking early. */
    var settleMinAge = 1.2f
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
    // --- smear ---
    var smearRadius = 0.05f
    var smearStrength = 0.85f
    var smearReach = 0.05f

    /** Fraction of pigment the solvent leaves behind at its centre. */
    var solventBite = 0.45f

    /**
     * How readily the force brushes lift paint that has already set, so they
     * can move it. Baked paint used to be strictly immutable, which meant stir
     * and lift did nothing to the marks actually on the canvas — the one thing
     * an artist expects a brush to act on. Zero restores the old behaviour.
     */
    var pickup = 2.5f

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
    /**
     * Paint that has set. There is always at least one layer; the active one is
     * what everything bakes into, so the rest of the solver keeps talking to a
     * single [DoubleTex] and does not know layers exist.
     */
    val layers = ArrayList<Layer>()
    var activeLayer = 0
        set(v) { field = v.coerceIn(0, layers.size - 1); layersDirty = true }
    private val background get() = layers[activeLayer].tex

    /** Set whenever the stack below or above the active layer changes shape. */
    var layersDirty = true

    // Everything under the active layer, and everything over it, flattened.
    // Recomposed only when the stack changes, not per frame.
    private var underlay: DoubleTex? = null
    private var overlay: DoubleTex? = null
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
    private lateinit var pSmear: ComputeProgram
    private lateinit var pPressureFlip: ComputeProgram
    private lateinit var pBlit: ComputeProgram
    private lateinit var pComposite: ComputeProgram
    private lateinit var pProbe: ComputeProgram
    private lateinit var pDivergenceFlip: ComputeProgram
    private lateinit var pGradSubFlip: ComputeProgram
    /**
     * The particle grid is deliberately much coarser than the ink grid, and is
     * NOT the gas solver's grid.
     *
     * FLIP is a particle method that borrows a grid to make particles see each
     * other. That only works when several particles share a cell: the pressure
     * solve couples a cell to its neighbours, so a cell holding one particle
     * couples that particle to nothing. Running it on the ink grid put roughly
     * one particle in every occupied cell -- measured coupling 2.6x against a
     * peak of 7.1x -- so the medium was a spray of independent points wearing
     * a solver. Around eight particles per occupied cell is where it peaks.
     *
     * A cell about the size of a brush dab is what that works out to, and it
     * is also 16x fewer cells for the pressure solve to sweep.
     */
    var flipRes = 160; private set
    var flipW = 1; private set
    var flipH = 1; private set

    private lateinit var flipVel: DoubleTex
    private lateinit var flipVelOld: Tex
    private lateinit var flipMass: Tex
    private lateinit var flipPressure: DoubleTex
    private lateinit var flipDivergence: Tex
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
    val underlayTexture get() = underlay?.read
    val overlayTexture get() = overlay?.read
    val waterTexture get() = water.read
    val velocityTexture get() = velocity.read

    fun vramBytes(): Long =
        if (!allocated) 0
        else velocity.bytes() + dye.bytes() + age.bytes() + water.bytes() +
             layers.sumOf { it.tex.bytes() } +
             (underlay?.bytes() ?: 0L) + (overlay?.bytes() ?: 0L) +
             historyBytes() +
             flipInk.bytes() + flip.bytes() + nibInkField.bytes() +
             flipVel.bytes() + flipVelOld.bytes() + flipMass.bytes() +
             flipPressure.bytes() + flipDivergence.bytes() +
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
        pSmear = ComputeProgram(ctx, "shaders/smear.comp")
        pPressureFlip = ComputeProgram(ctx, "shaders/pressure_flip.comp")
        pBlit = ComputeProgram(ctx, "shaders/blit.comp")
        pComposite = ComputeProgram(ctx, "shaders/composite.comp")
        pProbe = ComputeProgram(ctx, "shaders/probe.comp")
        pDivergenceFlip = ComputeProgram(ctx, "shaders/divergence_flip.comp")
        pGradSubFlip = ComputeProgram(ctx, "shaders/gradsub_flip.comp")
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
     * Reshapes only the particle grid. Kept apart from [allocate] so changing
     * how strongly the medium couples does not throw away the painting.
     */
    fun reshapeFlipGrid(res: Int) {
        if (!allocated || res == flipRes) return
        flipRes = res
        flipVel.release(); flipVelOld.release(); flipMass.release()
        flipPressure.release(); flipDivergence.release()
        shapeFlipGrid()
        flipVel = DoubleTex(flipW, flipH, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)
        flipVelOld = Tex(flipW, flipH, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)
        flipMass = Tex(flipW, flipH, GLES31.GL_R32F, GLES31.GL_NEAREST)
        flipPressure = DoubleTex(flipW, flipH, GLES31.GL_R32F, GLES31.GL_NEAREST)
        flipDivergence = Tex(flipW, flipH, GLES31.GL_R32F, GLES31.GL_NEAREST)
        flip.resizeGrid(flipW, flipH, flipRes)
        GLUtil.checkError("reshapeFlipGrid($res)")
    }

    /** Same canvas shaping as the main grid, so cells stay square in world space. */
    private fun shapeFlipGrid() {
        val root = kotlin.math.sqrt(aspect.coerceIn(0.2f, 5f))
        flipW = even(flipRes * root)
        flipH = even(flipRes / root)
    }

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
        layers.clear()
        layers.add(Layer("Layer 1", DoubleTex(dyeW, dyeH, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)))
        activeLayer = 0
        underlay = null
        overlay = null
        layersDirty = true
        age = DoubleTex(dyeW, dyeH, GLES31.GL_RGBA16F, GLES31.GL_NEAREST)

// RGBA32F: watercolor fluxes are small relative to the depth they modify,
        // and fp16 rounds them away outright
        water = DoubleTex(dyeW, dyeH, GLES31.GL_RGBA32F, GLES31.GL_NEAREST)
        // live particles are drawn here each frame, then composited
        flipInk = Tex(dyeW, dyeH, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)
        nibInkField = DoubleTex(dyeW, dyeH, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)

        // FLIP keeps its own grid: sharing the gas brush's pressure field would
        // destroy its warm start every frame
        shapeFlipGrid()
        flipVel = DoubleTex(flipW, flipH, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)
        flipVelOld = Tex(flipW, flipH, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)
        flipMass = Tex(flipW, flipH, GLES31.GL_R32F, GLES31.GL_NEAREST)
        flipPressure = DoubleTex(flipW, flipH, GLES31.GL_R32F, GLES31.GL_NEAREST)
        flipDivergence = Tex(flipW, flipH, GLES31.GL_R32F, GLES31.GL_NEAREST)
        flip.resizeGrid(flipW, flipH, flipRes)

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
        dropHistory()
        velocity.clear(); dye.clear(); age.clear(); water.clear()
        layers.forEach { it.tex.clear() }
        layersDirty = true
        flipInk.clear(); flip.clear(); nibInkField.clear()
        flipVel.clear(); flipVelOld.clear(); flipMass.clear()
        flipPressure.clear(); flipDivergence.clear()
        waterActive = false; nibActive = false
        pressure.clear(); curl.clear(); divergence.clear()
    }

    /**
     * How far apart stamps sit along a stroke, in the same units the brush
     * radii use (UV, with x scaled by the canvas aspect).
     *
     * The nib and smear draw a capsule from the previous point to this one, so
     * they join up on their own and only need stamps close enough to follow a
     * curve. Everything else stamps a round dab. Half a radius apart is what
     * turns a row of beads into a ribbon: the falloff is exp(-d²/r²), wide
     * enough that measured coverage never drops below 74% of the peak, and
     * every dab is a full-canvas pass so closer spacing costs frames for a
     * mark that is already continuous.
     */
    val stampSpacing: Float
        get() = when (brush) {
            Brush.NIB, Brush.SMEAR, Brush.PROBE -> 0.02f
            else -> (splatRadius * 0.5f).coerceAtLeast(0.002f)
        }

    /**
     * True when the brush stamps round dabs that have to be strung along the
     * path. The nib and smear instead draw a capsule from the previous reported
     * point to this one, which already covers the segment exactly once.
     */
    val stampsDabs: Boolean
        get() = brush != Brush.NIB && brush != Brush.SMEAR && brush != Brush.PROBE

    /**
     * What one dab carries, as a fraction of the Load slider.
     *
     * Load means ink per brush-width travelled. Defining it per dab instead
     * would make the mark depend on the stamp spacing, and defining it per
     * touch event -- which is what it used to be -- makes it depend on how
     * often the digitiser happened to report, so the same stroke drawn twice
     * came out at different weights.
     *
     * [DAB_CALIBRATION] is what makes a stroke weigh what it used to. The shape
     * of the rule is principled; its constant cannot be, because matching the
     * old per-event meaning would need the event rate that the rule exists to
     * remove. It is set from a measured stroke: without it a mark on a 2.34
     * canvas came out 3.7x heavier and saturated to solid black at Load 2.
     */
    val inkPerDab: Float
        get() = DAB_CALIBRATION * stampSpacing / splatRadius.coerceAtLeast(1e-4f)

    /** Width over height. Stroke geometry has to use the same metric as the shaders. */
    val canvasAspect: Float get() = aspect

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
        splatRadius = contactRadius(baseRadius * tiltSpread)
        inkPerStroke = baseInk * pressure
        try {
            strokeInner(u, v, du, dv, r, g, b, prevU, prevV)
        } finally {
            splatRadius = baseRadius
            inkPerStroke = baseInk
        }
    }

    /**
     * The dab's long radius. In fingerprint mode this is the measured contact
     * and nothing else -- clamping it toward the Brush size setting would
     * defeat the point of the mode -- so the guard is an absolute band instead,
     * wide enough to be honest and narrow enough that a device reporting
     * nonsense cannot fill the canvas.
     */
    private fun contactRadius(base: Float): Float {
        if (!fingerprint) return base
        return when (contactSource) {
            // a real length: the mark is the size of the finger
            ContactSource.MEASURED -> (contactMajor * 0.5f).coerceIn(0.002f, 0.2f)
            // only a normalised area, so the honest reading is a size relative
            // to the brush rather than an absolute one -- a light touch is half
            // the setting, a flat finger twice it
            ContactSource.NORMALISED -> base * (0.5f + 1.5f * contactSize)
            ContactSource.NONE -> base
        }
    }

    /** Minor over major, blended by [contactShapeAmount]. 1.0 is a circle. */
    val contactMinorRatio: Float
        get() {
            if (contactMajor <= 0f || contactShapeAmount <= 0f) return 1f
            val ratio = (contactMinor / contactMajor).coerceIn(0.15f, 1f)
            return 1f * (1f - contactShapeAmount) + ratio * contactShapeAmount
        }

    /** Unit vector along the long axis, world space. */
    val contactAxisX: Float get() = kotlin.math.cos(contactAngle)
    val contactAxisY: Float get() = kotlin.math.sin(contactAngle)

    /** Every footprint pass takes the same three, so they are set in one place. */
    private fun setContact(p: ComputeProgram) {
        p.set("uAxis", contactAxisX, contactAxisY)
        p.set("uMinor", contactMinorRatio)
    }

    private fun strokeInner(
        u: Float, v: Float, du: Float, dv: Float,
        r: Float, g: Float, b: Float, prevU: Float, prevV: Float
    ) {
        when (brush) {
            Brush.GAS -> splat(u, v, du, dv, r, g, b)
            Brush.NIB -> nib(u, v, prevU, prevV)
            Brush.SMEAR -> smear(u, v, prevU, prevV)
            Brush.FLIP -> {
                // a little momentum into the grid too, so the pour interacts
                // with fluid already on the canvas
                val r = splatRadius * 0.5f
                flip.emit(u, v, du, dv, r, inkPerStroke, aspect,
                          perDab = flip.countFor(r, aspect),
                          axisX = contactAxisX, axisY = contactAxisY,
                          minor = contactMinorRatio)
                force(u, v, du, dv, ForceMode.PUSH.code, 0.4f)
            }
            Brush.WATERCOLOR -> wet(u, v)
            Brush.VORTEX -> {
                // lift what has set under the brush, then stir it
                liftSetPaint(u, v)
                force(u, v, du, dv, forceMode.code, forceStrength)
            }
            Brush.SOLVENT -> solvent(u, v)
            Brush.PROBE -> probe(u, v)
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

    /**
     * Drags set pigment along the stroke. Warps the background, and the wet nib
     * field too when it is in use, so a charcoal line and a pen line both smudge.
     */
    fun smear(u: Float, v: Float, prevU: Float, prevV: Float) {
        if (!allocated) return

        pSmear.use()
        pSmear.set("uPoint", u, v)
        pSmear.set("uPrev", prevU, prevV)
        pSmear.set("uRadius", smearRadius)
        pSmear.set("uAspect", aspect)
        pSmear.set("uStrength", smearStrength * inkPerStroke)
        pSmear.set("uReach", smearReach)
        pSmear.set("uSrc", 0)

        background.read.bindSampler(0)
        background.write.bindImage(0, GLES31.GL_WRITE_ONLY)
        pSmear.dispatch(dyeW, dyeH)
        background.swap()

        if (nibActive) {
            nibInkField.read.bindSampler(0)
            nibInkField.write.bindImage(0, GLES31.GL_WRITE_ONLY)
            pSmear.dispatch(dyeW, dyeH)
            nibInkField.swap()
        }
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
        setContact(pWet)
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
        // A real FLIP step. Particles scatter their momentum onto their own
        // grid, that grid is made incompressible, and the CHANGE is gathered
        // back -- which is what makes the paint behave as one body of liquid
        // instead of a spray of independent points.
        flip.particlesToGrid(flipVel.read, flipMass, flipW, flipH)

        // FLIP transfers the delta, so the pre-projection field must be kept
        blit(flipVel.read, flipVelOld)

        // empty cells are held at zero pressure, giving a free surface rather
        // than liquid sealed in a box
        projectFlipGrid(dt)

        flip.gridToParticles(dt, flipVel.read, flipVelOld, aspect, flipW, flipH)

        // freshly dried particles land in the background layer, permanently
        flip.draw(state = 2f, target = background.read)

        // live particles are redrawn from scratch each frame
        flipInk.clear()
        flip.draw(state = 1f, target = flipInk)
    }

    val flipTexture get() = flipInk

    /** Copies one field into another; FLIP needs the pre-projection velocity. */
    private fun blit(src: Tex, dst: Tex) {
        pBlit.use()
        src.bindSampler(0)
        pBlit.set("uSrc", 0)
        dst.bindImage(0, GLES31.GL_WRITE_ONLY)
        pBlit.dispatch(dst.width, dst.height)
    }

    private fun projectFlipGrid(dt: Float) {
        // consistent operators here: a thin free surface needs them
        pDivergenceFlip.use()
        flipVel.read.bindImage(0, GLES31.GL_READ_ONLY)
        flipDivergence.bindImage(1, GLES31.GL_WRITE_ONLY)
        pDivergenceFlip.dispatch(flipW, flipH)

        pClear.use()
        pClear.set("uValue", 0.6f)
        flipPressure.read.bindImage(0, GLES31.GL_READ_WRITE)
        pClear.dispatch(flipW, flipH)

        pPressureFlip.use()
        pPressureFlip.set("uMinMass", flipMinMass)
        flipDivergence.bindImage(2, GLES31.GL_READ_ONLY)
        flipMass.bindImage(3, GLES31.GL_READ_ONLY)
        val half = (flipW + 1) / 2
        for (i in 0 until flipIterations) {
            for (parity in 0..1) {
                flipPressure.read.bindImage(0, GLES31.GL_READ_WRITE)
                pPressureFlip.set("uParity", parity)
                pPressureFlip.dispatch(half, flipH)
            }
        }

        pGradSubFlip.use()
        pGradSubFlip.set("uDrag", 0f)
        pGradSubFlip.set("uDt", dt)
        pGradSubFlip.set("uMaxSpeed", maxSpeed)
        flipVel.read.bindImage(0, GLES31.GL_READ_ONLY)
        flipVel.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        flipPressure.read.bindImage(2, GLES31.GL_READ_ONLY)
        pGradSubFlip.dispatch(flipW, flipH)
        flipVel.swap()
    }

    /**
     * Lifts a little already-set paint back into the simulation under the
     * brush, so the force brushes have something to act on. This is the "wet
     * skin" the PRD left as an open question, resolved in favour of making
     * baked paint smearable rather than strictly immutable.
     */
    private fun liftSetPaint(u: Float, v: Float) {
        if (pickup <= 0f) return
        transfer(1f / 60f, force = false, thaw = true, maskAt = u to v, rate = pickup)
    }

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

        // solvent works on dried paint too; that is most of what it is for
        liftSetPaint(u, v)

        pSplat.use()
        pSplat.set("uPoint", u, v)
        pSplat.set("uAspect", aspect)
        setContact(pSplat)
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

    /**
     * Marks the reported contact patch onto the permanent layer. Reads the raw
     * measurement rather than anything the size controls have done to it, since
     * the point is to see what the device actually said.
     */
    fun probe(u: Float, v: Float) {
        if (!allocated) return
        pProbe.use()
        pProbe.set("uPoint", u, v)
        pProbe.set("uAspect", aspect)
        pProbe.set("uRadius", when (contactSource) {
            ContactSource.MEASURED -> contactMajor * 0.5f
            ContactSource.NORMALISED -> splatRadius * (0.5f + 1.5f * contactSize)
            ContactSource.NONE -> 0f
        })
        // filled for a real measurement, an outline for one merely derived from
        // a normalised area: the mark says which it is without any text
        pProbe.set("uOutline", if (contactSource == ContactSource.NORMALISED) 1 else 0)
        pProbe.set("uAxis", contactAxisX, contactAxisY)
        pProbe.set("uMinor",
                   if (contactMajor > 0f) (contactMinor / contactMajor).coerceIn(0.05f, 1f)
                   else 1f)
        pProbe.set("uDot", 0.0025f)
        background.read.bindImage(0, GLES31.GL_READ_ONLY)
        background.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pProbe.dispatch(dyeW, dyeH)
        background.swap()
        layersDirty = true
    }

    /** Inject momentum and colour. Coordinates and delta are in UV space. */
    fun splat(u: Float, v: Float, du: Float, dv: Float, r: Float, g: Float, b: Float) {
        if (!allocated) return

        pSplat.use()
        pSplat.set("uPoint", u, v)
        pSplat.set("uAspect", aspect)
        setContact(pSplat)
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
        maskAt: Pair<Float, Float>?,
        rate: Float = -1f
    ) {
        if (!allocated) return

        pBake.use()
        pBake.set("uDt", dt)
        pBake.set("uSettleSpeed", settleSpeed)
        pBake.set("uBakeRate", when {
            rate >= 0f -> rate
            thaw -> bakeRate * 3f
            else -> bakeRate
        })
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

    // ---------------- undo ----------------

    /**
     * A stroke's worth of history: the pixels of one layer as they were before
     * it was touched. Only the active layer can change during a stroke -- bake,
     * soak, smear and the FLIP retire all write there and nowhere else -- so a
     * single texture is the whole edit.
     */
    private class Snapshot(val layerIndex: Int, val tex: Tex)

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var pendingStroke: Snapshot? = null

    /**
     * A cap in bytes rather than in steps: a snapshot is a full canvas, and at
     * 2048 detail that is 15 MB apiece. Counting strokes would be a memory
     * cliff on exactly the canvases that can least afford one.
     */
    private val undoBudgetBytes = 64L * 1024 * 1024

    /**
     * Snapshots are all the same size, and allocating one at the start of every
     * stroke is a full-canvas texture create -- 15 MB at 2048 detail -- right at
     * the moment the pen touches down. Retired ones are kept for reuse instead.
     */
    private val snapshotPool = ArrayDeque<Tex>()

    private fun obtainSnapshotTex(): Tex =
        snapshotPool.removeLastOrNull() ?: Tex(dyeW, dyeH, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)

    private fun recycle(tex: Tex) {
        if (snapshotPool.size < 4 && tex.width == dyeW && tex.height == dyeH) {
            snapshotPool.addLast(tex)
        } else {
            tex.release()
        }
    }

    /** Undo history is real VRAM and belongs in the HUD's total. */
    fun historyBytes(): Long =
        undoStack.sumOf { it.tex.bytes() } + redoStack.sumOf { it.tex.bytes() } +
        snapshotPool.sumOf { it.bytes() } + (pendingStroke?.tex?.bytes() ?: 0L)

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun beginStroke() {
        if (!allocated) return
        pendingStroke?.let { recycle(it.tex) }
        pendingStroke = Snapshot(activeLayer, copyOfLayer())
    }

    fun endStroke() {
        val s = pendingStroke ?: return
        pendingStroke = null
        undoStack.addLast(s)
        trim(undoStack)
        // a new stroke is a new branch of history
        redoStack.forEach { recycle(it.tex) }
        redoStack.clear()
    }

    fun undo(): Boolean = step(undoStack, redoStack)

    fun redo(): Boolean = step(redoStack, undoStack)

    private fun step(from: ArrayDeque<Snapshot>, to: ArrayDeque<Snapshot>): Boolean {
        if (!allocated) return false
        val s = from.removeLastOrNull() ?: return false
        if (s.layerIndex !in layers.indices) {   // the stack moved under it
            recycle(s.tex)
            return false
        }
        to.addLast(Snapshot(s.layerIndex, copyOfLayer(s.layerIndex)))
        trim(to)
        blit(s.tex, layers[s.layerIndex].tex.read)
        recycle(s.tex)
        quietLiveFields()
        layersDirty = true
        return true
    }

    /**
     * Undo restores paint that has set. Anything still live would bake again a
     * moment later and undo the undo, so the simulation is stilled as well.
     */
    private fun quietLiveFields() {
        velocity.clear(); dye.clear(); age.clear(); water.clear()
        flipInk.clear(); flip.clear(); nibInkField.clear()
        flipVel.clear(); flipVelOld.clear(); flipMass.clear()
        flipPressure.clear(); flipDivergence.clear()
        pressure.clear(); curl.clear(); divergence.clear()
        waterActive = false; nibActive = false
    }

    private fun copyOfLayer(index: Int = activeLayer): Tex {
        val copy = obtainSnapshotTex()
        blit(layers[index].tex.read, copy)
        return copy
    }

    private fun trim(stack: ArrayDeque<Snapshot>) {
        var bytes = stack.sumOf { it.tex.bytes() }
        while (stack.size > 1 && bytes > undoBudgetBytes) {
            bytes -= stack.first().tex.bytes()
            recycle(stack.removeFirst().tex)
        }
    }

    /**
     * Structural changes -- a layer added, deleted or reordered -- would leave
     * every snapshot pointing at the wrong sheet, so history starts over.
     */
    fun dropHistory() {
        pendingStroke?.let { recycle(it.tex) }; pendingStroke = null
        undoStack.forEach { recycle(it.tex) }; undoStack.clear()
        redoStack.forEach { recycle(it.tex) }; redoStack.clear()
    }

    // ---------------- layers ----------------

    /** Hard cap. Each layer is two full-canvas RGBA16F textures. */
    val maxLayers = 8

    fun addLayer(): Boolean {
        if (!allocated || layers.size >= maxLayers) return false
        val fresh = Layer("Layer ${layers.size + 1}",
                          DoubleTex(dyeW, dyeH, GLES31.GL_RGBA16F, GLES31.GL_LINEAR))
        layers.add(activeLayer + 1, fresh)
        activeLayer += 1
        layersDirty = true
        dropHistory()
        return true
    }

    /** The last layer is never removed: there is always something to paint on. */
    fun deleteActiveLayer(): Boolean {
        if (!allocated || layers.size <= 1) return false
        layers.removeAt(activeLayer).tex.release()
        activeLayer = activeLayer.coerceAtMost(layers.size - 1)
        layersDirty = true
        dropHistory()
        return true
    }

    /** Moves the active layer one step up (+1) or down (-1) the stack. */
    fun moveActiveLayer(delta: Int): Boolean {
        val to = activeLayer + delta
        if (!allocated || to < 0 || to >= layers.size) return false
        val l = layers.removeAt(activeLayer)
        layers.add(to, l)
        activeLayer = to
        layersDirty = true
        dropHistory()
        return true
    }

    /** Wipes only the layer being painted on, leaving the rest of the stack. */
    fun clearActiveLayer() {
        if (!allocated) return
        beginStroke()
        endStroke()
        background.clear()
        dye.clear(); age.clear()
        layersDirty = true
    }

    /**
     * Flattens everything under the active layer, and everything over it, into
     * one texture each. Called only when the stack changed -- an eight-layer
     * canvas would otherwise cost sixteen full-canvas passes every frame.
     */
    fun recomposeLayers() {
        if (!allocated || !layersDirty) return
        layersDirty = false

        val below = layers.subList(0, activeLayer).filter { it.visible && it.opacity > 0f }
        val above = layers.subList(activeLayer + 1, layers.size).filter { it.visible && it.opacity > 0f }

        underlay = flatten(below, underlay)
        overlay = flatten(above, overlay)
    }

    /**
     * Returns a texture holding [group] composited bottom-up, or null when the
     * group is empty -- an empty stack should not cost VRAM or a sampler read.
     */
    private fun flatten(group: List<Layer>, existing: DoubleTex?): DoubleTex? {
        if (group.isEmpty()) {
            existing?.release()
            return null
        }
        val target = existing ?: DoubleTex(dyeW, dyeH, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)
        target.read.clear()
        group.forEach { l ->
            pComposite.use()
            target.read.bindSampler(0)
            l.tex.read.bindSampler(1)
            target.write.bindImage(0, GLES31.GL_WRITE_ONLY)
            pComposite.set("uOpacity", l.opacity)
            pComposite.dispatch(dyeW, dyeH)
            target.swap()
        }
        return target
    }

    private fun releaseTextures() {
        dropHistory()
        snapshotPool.forEach { it.release() }
        snapshotPool.clear()
        velocity.release(); dye.release(); age.release()
        layers.forEach { it.tex.release() }
        layers.clear()
        underlay?.release(); underlay = null
        overlay?.release(); overlay = null
        water.release(); flipInk.release(); nibInkField.release()
        flipVel.release(); flipVelOld.release(); flipMass.release()
        flipPressure.release(); flipDivergence.release()
        pressure.release(); curl.release(); divergence.release()
        statsPartial.release()
        allocated = false
    }

    fun release() {
        if (allocated) releaseTextures()
    }

    companion object {
        /**
         * Ink per dab, relative to spacing over radius. Measured: a reference
         * stroke deposits 658 units of baked ink at this value, against 658 for
         * the one-splat-per-event behaviour it replaced, and 2271 without it.
         */
        const val DAB_CALIBRATION = 0.29f

        /** Reduction tile size for [measure]. */
        const val STATS_TILE = 16

        /** Selectable simulation resolutions for the headroom sweep. */
        val RESOLUTIONS = intArrayOf(128, 256, 384, 512, 768, 1024, 1536, 2048)
    }
}
