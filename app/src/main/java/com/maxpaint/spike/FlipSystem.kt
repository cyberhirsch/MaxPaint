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
 */
class FlipSystem(private val ctx: Context, val capacity: Int = 120_000) {

    var flipRatio = 0.92f       // 1 = splashy and particle-driven, 0 = viscous
    var gravityX = 0f
    var gravityY = -0.55f
    var particleDrag = 0.25f
    var settleSpeed = 0.06f
    var settleMinAge = 0.25f
    var pointSize = 5f
    var inkPerParticle = 0.14f
    var emitPerSample = 12

    private var buffer = 0
    private var vao = 0
    private var head = 0
    private var seed = 1f

    private lateinit var pEmit: ComputeProgram
    private lateinit var pUpdate: ComputeProgram
    private var drawProgram = 0

    /** Particles emitted since the last reset; useful for the HUD. */
    var emitted = 0L; private set

    fun init() {
        pEmit = ComputeProgram(ctx, "shaders/flip_emit.comp")
        pUpdate = ComputeProgram(ctx, "shaders/flip_update.comp")
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

    fun emit(u: Float, v: Float, du: Float, dv: Float, radius: Float, inkScale: Float = 1f) {
        if (buffer == 0) return
        val count = emitPerSample

        pEmit.use()
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffer)
        pEmit.set("uHead", head)
        pEmit.set("uCount", count)
        pEmit.set("uCapacity", capacity)
        pEmit.set("uPoint", u, v)
        pEmit.set("uVel", du, dv)
        pEmit.set("uRadius", radius)
        pEmit.set("uInk", inkPerParticle * inkScale)
        pEmit.set("uJitterSeed", seed)
        GLES31.glDispatchCompute((count + 63) / 64, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

        head = (head + count) % capacity
        emitted += count
        seed = (seed + 13.37f) % 1000f
    }

    fun step(dt: Float, velocityTexture: Tex) {
        if (buffer == 0) return
        pUpdate.use()
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffer)
        pUpdate.set("uDt", dt)
        pUpdate.set("uCapacity", capacity)
        pUpdate.set("uFlipRatio", flipRatio)
        pUpdate.set("uGravity", gravityX, gravityY)
        pUpdate.set("uSettleSpeed", settleSpeed)
        pUpdate.set("uSettleMinAge", settleMinAge)
        pUpdate.set("uDrag", particleDrag)
        pUpdate.set("uVel", 0)
        velocityTexture.bindSampler(0)
        GLES31.glDispatchCompute((capacity + 63) / 64, 1, 1)
        GLES31.glMemoryBarrier(
            GLES31.GL_SHADER_STORAGE_BARRIER_BIT or GLES31.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT
        )
    }

    /**
     * Draws particles in [state] into whatever framebuffer is bound, additively.
     * State 1 is live paint; state 2 is the frame a particle dries, which is
     * drawn once into the background and then retired.
     */
    fun draw(state: Float, target: Tex) {
        if (buffer == 0) return

        val fbo = IntArray(1)
        GLES31.glGenFramebuffers(1, fbo, 0)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, fbo[0])
        GLES31.glFramebufferTexture2D(
            GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0,
            GLES31.GL_TEXTURE_2D, target.id, 0
        )
        GLES31.glViewport(0, 0, target.width, target.height)

        GLES31.glEnable(GLES31.GL_BLEND)
        GLES31.glBlendFunc(GLES31.GL_ONE, GLES31.GL_ONE)   // premultiplied, accumulate

        GLES31.glUseProgram(drawProgram)
        GLES31.glUniform1f(GLES31.glGetUniformLocation(drawProgram, "uPointSize"), pointSize)
        GLES31.glUniform1f(GLES31.glGetUniformLocation(drawProgram, "uWantState"), state)

        GLES31.glBindVertexArray(vao)
        GLES31.glDrawArrays(GLES31.GL_POINTS, 0, capacity)
        GLES31.glBindVertexArray(0)

        GLES31.glDisable(GLES31.GL_BLEND)
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
        GLES31.glDeleteFramebuffers(1, fbo, 0)
    }

    fun release() {
        if (buffer != 0) GLES31.glDeleteBuffers(1, intArrayOf(buffer), 0)
        if (vao != 0) GLES31.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (drawProgram != 0) GLES31.glDeleteProgram(drawProgram)
        buffer = 0
    }

    fun bytes(): Long = capacity.toLong() * STRIDE

    companion object {
        /** Two vec4 per particle: pos+vel, then ink/age/state/seed. */
        const val STRIDE = 32
    }
}
