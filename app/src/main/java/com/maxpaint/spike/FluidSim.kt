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

    // --- M1: the bake ---
    /** Speed at or below which paint begins to set. */
    var settleSpeed = 0.35f
    /** How fast settled dye transfers to the background, per second. */
    var bakeRate = 2.5f
    /** "Hold": seconds paint must stay live before it may bake at all. */
    var settleMinAge = 0.35f
    /** Set by Freeze Now; consumed on the next step. */
    @Volatile var freezeRequested = false

    // ---- resources ----
    private lateinit var velocity: DoubleTex
    private lateinit var dye: DoubleTex
    private lateinit var pressure: DoubleTex
    private lateinit var background: DoubleTex
    private lateinit var age: DoubleTex
    private lateinit var curl: Tex
    private lateinit var divergence: Tex

    private lateinit var pAdvect: ComputeProgram
    private lateinit var pSplat: ComputeProgram
    private lateinit var pCurl: ComputeProgram
    private lateinit var pVorticity: ComputeProgram
    private lateinit var pDivergence: ComputeProgram
    private lateinit var pPressure: ComputeProgram
    private lateinit var pPressureRB: ComputeProgram
    private lateinit var pClear: ComputeProgram
    private lateinit var pGradSub: ComputeProgram
    private lateinit var pBake: ComputeProgram

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
        pSplat = ComputeProgram(ctx, "shaders/splat.comp")
        pCurl = ComputeProgram(ctx, "shaders/curl.comp")
        pVorticity = ComputeProgram(ctx, "shaders/vorticity.comp")
        pDivergence = ComputeProgram(ctx, "shaders/divergence.comp")
        pPressure = ComputeProgram(ctx, "shaders/pressure.comp")
        pPressureRB = ComputeProgram(ctx, "shaders/pressure_rb.comp")
        pClear = ComputeProgram(ctx, "shaders/clearp.comp")
        pGradSub = ComputeProgram(ctx, "shaders/gradsub.comp")
        pBake = ComputeProgram(ctx, "shaders/bake.comp")
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

        // 3. divergence
        pDivergence.use()
        velocity.read.bindImage(0, GLES31.GL_READ_ONLY)
        divergence.bindImage(1, GLES31.GL_WRITE_ONLY)
        pDivergence.dispatch(simRes, simRes)

        // 4. decay previous pressure (warm start beats a cold clear)
        pClear.use()
        pClear.set("uValue", 0.8f)
        pressure.read.bindImage(0, GLES31.GL_READ_WRITE)
        pClear.dispatch(simRes, simRes)

        // 5. pressure solve
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

        // 6. project
        pGradSub.use()
        pGradSub.set("uDrag", velocityDrag)
        pGradSub.set("uDt", dt)
        velocity.read.bindImage(0, GLES31.GL_READ_ONLY)
        velocity.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pressure.read.bindImage(2, GLES31.GL_READ_ONLY)
        pGradSub.dispatch(simRes, simRes)
        velocity.swap()

        // 7. advect velocity
        pAdvect.use()
        pAdvect.set("uDt", dt)
        pAdvect.set("uDstTexel", 1f / simRes, 1f / simRes)
        pAdvect.set("uDissipation", 0f)
        velocity.read.bindSampler(0)
        velocity.read.bindSampler(1)
        velocity.write.bindImage(0, GLES31.GL_WRITE_ONLY)
        pAdvect.dispatch(simRes, simRes)
        velocity.swap()

        // 8. advect dye
        pAdvect.set("uDstTexel", 1f / dyeRes, 1f / dyeRes)
        pAdvect.set("uDissipation", dyeDissipation)
        dye.read.bindSampler(0)
        velocity.read.bindSampler(1)
        dye.write.bindImage(0, GLES31.GL_WRITE_ONLY)
        pAdvect.dispatch(dyeRes, dyeRes)
        dye.swap()

        // 9. advect age along with the dye it belongs to
        pAdvect.set("uDissipation", 0f)
        age.read.bindSampler(0)
        velocity.read.bindSampler(1)
        age.write.bindImage(0, GLES31.GL_WRITE_ONLY)
        pAdvect.dispatch(dyeRes, dyeRes)
        age.swap()

        // 10. bake: settled fluid moves out of the sim and into the background
        bake(dt)
    }

    private fun bake(dt: Float) {
        val force = freezeRequested
        freezeRequested = false

        pBake.use()
        pBake.set("uDt", dt)
        pBake.set("uSettleSpeed", settleSpeed)
        pBake.set("uBakeRate", bakeRate)
        pBake.set("uSettleMinAge", settleMinAge)
        pBake.set("uForce", if (force) 1 else 0)

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
        allocated = false
    }

    fun release() {
        if (allocated) releaseTextures()
    }

    companion object {
        /** Selectable simulation resolutions for the M0 headroom sweep. */
        val RESOLUTIONS = intArrayOf(128, 256, 384, 512, 768, 1024, 1536, 2048)
    }
}
