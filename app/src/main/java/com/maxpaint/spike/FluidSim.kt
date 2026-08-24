package com.maxpaint.spike

import android.content.Context
import android.opengl.GLES31

/**
 * M0 spike: gaseous 2D fluid on GLES 3.1 compute shaders.
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
    var simRes = 512; private set
    var dyeScale = 1; private set
    var pressureIterations = 30
    /** Red-black Gauss-Seidel converges ~2x faster per sweep than Jacobi at the
     *  same thread count, and needs one pressure texture instead of two.
     *  See tools/compare_solvers.py for the measurement. */
    var useRedBlack = true
    var vorticity = 22f
    var velocityDrag = 0.12f      // "drag" from the PRD; higher = paint sets sooner
    var dyeDissipation = 0.05f
    var splatRadius = 0.02f
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
    var bakeRate = 2.5f
    /** "Hold": seconds paint must stay live before it may bake at all. */
    var settleMinAge = 0.35f
    /** Set by Freeze Now; consumed on the next step. */
    @Volatile var freezeRequested = false
    /** Set by global Thaw; consumed on the next step. */
    @Volatile var thawRequested = false

    // --- brushes ---
    var brush = Brush.GAS
    var forceMode = ForceMode.SWIRL
    /** Sustained stirring inflates ink (see README); keep the default gentle. */
    var forceStrength = 1.0f
    var combFrequency = 14f
    /** Fraction of pigment the solvent leaves behind at its centre. */
    var solventBite = 0.45f

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
    private lateinit var statsPartial: Tex
    private var partialRes = 1

    private var aspect = 1f
    private var allocated = false

    val dyeRes get() = simRes * dyeScale
    val dyeTexture get() = dye.read
    val backgroundTexture get() = background.read
    val velocityTexture get() = velocity.read

    fun vramBytes(): Long =
        if (!allocated) 0
        else velocity.bytes() + dye.bytes() + background.bytes() + age.bytes() +
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
    }

    fun setAspect(a: Float) { aspect = a }

    /** (Re)allocate all fields. Safe to call at runtime when the user picks a new resolution. */
    fun allocate(newSimRes: Int, newDyeScale: Int) {
        if (allocated) releaseTextures()
        simRes = newSimRes
        dyeScale = newDyeScale

        velocity = DoubleTex(simRes, simRes, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)
        dye = DoubleTex(dyeRes, dyeRes, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)
        // Baked paint lives at dye resolution for the spike. The PRD wants it at
        // full canvas resolution (7.2); that arrives with the document model in M3.
        background = DoubleTex(dyeRes, dyeRes, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)
        age = DoubleTex(dyeRes, dyeRes, GLES31.GL_RGBA16F, GLES31.GL_NEAREST)

        partialRes = (dyeRes + STATS_TILE - 1) / STATS_TILE
        statsPartial = Tex(partialRes, partialRes, GLES31.GL_RGBA32F, GLES31.GL_NEAREST)
        pressure = DoubleTex(simRes, simRes, GLES31.GL_R32F, GLES31.GL_NEAREST)
        curl = Tex(simRes, simRes, GLES31.GL_R32F, GLES31.GL_NEAREST)
        divergence = Tex(simRes, simRes, GLES31.GL_R32F, GLES31.GL_NEAREST)

        allocated = true
        GLUtil.checkError("allocate($simRes, dye=$dyeRes)")
    }

    fun clear() {
        if (!allocated) return
        velocity.clear(); dye.clear(); background.clear(); age.clear()
        pressure.clear(); curl.clear(); divergence.clear()
    }

    /**
     * A stroke sample. Coordinates and delta are in UV space; the brush decides
     * what that means — pigment, pure force, or a local freeze.
     */
    fun stroke(u: Float, v: Float, du: Float, dv: Float, r: Float, g: Float, b: Float) {
        if (!allocated) return

        when (brush) {
            Brush.GAS, Brush.FLIP, Brush.WATERCOLOR -> splat(u, v, du, dv, r, g, b)
            Brush.VORTEX -> force(u, v, du, dv, forceMode.code, forceStrength)
            Brush.SOLVENT -> solvent(u, v)
            Brush.FREEZE -> transfer(1f / 60f, force = true, thaw = false, maskAt = u to v)
            Brush.THAW -> transfer(1f / 60f, force = false, thaw = true, maskAt = u to v)
        }
    }

    /** Momentum with no pigment: stir, shove, pinch or comb what is already there. */
    fun force(u: Float, v: Float, du: Float, dv: Float, mode: Int, strength: Float) {
        if (!allocated) return
        pForce.use()
        pForce.set("uPoint", u, v)
        pForce.set("uDir", du, dv)
        pForce.set("uRadius", splatRadius * 1.5f)
        pForce.set("uAspect", aspect)
        pForce.set("uStrength", strength)
        pForce.set("uMode", mode)
        pForce.set("uCombFreq", combFrequency)
        pForce.set("uDt", 1f / 60f)
        velocity.read.bindImage(0, GLES31.GL_READ_ONLY)
        velocity.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pForce.dispatch(simRes, simRes)
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
        pSplat.dispatch(dyeRes, dyeRes)
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
        pSplat.set("uValue", du * velocityGain, dv * velocityGain, 0f, 0f)
        velocity.read.bindImage(0, GLES31.GL_READ_ONLY)
        velocity.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pSplat.dispatch(simRes, simRes)
        velocity.swap()

        // dye - a slightly tighter splat reads as a crisper mark.
        // Colour is premultiplied by coverage so compositing is a plain "over".
        pSplat.set("uRadius", splatRadius * 0.6f)
        pSplat.set("uValue", r, g, b, 1f)
        dye.read.bindImage(0, GLES31.GL_READ_ONLY)
        dye.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pSplat.dispatch(dyeRes, dyeRes)
        dye.swap()

        // Freshly injected paint is new, so its age restarts. Lerp rather than
        // add, or "Hold" would never apply to a stroke drawn over an old one.
        pSplat.set("uMode", 1)
        pSplat.set("uValue", 0f, 0f, 0f, 0f)
        age.read.bindImage(0, GLES31.GL_READ_ONLY)
        age.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pSplat.dispatch(dyeRes, dyeRes)
        age.swap()
        pSplat.set("uMode", 0)
    }

    /** Bake every bit of live fluid into the background, now. */
    fun freezeNow() { freezeRequested = true }

    /** Lift the most recent baked paint back into the simulation. */
    fun thaw() { thawRequested = true }

    /** True when there is no live fluid left to simulate. */
    fun isIdle(): Boolean = false

    fun step(dt: Float) {
        if (!allocated) return

        // 1. curl
        pCurl.use()
        velocity.read.bindImage(0, GLES31.GL_READ_ONLY)
        curl.bindImage(1, GLES31.GL_WRITE_ONLY)
        pCurl.dispatch(simRes, simRes)

        // 2. vorticity confinement
        if (vorticity > 0f) {
            pVorticity.use()
            pVorticity.set("uStrength", vorticity)
            pVorticity.set("uDt", dt)
            velocity.read.bindImage(0, GLES31.GL_READ_ONLY)
            velocity.write.bindImage(1, GLES31.GL_WRITE_ONLY)
            curl.bindImage(2, GLES31.GL_READ_ONLY)
            pVorticity.dispatch(simRes, simRes)
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
        pAdv.set("uDstTexel", 1f / simRes, 1f / simRes)
        pAdv.set("uDissipation", 0f)
        velocity.read.bindSampler(0)
        velocity.read.bindSampler(1)
        velocity.write.bindImage(0, GLES31.GL_WRITE_ONLY)
        pAdv.dispatch(simRes, simRes)
        velocity.swap()

        // 8. advect dye
        pAdv.set("uDstTexel", 1f / dyeRes, 1f / dyeRes)
        pAdv.set("uDissipation", dyeDissipation)
        dye.read.bindSampler(0)
        velocity.read.bindSampler(1)
        dye.write.bindImage(0, GLES31.GL_WRITE_ONLY)
        pAdv.dispatch(dyeRes, dyeRes)
        dye.swap()

        // 9. advect age along with the dye it belongs to
        pAdv.set("uDissipation", 0f)
        age.read.bindSampler(0)
        velocity.read.bindSampler(1)
        age.write.bindImage(0, GLES31.GL_WRITE_ONLY)
        pAdv.dispatch(dyeRes, dyeRes)
        age.swap()

        // 10. bake: settled fluid moves out of the sim and into the background
        bake(dt)
    }

    private fun computeDivergence() {
        pDivergence.use()
        velocity.read.bindImage(0, GLES31.GL_READ_ONLY)
        divergence.bindImage(1, GLES31.GL_WRITE_ONLY)
        pDivergence.dispatch(simRes, simRes)
    }

    private fun decayPressure(factor: Float) {
        pClear.use()
        pClear.set("uValue", factor)
        pressure.read.bindImage(0, GLES31.GL_READ_WRITE)
        pClear.dispatch(simRes, simRes)
    }

    private fun solvePressure() {
        if (useRedBlack) {
            // In-place red-black Gauss-Seidel. Legal because pressure is r32f,
            // the one format ES 3.1 allows read-write images for. Each sweep is
            // two half-width dispatches, so thread count matches one Jacobi pass.
            pPressureRB.use()
            divergence.bindImage(2, GLES31.GL_READ_ONLY)
            val halfWidth = (simRes + 1) / 2
            for (i in 0 until pressureIterations) {
                for (parity in 0..1) {
                    pressure.read.bindImage(0, GLES31.GL_READ_WRITE)
                    pPressureRB.set("uParity", parity)
                    pPressureRB.dispatch(halfWidth, simRes)
                }
            }
        } else {
            pPressure.use()
            divergence.bindImage(2, GLES31.GL_READ_ONLY)
            for (i in 0 until pressureIterations) {
                pressure.read.bindImage(0, GLES31.GL_READ_ONLY)
                pressure.write.bindImage(1, GLES31.GL_WRITE_ONLY)
                pPressure.dispatch(simRes, simRes)
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
        pGradSub.dispatch(simRes, simRes)
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
        pStats.dispatch(partialRes, partialRes)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)

        val n = partialRes * partialRes * 4
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
            0, 0, partialRes, partialRes,
            GLES31.GL_RGBA, GLES31.GL_FLOAT, buf
        )
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glDeleteFramebuffers(1, fbo, 0)

        var ink = 0.0
        var div2 = 0.0
        for (i in 0 until partialRes * partialRes) {
            ink += buf.get(i * 4)
            div2 += buf.get(i * 4 + 1)
        }
        val cells = (simRes.toDouble() * simRes.toDouble()).coerceAtLeast(1.0)
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

        dye.read.bindImage(0, GLES31.GL_READ_ONLY)
        dye.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        background.read.bindImage(2, GLES31.GL_READ_ONLY)
        background.write.bindImage(3, GLES31.GL_WRITE_ONLY)
        age.read.bindImage(4, GLES31.GL_READ_ONLY)
        age.write.bindImage(5, GLES31.GL_WRITE_ONLY)
        velocity.read.bindSampler(0)

        pBake.dispatch(dyeRes, dyeRes)
        dye.swap(); background.swap(); age.swap()
    }

    private fun releaseTextures() {
        velocity.release(); dye.release(); background.release(); age.release()
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

        /** Selectable simulation resolutions for the M0 headroom sweep. */
        val RESOLUTIONS = intArrayOf(128, 256, 384, 512, 768, 1024, 1536, 2048)
    }
}
