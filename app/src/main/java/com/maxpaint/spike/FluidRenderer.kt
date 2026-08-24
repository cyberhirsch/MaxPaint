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
    @Volatile var pendingDyeScale: Int = 1
    @Volatile var debugView = 0
    @Volatile var heatOverlay = false
    @Volatile var paused = false
    @Volatile var benchmarkRequested = false
    @Volatile var clearRequested = false
    @Volatile var freezeRequested = false
    @Volatile var thawRequested = false

    @Volatile var statsLine: String = ""
    @Volatile var benchmarkReport: String? = null
    @Volatile var deviceInfo: String = ""

    private var appliedSimRes = -1
    private var appliedDyeScale = -1
    private var unsupported = false

    private class Touch(val u: Float, val v: Float, val du: Float, val dv: Float,
                        val r: Float, val g: Float, val b: Float, val pressure: Float)

    private val touches = ConcurrentLinkedQueue<Touch>()

    private var lastFrameNs = 0L
    private val frameTimes = ArrayDeque<Double>()

    /** Stylus tilt widens the mark; 1.0 is an upright pen (PRD FR-6). */
    @Volatile var tiltSpread = 1f

    fun queueSplat(
        u: Float, v: Float, du: Float, dv: Float,
        r: Float, g: Float, b: Float, pressure: Float = 1f
    ) {
        touches.add(Touch(u, v, du, dv, r, g, b, pressure))
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        deviceInfo = "${GLES31.glGetString(GLES31.GL_RENDERER)} / ${GLES31.glGetString(GLES31.GL_VERSION)}"

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

        if (clearRequested) {
            clearRequested = false
            sim.clear()
            touches.clear()
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
            sim.stroke(t.u, t.v, t.du, t.dv, t.r, t.g, t.b, t.pressure, tiltSpread)
        }

        if (!paused) sim.step(dt)

        render()
        updateStats(dt)
    }

    private fun render() {
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glViewport(0, 0, viewW, viewH)
        GLES31.glClearColor(1f, 1f, 1f, 1f)   // paper
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)

        GLES31.glUseProgram(displayProgram)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(displayProgram, "uDebugView"), debugView)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(displayProgram, "uHeat"), if (heatOverlay) 1 else 0)
        GLES31.glUniform1f(GLES31.glGetUniformLocation(displayProgram, "uSettleSpeed"), sim.settleSpeed)
        sim.dyeTexture.bindSampler(0)
        sim.velocityTexture.bindSampler(1)
        sim.backgroundTexture.bindSampler(2)
        sim.waterTexture.bindSampler(3)
        sim.flipTexture.bindSampler(4)
        GLES31.glUniform1i(
            GLES31.glGetUniformLocation(displayProgram, "uShowWater"),
            if (sim.waterActive) 1 else 0
        )

        GLES31.glBindVertexArray(vao)
        GLES31.glDrawArrays(GLES31.GL_TRIANGLES, 0, 3)
        GLES31.glBindVertexArray(0)
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
            "%s · %d²  dye %d²  %s x%d\n%.1f fps   %.2f ms (worst %.2f)\nVRAM %.1f MB",
            sim.brush.label, sim.simRes, sim.dyeRes,
            if (sim.useRedBlack) "RB-GS" else "Jacobi", sim.pressureIterations,
            if (avg > 0) 1000.0 / avg else 0.0, avg, worst, vram
        )
    }

    fun release() {
        sim.release()
        if (displayProgram != 0) GLES31.glDeleteProgram(displayProgram)
    }
}
