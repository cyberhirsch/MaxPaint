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

    /**
     * Dabs per second poured while the finger is held still. Zero is the old
     * behaviour, where a still finger put down nothing; Wet Paint keeps it that
     * way, and Splatter is built on it.
     */
    var flowRate = 0f

    /**
     * Drift compensation strength, the reference solver's k (default 1): a
     * cell denser than the emission density is given artificial divergence so
     * the solve pushes the excess out. Without it FLIP loses volume -- the
     * particles slowly sink into each other and the paint thins.
     */
    var compression = 1f
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

    /**
     * Iterations of the particle-separation relaxation (pushParticlesApart in
     * the reference solver). Zero disables it. This is the anti-grain pass:
     * the grid cannot see sub-cell clumping, so without it particles pile
     * into speckle instead of spreading into a body of liquid.
     */
    var separationIters = 2

    private var buffer = 0
    private var vao = 0
    private var head = 0
    private var seed = 1f

    private lateinit var pEmit: ComputeProgram
    private lateinit var pClearGrid: ComputeProgram
    private lateinit var pP2G: ComputeProgram
    private lateinit var pNormalize: ComputeProgram
    private lateinit var pG2P: ComputeProgram
    private lateinit var pIntegrate: ComputeProgram
    private lateinit var pSolve: ComputeProgram
    private lateinit var pCopy: ComputeProgram
    private lateinit var pSepClear: ComputeProgram
    private lateinit var pSepBin: ComputeProgram
    private lateinit var pSepPush: ComputeProgram
    private var drawProgram = 0

    /** Spatial hash for the separation pass: 13 ints per cell (count + 12 ids). */
    private var sepBuffer = 0
    private var sepW = 0
    private var sepH = 0

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
        // init() runs whenever a GL context is (re)created. Names from the old
        // context are dead, but the guards below cannot tell a dead nonzero
        // name from a live one: after the app was backgrounded and the context
        // lost, resizeGrid() saw gridCells unchanged and a nonzero gridBuffer
        // and skipped recreation, so every frame then dispatched against a
        // deleted buffer -- which is what broke the solver on returning to the
        // app. Forget the old names outright; deleting them would be a no-op
        // in this context anyway. The counters reset too: the particles those
        // names held are gone, and inUse must not claim otherwise.
        buffer = 0
        vao = 0
        gridBuffer = 0
        gridCells = 0
        sepBuffer = 0
        sepW = 0
        sepH = 0
        head = 0
        emitted = 0L

        pEmit = ComputeProgram(ctx, "shaders/flip_emit.comp")
        pClearGrid = ComputeProgram(ctx, "shaders/flip_clear_grid.comp")
        pP2G = ComputeProgram(ctx, "shaders/flip_p2g.comp")
        pNormalize = ComputeProgram(ctx, "shaders/flip_normalize.comp")
        pG2P = ComputeProgram(ctx, "shaders/flip_g2p.comp")
        pIntegrate = ComputeProgram(ctx, "shaders/flip_integrate.comp")
        pSolve = ComputeProgram(ctx, "shaders/flip_solve.comp")
        pCopy = ComputeProgram(ctx, "shaders/flip_copy.comp")
        pSepClear = ComputeProgram(ctx, "shaders/flip_sep_clear.comp")
        pSepBin = ComputeProgram(ctx, "shaders/flip_sep_bin.comp")
        pSepPush = ComputeProgram(ctx, "shaders/flip_sep_push.comp")
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
            // six ints per cell: momentum and weight for each staggered
            // component, cell-centred density, and the cell type
            GLES31.GL_SHADER_STORAGE_BUFFER, cells * 6 * 4, null, GLES31.GL_DYNAMIC_COPY
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

    /**
     * The reference solver's pushParticlesApart: a relaxation that moves any
     * two live particles closer than the rest packing distance apart, half
     * each. Runs before the grid transfer so the grid sees paint that already
     * fills its footprint evenly instead of sub-cell clumps.
     *
     * The hash must be nearly as fine as the separation distance itself, or a
     * cell's twelve recorded slots see only a fraction of a crowd and the
     * push starves -- the reference's spacing is 2.2x the particle radius for
     * the same reason. Cells are 2x the rest distance (a handful of particles
     * each at rest), capped by a memory budget; the cap only binds at extreme
     * Density-times-Coupling settings, where separation degrades rather than
     * the allocation exploding.
     */
    fun pushApart(aspect: Float) {
        if (buffer == 0 || separationIters <= 0) return

        val a = aspect.coerceIn(0.2f, 5f)
        // Rest packing distance -- hex-packing particlesPerCell into one grid
        // cell -- times 0.7. Not 1.0: a minimum at the rest distance itself
        // would unpack paint sitting exactly at the density cohesion and the
        // solve's drift compensation both settle it to, and the passes would
        // fight forever -- the bead never rests, the surface never dries.
        // Below rest packing the push only acts on genuine clumps, which is
        // its whole job.
        val cell = kotlin.math.sqrt(a) / gridRes.coerceAtLeast(1)
        var minDist = 0.75f * cell / kotlin.math.sqrt(particlesPerCell.coerceAtLeast(1f))
        var spacing = 2f * minDist
        val budget = 600_000f
        if (a / (spacing * spacing) > budget) spacing = kotlin.math.sqrt(a / budget)
        // the push only searches 3x3 hash cells, so it cannot honour a
        // separation wider than one of them
        minDist = minDist.coerceAtMost(0.95f * spacing)

        val w = kotlin.math.ceil(a / spacing).toInt().coerceAtLeast(8)
        val h = kotlin.math.ceil(1f / spacing).toInt().coerceAtLeast(8)
        if (sepBuffer == 0 || w != sepW || h != sepH) {
            if (sepBuffer != 0) GLES31.glDeleteBuffers(1, intArrayOf(sepBuffer), 0)
            sepW = w
            sepH = h
            val ids = IntArray(1)
            GLES31.glGenBuffers(1, ids, 0)
            sepBuffer = ids[0]
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, sepBuffer)
            GLES31.glBufferData(
                GLES31.GL_SHADER_STORAGE_BUFFER,
                sepW * sepH * 13 * 4, null, GLES31.GL_DYNAMIC_COPY
            )
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        }

        val span = liveSpan()
        val cells = sepW * sepH
        for (iter in 0 until separationIters) {
            pSepClear.use()
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, sepBuffer)
            pSepClear.set("uCells", cells)
            GLES31.glDispatchCompute((cells + 63) / 64, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

            pSepBin.use()
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffer)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, sepBuffer)
            pSepBin.set("uCapacity", span)
            pSepBin.set("uAspect", a)
            pSepBin.set("uSpacing", spacing)
            GLES31.glUniform2i(GLES31.glGetUniformLocation(pSepBin.id, "uSep"), sepW, sepH)
            GLES31.glDispatchCompute((span + 63) / 64, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

            pSepPush.use()
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffer)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, sepBuffer)
            pSepPush.set("uCapacity", span)
            pSepPush.set("uAspect", a)
            pSepPush.set("uSpacing", spacing)
            pSepPush.set("uMinDist", minDist)
            GLES31.glUniform2i(GLES31.glGetUniformLocation(pSepPush.id, "uSep"), sepW, sepH)
            GLES31.glDispatchCompute((span + 63) / 64, 1, 1)
            barrier()
        }
    }

    /** integrateParticles + wall collisions: move on last frame's solve. */
    fun integrate(dt: Float, aspect: Float) {
        if (buffer == 0) return
        pIntegrate.use()
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffer)
        pIntegrate.set("uDt", dt)
        pIntegrate.set("uCapacity", liveSpan())
        pIntegrate.set("uAspect", aspect)
        GLES31.glDispatchCompute((liveSpan() + 63) / 64, 1, 1)
        barrier()
    }

    /** Scatters particle momentum and density onto the grid and marks fluid
     *  cells; the accumulator is resolved into the u/v/density fields. */
    fun particlesToGrid(texU: Tex, texV: Tex, density: Tex, w: Int, h: Int) {
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
        texU.bindImage(0, GLES31.GL_WRITE_ONLY)
        texV.bindImage(2, GLES31.GL_WRITE_ONLY)
        density.bindImage(3, GLES31.GL_WRITE_ONLY)
        GLES31.glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1)
        barrier()
    }

    /** FLIP transfers the solve's CHANGE, so the pre-solve field is kept. */
    fun snapshot(texU: Tex, texUOld: Tex, texV: Tex, texVOld: Tex, w: Int, h: Int) {
        pCopy.use()
        texU.bindImage(0, GLES31.GL_READ_ONLY)
        texUOld.bindImage(1, GLES31.GL_WRITE_ONLY)
        texV.bindImage(2, GLES31.GL_READ_ONLY)
        texVOld.bindImage(3, GLES31.GL_WRITE_ONLY)
        GLES31.glUniform2i(GLES31.glGetUniformLocation(pCopy.id, "uGrid"), w, h)
        GLES31.glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1)
        barrier()
    }

    /**
     * The reference's solveIncompressibility: red-black relaxation directly
     * on the face velocities of fluid cells, with drift compensation.
     */
    fun solve(iterations: Int, omega: Float, texU: Tex, texV: Tex,
              w: Int, h: Int) {
        if (gridBuffer == 0) return
        pSolve.use()
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, gridBuffer)
        GLES31.glUniform2i(GLES31.glGetUniformLocation(pSolve.id, "uGrid"), w, h)
        pSolve.set("uOmega", omega)
        pSolve.set("uRest", particlesPerCell)
        pSolve.set("uCompensate", compression)
        texU.bindImage(0, GLES31.GL_READ_WRITE)
        texV.bindImage(2, GLES31.GL_READ_WRITE)
        val half = (w + 1) / 2
        for (i in 0 until iterations) {
            for (parity in 0..1) {
                pSolve.set("uParity", parity)
                GLES31.glDispatchCompute((half + 7) / 8, (h + 7) / 8, 1)
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
            }
        }
        barrier()
    }

    /** Gathers the solved field back onto the particles: the FLIP blend, then
     *  MaxPaint's drag, cohesion, CFL clamp and settling. Positions move in
     *  [integrate], next frame. */
    fun gridToParticles(dt: Float, texU: Tex, texV: Tex,
                        texUOld: Tex, texVOld: Tex, density: Tex,
                        gridW: Int, gridH: Int) {
        if (buffer == 0 || gridBuffer == 0) return
        pG2P.use()
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffer)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, gridBuffer)
        pG2P.set("uDt", dt)
        pG2P.set("uCapacity", liveSpan())
        GLES31.glUniform2i(
            GLES31.glGetUniformLocation(pG2P.id, "uGrid"), gridW, gridH
        )
        pG2P.set("uFlipRatio", flipRatio)
        pG2P.set("uDrag", particleDrag)
        pG2P.set("uSettleSpeed", settleSpeed)
        pG2P.set("uSettleMinAge", settleMinAge)
        // The slider keeps its 0..200 numbers; what they mean is the speed
        // the surface may creep, in world units/s -- 200 is 0.5, an eighth of
        // the CFL cap. Rest mass tracks the emission density -- each particle
        // deposits total density weight one, so a cell at rest holds
        // particlesPerCell -- and the surface gate does not shift when
        // Density changes.
        pG2P.set("uCohesionSpeed", cohesion * 0.0025f)
        pG2P.set("uRestMass", particlesPerCell)
        pG2P.set("uMaxSpeed", maxSpeed)
        pG2P.set("uTexel", 1f / gridW, 1f / gridH)
        pG2P.set("uUNew", 0)
        pG2P.set("uVNew", 1)
        pG2P.set("uUOld", 2)
        pG2P.set("uVOld", 3)
        pG2P.set("uDensity", 4)
        texU.bindSampler(0)
        texV.bindSampler(1)
        texUOld.bindSampler(2)
        texVOld.bindSampler(3)
        density.bindSampler(4)
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
        if (sepBuffer != 0) GLES31.glDeleteBuffers(1, intArrayOf(sepBuffer), 0)
        sepBuffer = 0
        if (buffer != 0) GLES31.glDeleteBuffers(1, intArrayOf(buffer), 0)
        if (vao != 0) GLES31.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (drawProgram != 0) GLES31.glDeleteProgram(drawProgram)
        buffer = 0
    }

    fun bytes(): Long = capacity.toLong() * STRIDE + gridCells.toLong() * 24 +
        sepW.toLong() * sepH * 13 * 4

    companion object {
        /** Two vec4 per particle: pos+vel, then ink/age/state/seed. */
        const val STRIDE = 32
    }
}
