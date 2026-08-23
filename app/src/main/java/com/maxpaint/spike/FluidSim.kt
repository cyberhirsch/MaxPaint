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
    var vorticity = 22f
    var velocityDrag = 0.12f      // "drag" from the PRD; higher = paint sets sooner
    var dyeDissipation = 0.05f
    var splatRadius = 0.02f
    var velocityGain = 1.0f

    // ---- resources ----
    private lateinit var velocity: DoubleTex
    private lateinit var dye: DoubleTex
    private lateinit var pressure: DoubleTex
    private lateinit var curl: Tex
    private lateinit var divergence: Tex

    private lateinit var pAdvect: ComputeProgram
    private lateinit var pSplat: ComputeProgram
    private lateinit var pCurl: ComputeProgram
    private lateinit var pVorticity: ComputeProgram
    private lateinit var pDivergence: ComputeProgram
    private lateinit var pPressure: ComputeProgram
    private lateinit var pClear: ComputeProgram
    private lateinit var pGradSub: ComputeProgram

    private var aspect = 1f
    private var allocated = false

    val dyeRes get() = simRes * dyeScale
    val dyeTexture get() = dye.read
    val velocityTexture get() = velocity.read

    fun vramBytes(): Long =
        if (!allocated) 0
        else velocity.bytes() + dye.bytes() + pressure.bytes() + curl.bytes() + divergence.bytes()

    fun initPrograms() {
        pAdvect = ComputeProgram(ctx, "shaders/advect.comp")
        pSplat = ComputeProgram(ctx, "shaders/splat.comp")
        pCurl = ComputeProgram(ctx, "shaders/curl.comp")
        pVorticity = ComputeProgram(ctx, "shaders/vorticity.comp")
        pDivergence = ComputeProgram(ctx, "shaders/divergence.comp")
        pPressure = ComputeProgram(ctx, "shaders/pressure.comp")
        pClear = ComputeProgram(ctx, "shaders/clearp.comp")
        pGradSub = ComputeProgram(ctx, "shaders/gradsub.comp")
    }

    fun setAspect(a: Float) { aspect = a }

    /** (Re)allocate all fields. Safe to call at runtime when the user picks a new resolution. */
    fun allocate(newSimRes: Int, newDyeScale: Int) {
        if (allocated) releaseTextures()
        simRes = newSimRes
        dyeScale = newDyeScale

        velocity = DoubleTex(simRes, simRes, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)
        dye = DoubleTex(dyeRes, dyeRes, GLES31.GL_RGBA16F, GLES31.GL_LINEAR)
        pressure = DoubleTex(simRes, simRes, GLES31.GL_R32F, GLES31.GL_NEAREST)
        curl = Tex(simRes, simRes, GLES31.GL_R32F, GLES31.GL_NEAREST)
        divergence = Tex(simRes, simRes, GLES31.GL_R32F, GLES31.GL_NEAREST)

        allocated = true
        GLUtil.checkError("allocate($simRes, dye=$dyeRes)")
    }

    fun clear() {
        if (!allocated) return
        velocity.clear(); dye.clear(); pressure.clear()
        curl.clear(); divergence.clear()
    }

    /** Inject momentum and colour. Coordinates and delta are in UV space. */
    fun splat(u: Float, v: Float, du: Float, dv: Float, r: Float, g: Float, b: Float) {
        if (!allocated) return

        pSplat.use()
        pSplat.set("uPoint", u, v)
        pSplat.set("uAspect", aspect)

        // velocity
        pSplat.set("uRadius", splatRadius)
        pSplat.set("uValue", du * velocityGain, dv * velocityGain, 0f, 0f)
        velocity.read.bindImage(0, GLES31.GL_READ_ONLY)
        velocity.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pSplat.dispatch(simRes, simRes)
        velocity.swap()

        // dye - a slightly tighter splat reads as a crisper mark
        pSplat.set("uRadius", splatRadius * 0.6f)
        pSplat.set("uValue", r, g, b, 1f)
        dye.read.bindImage(0, GLES31.GL_READ_ONLY)
        dye.write.bindImage(1, GLES31.GL_WRITE_ONLY)
        pSplat.dispatch(dyeRes, dyeRes)
        dye.swap()
    }

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

        // 5. Jacobi pressure solve
        pPressure.use()
        divergence.bindImage(2, GLES31.GL_READ_ONLY)
        for (i in 0 until pressureIterations) {
            pressure.read.bindImage(0, GLES31.GL_READ_ONLY)
            pressure.write.bindImage(1, GLES31.GL_WRITE_ONLY)
            pPressure.dispatch(simRes, simRes)
            pressure.swap()
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
    }

    private fun releaseTextures() {
        velocity.release(); dye.release(); pressure.release()
        curl.release(); divergence.release()
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
