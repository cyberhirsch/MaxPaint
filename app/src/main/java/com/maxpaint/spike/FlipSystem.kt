package com.maxpaint.spike

import android.content.Context
import android.opengl.GLES31

/**
 * FLIP-style particle paint: liquid that pours, drips and runs.
 *
 * The particle pool is a single GPU buffer bound two ways — as a shader storage
 * buffer for the compute passes that emit and update particles, and as a vertex
 * buffer for the draws that render them. That avoids reading storage buffers in
 * a vertex shader, which ES 3.1 permits an implementation to support zero of.
 *
 * Particles are drawn as soft round points with additive blending rather than
 * scattered by hand, which sidesteps the float atomics ES 3.1 lacks.
 *
 * There is no gravity: a canvas has no up. Paint travels on the momentum of the
 * stroke and stops where drag stops it.
 */
class FlipSystem(private val ctx: Context, val capacity: Int = 400_000) {

    /**
     * How much of its own motion a particle keeps rather than taking the
     * grid's. 0 is pure PIC, 1 is pure FLIP; above 1 it extrapolates, keeping
     * more than all of it, which is livelier and closer to unstable.
     */
    var flipRatio = 0.6f
    var particleDrag = 0.25f
    /** CFL guard for particles, matching the grid's. */
    var maxSpeed = 4f
    /** Surface tension. With no gravity, this is what gathers paint into
     *  droplets rather than letting it disperse. */
    var cohesion = 1f
    var settleSpeed = 0.06f
    var settleMinAge = 0.25f
    var pointSize = 3f
    var inkPerParticle = 0.14f
    /**
     * Particles per grid cell of the dab's footprint, not particles per dab.
     *
     * Density is what the solve actually responds to -- coupling was measured
     * to peak between eight and thirteen particles per occupied cell -- and a
     * fixed count per dab does not hold density: a wider brush spreads the same
     * particles over more cells, so changing Brush size silently changed how
     * thick the medium behaved. The count is derived from the footprint.
     */
    var particlesPerCell = 120f

    private var buffer = 0
    private var vao = 0
    private var head = 0
    private var seed = 1f

    private lateinit var pEmit: ComputeProgram
    private lateinit var pClearGrid: ComputeProgram
    private lateinit var pP2G: ComputeProgram
    private lateinit var pNormalize: ComputeProgram
    private lateinit var pG2P: ComputeProgram
    private var drawProgram = 0

    /** Fixed-point momentum and mass accumulator, one triple per grid cell. */
    private var gridBuffer = 0
    private var gridCells = 0

    /** Particles emitted since the last reset; useful for the HUD. */
    var emitted = 0L; private set

    /** True once this medium has been used, so an unused pool costs nothing. */
    val inUse get() = emitted > 0L

    /**
     * How much of the pool can hold a particle. Until the ring buffer wraps,
     * everything past the write head is empty, and updating or drawing it is
     * pure waste -- this was walking all 120k slots from the first stroke.
     */
    private fun liveSpan(): Int =
        if (emitted >= capacity) capacity else head.coerceAtLeast(1)

    fun init() {
        pEmit = ComputeProgram(ctx, "shaders/flip_emit.comp")
        pClearGrid = ComputeProgram(ctx, "shaders/flip_clear_grid.comp")
        pP2G = ComputeProgram(ctx, "shaders/flip_p2g.comp")
        pNormalize = ComputeProgram(ctx, "shaders/flip_normalize.comp")
        pG2P = ComputeProgram(ctx, "shaders/flip_g2p.comp")
        drawProgram = GLUtil.link(
            GLUtil.compile(GLES31.GL_VERTEX_SHADER, GLUtil.readAsset(ctx, "shaders/particle.vert"), "particle.vert"),
            GLUtil.compile(GLES31.GL_FRAGMENT_SHADER, GLUtil.readAsset(ctx, "shaders/particle.frag"), "particle.frag")
        )

        val ids = IntArray(1)
        GLES31.glGenBuffers(1, ids, 0)
        buffer = ids[0]
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer)
        GLES31.glBufferData(
            GLES31.GL_SHADER_STORAGE_BUFFER,
            capacity * STRIDE, null, GLES31.GL_DYNAMIC_DRAW
        )
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)

        val vaos = IntArray(1)
        GLES31.glGenVertexArrays(1, vaos, 0)
        vao = vaos[0]
        GLES31.glBindVertexArray(vao)
        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, buffer)
        GLES31.glEnableVertexAttribArray(0)
        GLES31.glVertexAttribPointer(0, 4, GLES31.GL_FLOAT, false, STRIDE, 0)
        GLES31.glEnableVertexAttribArray(1)
        GLES31.glVertexAttribPointer(1, 4, GLES31.GL_FLOAT, false, STRIDE, 16)
        GLES31.glBindVertexArray(0)
        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, 0)

        clear()
    }

    fun clear() {
        if (buffer == 0) return
        val zeros = java.nio.ByteBuffer.allocateDirect(capacity * STRIDE)
            .order(java.nio.ByteOrder.nativeOrder())
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer)
        GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, capacity * STRIDE, zeros)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        head = 0
        emitted = 0
        seed = 1f
    }

    /** How many particles a dab of this radius emits, on the current grid. */
    fun countFor(radius: Float, aspect: Float): Int {
        // cells are square in world space, so one number describes both axes
        val cell = kotlin.math.sqrt(aspect.coerceIn(0.2f, 5f)) / gridRes.coerceAtLeast(1)
        val footprint = (Math.PI.toFloat() * radius * radius / (cell * cell))
            .coerceAtLeast(1f)
        return (particlesPerCell * footprint).toInt().coerceIn(4, 2048)
    }

    /** Set by the sim when the grid is reshaped; only [countFor] needs it. */
    var gridRes = 192; private set

    fun emit(u: Float, v: Float, du: Float, dv: Float, radius: Float,
             inkScale: Float = 1f, aspect: Float = 1f, perDab: Int = 32,
             axisX: Float = 1f, axisY: Float = 0f, minor: Float = 1f) {
        if (buffer == 0) return
        val count = perDab

        pEmit.use()
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffer)
        pEmit.set("uHead", head)
        pEmit.set("uCount", count)
        pEmit.set("uCapacity", capacity)
        pEmit.set("uPoint", u, v)
        pEmit.set("uVel", du, dv)
        pEmit.set("uRadius", radius)
        pEmit.set("uAspect", aspect)
        pEmit.set("uAxis", axisX, axisY)
        pEmit.set("uMinor", minor)
        pEmit.set("uInk", inkPerParticle * inkScale)
        pEmit.set("uJitterSeed", seed)
        GLES31.glDispatchCompute((count + 63) / 64, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

        head = (head + count) % capacity
        emitted += count
        seed = (seed + 13.37f) % 1000f
    }

    /** Sizes the accumulator to the grid; called when the canvas is allocated. */
    fun resizeGrid(w: Int, h: Int, res: Int = gridRes) {
        gridRes = res
        val cells = w * h
        if (cells == gridCells && gridBuffer != 0) return
        if (gridBuffer != 0) GLES31.glDeleteBuffers(1, intArrayOf(gridBuffer), 0)
        val ids = IntArray(1)
        GLES31.glGenBuffers(1, ids, 0)
        gridBuffer = ids[0]
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, gridBuffer)
        GLES31.glBufferData(
            // four ints per cell: momentum and weight for each component,
            // since a staggered grid samples them at different points
            GLES31.GL_SHADER_STORAGE_BUFFER, cells * 4 * 4, null, GLES31.GL_DYNAMIC_COPY
        )
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        gridCells = cells
    }

    private fun barrier() = GLES31.glMemoryBarrier(
        GLES31.GL_SHADER_STORAGE_BARRIER_BIT or
        GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or
        GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or
        GLES31.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT
    )

    /** Scatters particle momentum onto the grid and resolves it into a field. */
    fun particlesToGrid(velTarget: Tex, massTarget: Tex, w: Int, h: Int) {
        if (buffer == 0 || gridBuffer == 0) return

        pClearGrid.use()
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, gridBuffer)
        pClearGrid.set("uCells", gridCells)
        GLES31.glDispatchCompute((gridCells + 63) / 64, 1, 1)
        barrier()

        pP2G.use()
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffer)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, gridBuffer)
        pP2G.set("uCapacity", liveSpan())
        GLES31.glUniform2i(
            GLES31.glGetUniformLocation(pP2G.id, "uGrid"), w, h
        )
        GLES31.glDispatchCompute((liveSpan() + 63) / 64, 1, 1)
        barrier()

        pNormalize.use()
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, gridBuffer)
        GLES31.glUniform2i(
            GLES31.glGetUniformLocation(pNormalize.id, "uGrid"), w, h
        )
        velTarget.bindImage(0, GLES31.GL_WRITE_ONLY)
        massTarget.bindImage(1, GLES31.GL_WRITE_ONLY)
        GLES31.glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1)
        barrier()
    }

    /** Gathers the projected field back onto the particles and moves them. */
    fun gridToParticles(dt: Float, velNew: Tex, velOld: Tex, aspect: Float,
                        gridW: Int, gridH: Int) {
        if (buffer == 0) return
        pG2P.use()
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffer)
        pG2P.set("uDt", dt)
        pG2P.set("uCapacity", liveSpan())
        pG2P.set("uFlipRatio", flipRatio)
        pG2P.set("uDrag", particleDrag)
        pG2P.set("uAspect", aspect)
        pG2P.set("uSettleSpeed", settleSpeed)
        pG2P.set("uSettleMinAge", settleMinAge)
        pG2P.set("uCohesion", cohesion)
        pG2P.set("uMaxSpeed", maxSpeed)
        pG2P.set("uTexel", 1f / gridW, 1f / gridH)
        pG2P.set("uVelNew", 0)
        pG2P.set("uVelOld", 1)
        velNew.bindSampler(0)
        velOld.bindSampler(1)
        GLES31.glDispatchCompute((liveSpan() + 63) / 64, 1, 1)
        barrier()
    }

    /**
     * Draws particles in [state] into whatever framebuffer is bound, additively.
     * State 1 is live paint; state 2 is the frame a particle dries, which is
     * drawn once into the background and then retired.
     */
    fun draw(state: Float, target: Tex) {
        if (buffer == 0) return

        ScratchFbo.bind(target)
        GLES31.glViewport(0, 0, target.width, target.height)

        GLES31.glEnable(GLES31.GL_BLEND)
        GLES31.glBlendFunc(GLES31.GL_ONE, GLES31.GL_ONE)   // premultiplied, accumulate

        GLES31.glUseProgram(drawProgram)
        GLES31.glUniform1f(GLES31.glGetUniformLocation(drawProgram, "uPointSize"), pointSize)
        GLES31.glUniform1f(GLES31.glGetUniformLocation(drawProgram, "uWantState"), state)

        GLES31.glBindVertexArray(vao)
        GLES31.glDrawArrays(GLES31.GL_POINTS, 0, liveSpan())
        GLES31.glBindVertexArray(0)

        GLES31.glDisable(GLES31.GL_BLEND)
        ScratchFbo.unbind()
    }

    fun release() {
        if (gridBuffer != 0) GLES31.glDeleteBuffers(1, intArrayOf(gridBuffer), 0)
        gridBuffer = 0
        if (buffer != 0) GLES31.glDeleteBuffers(1, intArrayOf(buffer), 0)
        if (vao != 0) GLES31.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (drawProgram != 0) GLES31.glDeleteProgram(drawProgram)
        buffer = 0
    }

    fun bytes(): Long = capacity.toLong() * STRIDE + gridCells.toLong() * 16

    companion object {
        /** Two vec4 per particle: pos+vel, then ink/age/state/seed. */
        const val STRIDE = 32
    }
}
