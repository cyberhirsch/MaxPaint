package com.maxpaint.spike

import android.content.Context
import android.opengl.GLES31
import android.opengl.GLSurfaceView
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min

class FluidRenderer(private val ctx: Context) : GLSurfaceView.Renderer {

    val sim = FluidSim(ctx)

    private var displayProgram = 0
    private var vao = 0
    private var viewW = 1
    private var viewH = 1

    /** Set from the UI thread; applied on the GL thread at the top of the next frame. */
    @Volatile var pendingSimRes: Int = 512
    @Volatile var pendingDyeScale: Int = 2
    @Volatile var debugView = 0
    @Volatile var heatOverlay = false
    @Volatile var paused = false
    @Volatile var benchmarkRequested = false
    @Volatile var particleBenchmarkRequested = false
    @Volatile var clearRequested = false
    @Volatile var freezeRequested = false
    @Volatile var thawRequested = false
    @Volatile var exportRequested = false
    @Volatile var undoRequested = false
    @Volatile var redoRequested = false

    /** Handed the finished frame on the GL thread; saving happens off it. */
    @Volatile var onExported: ((android.graphics.Bitmap) -> Unit)? = null

    @Volatile var statsLine: String = ""
    @Volatile var benchmarkReport: String? = null
    @Volatile var deviceInfo: String = ""

    private var appliedSimRes = -1
    private var appliedDyeScale = -1
    private var unsupported = false

    /**
     * Stroke boundaries travel in the same queue as the samples rather than as
     * their own flags: a finger lifting and landing again inside one frame has
     * to stay two strokes, and two booleans cannot express that.
     */
    private class Touch(val kind: Int,
                        val u: Float = 0f, val v: Float = 0f,
                        val du: Float = 0f, val dv: Float = 0f,
                        val r: Float = 0f, val g: Float = 0f, val b: Float = 0f,
                        val pressure: Float = 1f,
                        val prevU: Float = 0f, val prevV: Float = 0f,
                        // the contact patch for THIS sample: a finger rolls
                        // during a stroke, so it cannot ride on the renderer
                        // as tilt does and still describe the right dab
                        val major: Float = 0f, val minor: Float = 0f,
                        val angle: Float = 0f,
                        // normalised 0..1, the fallback where no driver
                        // reports an actual length
                        val size: Float = 0f) {
        companion object {
            const val SAMPLE = 0
            const val BEGIN = 1
            const val END = 2
        }
    }

    private val touches = ConcurrentLinkedQueue<Touch>()

    private var lastFrameNs = 0L
    private val frameTimes = ArrayDeque<Double>()

    /**
     * Where a finger is resting, or null. Held on the renderer rather than in
     * the touch queue because a pour is driven by the clock: the point is that
     * paint keeps arriving when no events are.
     */
    @Volatile var heldU = 0f
    @Volatile var heldV = 0f
    @Volatile var holding = false

    /** The held point last frame; a pour only runs when it stopped moving. */
    private var pourU = -1f
    private var pourV = -1f

    /** Stylus tilt widens the mark; 1.0 is an upright pen (PRD FR-6). */
    @Volatile var tiltSpread = 1f

    fun queueSplat(
        u: Float, v: Float, du: Float, dv: Float,
        r: Float, g: Float, b: Float, pressure: Float = 1f,
        prevU: Float = u, prevV: Float = v,
        major: Float = 0f, minor: Float = 0f, angle: Float = 0f, size: Float = 0f
    ) {
        touches.add(Touch(Touch.SAMPLE, u, v, du, dv, r, g, b, pressure,
                          prevU, prevV, major, minor, angle, size))
    }

    fun queueStrokeBegin() = touches.add(Touch(Touch.BEGIN))

    fun queueStrokeEnd() = touches.add(Touch(Touch.END))

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        deviceInfo = "${GLES31.glGetString(GLES31.GL_RENDERER)} / ${GLES31.glGetString(GLES31.GL_VERSION)}"
        ScratchFbo.invalidate()   // the old context's framebuffer name is gone

        if (!hasComputeSupport()) {
            unsupported = true
            statsLine = "This device does not expose OpenGL ES 3.1.\nCompute shaders are required.\nReported: $deviceInfo"
            return
        }

        sim.initPrograms()

        displayProgram = GLUtil.link(
            GLUtil.compile(GLES31.GL_VERTEX_SHADER, GLUtil.readAsset(ctx, "shaders/display.vert"), "display.vert"),
            GLUtil.compile(GLES31.GL_FRAGMENT_SHADER, GLUtil.readAsset(ctx, "shaders/display.frag"), "display.frag")
        )

        // ES 3.1 core profile still requires a bound VAO for attribute-less draws
        val vaos = IntArray(1)
        GLES31.glGenVertexArrays(1, vaos, 0)
        vao = vaos[0]

        appliedSimRes = -1   // force allocation on first frame
        lastFrameNs = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES31.glViewport(0, 0, width, height)
        sim.setAspect(width.toFloat() / height.toFloat())
    }

    /** Compute shaders are ES 3.1+; GLSurfaceView will happily hand us a 3.0 context. */
    private fun hasComputeSupport(): Boolean {
        val v = GLES31.glGetString(GLES31.GL_VERSION) ?: return false
        val m = Regex("OpenGL ES (\\d+)\\.(\\d+)").find(v) ?: return false
        val (major, minor) = m.destructured
        return major.toInt() > 3 || (major.toInt() == 3 && minor.toInt() >= 1)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (unsupported) {
            GLES31.glClearColor(1f, 0.9f, 0.9f, 1f)
            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)
            return
        }

        val now = System.nanoTime()
        var dt = (now - lastFrameNs) / 1_000_000_000f
        lastFrameNs = now
        // never let a hitch blow up the simulation
        dt = dt.coerceIn(1f / 240f, 1f / 30f)

        if (pendingSimRes != appliedSimRes || pendingDyeScale != appliedDyeScale) {
            sim.allocate(pendingSimRes, pendingDyeScale)
            appliedSimRes = pendingSimRes
            appliedDyeScale = pendingDyeScale
            frameTimes.clear()
        }

        if (benchmarkRequested) {
            benchmarkRequested = false
            runBenchmark()
            return
        }

        if (particleBenchmarkRequested) {
            particleBenchmarkRequested = false
            val pb = ParticleBenchmark(sim)
            pb.run()
            benchmarkReport = pb.report(deviceInfo)
            lastFrameNs = System.nanoTime()
            return
        }

        if (clearRequested) {
            clearRequested = false
            sim.clear()
            touches.clear()
        }

        sim.recomposeLayers()

        if (exportRequested) {
            exportRequested = false
            exportPng()
        }

        if (freezeRequested) {
            freezeRequested = false
            sim.freezeNow()
        }
        if (thawRequested) {
            thawRequested = false
            sim.thaw()
        }

        while (true) {
            val t = touches.poll() ?: break
            when (t.kind) {
                Touch.BEGIN -> sim.beginStroke()
                Touch.END -> sim.endStroke()
                else -> {
                    sim.stroke(t.u, t.v, t.du, t.dv, t.r, t.g, t.b, t.pressure,
                               tiltSpread, t.prevU, t.prevV)
                }
            }
        }

        // The pour runs the whole time the finger is down -- it is the
        // particle medium's only emitter. It carries the finger's velocity,
        // scaled inside pour() by Motion inheritance, so a still finger
        // puddles and a fast gesture throws a jet. The velocity convention
        // matches the stroke dabs' (delta x 12 per 60Hz event), independent
        // of the frame rate.
        if (holding) {
            val du: Float
            val dv: Float
            if (pourU >= 0f && dt > 0f) {
                val scale = 12f / (60f * dt)
                du = (heldU - pourU) * scale
                dv = (heldV - pourV) * scale
            } else {
                du = 0f; dv = 0f
            }
            pourU = heldU
            pourV = heldV
            sim.pour(heldU, heldV, du, dv, dt)
        } else {
            pourU = -1f
            pourV = -1f
        }

        if (undoRequested) { undoRequested = false; sim.undo() }
        if (redoRequested) { redoRequested = false; sim.redo() }

        if (!paused) sim.step(dt)

        render()
        updateStats(dt)
    }

    private fun render() {
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glViewport(0, 0, viewW, viewH)
        GLES31.glClearColor(1f, 1f, 1f, 1f)   // paper
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)
        drawCanvas(debugView, heatOverlay)
    }

    /** The composite, drawn into whatever framebuffer is bound. */
    private fun drawCanvas(view: Int, heat: Boolean) {
        GLES31.glUseProgram(displayProgram)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(displayProgram, "uDebugView"), view)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(displayProgram, "uHeat"), if (heat) 1 else 0)
        GLES31.glUniform1f(GLES31.glGetUniformLocation(displayProgram, "uSettleSpeed"), sim.settleSpeed)
        sim.dyeTexture.bindSampler(0)
        sim.velocityTexture.bindSampler(1)
        sim.backgroundTexture.bindSampler(2)
        sim.waterTexture.bindSampler(3)
        sim.flipTexture.bindSampler(4)
        sim.nibTexture.bindSampler(5)

        val under = sim.underlayTexture
        val over = sim.overlayTexture
        under?.bindSampler(6)
        over?.bindSampler(7)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(displayProgram, "uHasUnder"), if (under != null) 1 else 0)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(displayProgram, "uHasOver"), if (over != null) 1 else 0)
        val active = sim.layers.getOrNull(sim.activeLayer)
        GLES31.glUniform1f(
            GLES31.glGetUniformLocation(displayProgram, "uActiveAlpha"),
            if (active == null || !active.visible) 0f else active.opacity
        )
        GLES31.glUniform1i(
            GLES31.glGetUniformLocation(displayProgram, "uShowWater"),
            if (sim.waterActive) 1 else 0
        )

        GLES31.glBindVertexArray(vao)
        GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
        GLES31.glBindVertexArray(0)
    }

    /**
     * Renders the canvas at its own resolution rather than the screen's, so the
     * saved image is the painting and not the phone's viewport. glReadPixels
     * hands back rows bottom-up, hence the flip.
     */
    private fun exportPng() {
        val w = sim.dyeW
        val h = sim.dyeH
        val target = Tex(w, h, GLES31.GL_RGBA8, GLES31.GL_NEAREST)
        try {
            ScratchFbo.bind(target)
            GLES31.glViewport(0, 0, w, h)
            GLES31.glClearColor(1f, 1f, 1f, 1f)
            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)
            drawCanvas(view = 0, heat = false)   // never export the debug overlays

            val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4)
                .order(java.nio.ByteOrder.nativeOrder())
            GLES31.glReadPixels(0, 0, w, h, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, buf)
            buf.rewind()

            val flipped = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888
            )
            flipped.copyPixelsFromBuffer(buf)
            val m = android.graphics.Matrix().apply { postScale(1f, -1f) }
            val out = android.graphics.Bitmap.createBitmap(flipped, 0, 0, w, h, m, false)
            if (out !== flipped) flipped.recycle()
            onExported?.invoke(out)
        } finally {
            ScratchFbo.unbind()
            target.release()
            GLES31.glViewport(0, 0, viewW, viewH)
        }
    }

    private fun runBenchmark() {
        val saveRes = appliedSimRes
        val saveDye = appliedDyeScale
        val bench = Benchmark(sim, dyeScale = pendingDyeScale)
        bench.run()
        benchmarkReport = bench.report(deviceInfo)

        // restore whatever the user had selected
        sim.allocate(saveRes, saveDye)
        sim.clear()
        frameTimes.clear()
        lastFrameNs = System.nanoTime()
    }

    private fun updateStats(dt: Float) {
        frameTimes.addLast(dt * 1000.0)
        while (frameTimes.size > 60) frameTimes.removeFirst()
        val avg = frameTimes.average()
        val worst = frameTimes.maxOrNull() ?: 0.0
        val vram = sim.vramBytes() / (1024.0 * 1024.0)

        statsLine = String.format(
            "%s · %dx%d%s  %s x%d\n%.1f fps   %.2f ms (worst %.2f)\nVRAM %.1f MB   layer %d/%d",
            sim.brush.label, sim.simW, sim.simH,
            if (sim.flip.inUse) "  drops ${sim.flipW}x${sim.flipH}" else "",
            if (sim.useRedBlack) "RB-GS" else "Jacobi", sim.pressureIterations,
            if (avg > 0) 1000.0 / avg else 0.0, avg, worst, vram,
            sim.activeLayer + 1, sim.layers.size.coerceAtLeast(1)
        )
    }

    fun release() {
        sim.release()
        if (displayProgram != 0) GLES31.glDeleteProgram(displayProgram)
    }
}
