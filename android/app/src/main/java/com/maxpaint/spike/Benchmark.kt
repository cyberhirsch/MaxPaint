package com.maxpaint.spike

import android.opengl.GLES31
import kotlin.math.PI
import kotlin.math.sin

/**
 * Sweeps the selectable resolutions, running an identical scripted stroke at each,
 * and reports honest GPU-side timings alongside solver quality. This is the
 * instrument for sizing a device:
 * it answers "how high can this device actually go?" rather than guessing.
 *
 * Timing uses glFinish around the sim step. That serialises the pipeline, so the
 * numbers are a touch pessimistic versus real pipelined rendering — but they are
 * a true measure of sim cost, which is what we are trying to size.
 */
class Benchmark(
    private val sim: FluidSim,
    private val resolutions: IntArray = FluidSim.RESOLUTIONS,
    private val dyeScale: Int = 1,
    private val warmupFrames: Int = 20,
    private val measureFrames: Int = 90,
    private val refreshRate: Float = 60f
) {
    data class Result(
        val simRes: Int,
        val dyeRes: Int,
        val simW: Int,
        val simH: Int,
        val medianMs: Double,
        val p95Ms: Double,
        val meanMs: Double,
        val vramMb: Double,
        /** Fraction of divergence one cold solve removes, at this sweep count. */
        val convergence: Double,
        /** Fractional change in total ink across the measured run. */
        val inkDrift: Double,
        val refreshRate: Float
    ) {
        val estimatedFps get() = if (medianMs > 0) 1000.0 / medianMs else 0.0
        val frameTimeMs get() = 1000.0 / refreshRate
        val fastEnough get() = medianMs <= frameTimeMs && p95Ms <= frameTimeMs * 1.2
        /** Converged enough that strokes neither smear nor visibly bloom. */
        val solvedEnough get() = convergence >= 0.5 && kotlin.math.abs(inkDrift) <= 0.15
        val usable get() = fastEnough && solvedEnough
    }

    var results: List<Result> = emptyList(); private set

    /** Blocking; call on the GL thread. Takes a few seconds. */
    fun run(): List<Result> {
        val out = ArrayList<Result>()
        val dt = 1f / 60f

        for (res in resolutions) {
            sim.allocate(res, dyeScale)
            sim.clear()

            repeat(warmupFrames) { i -> scriptedFrame(i, dt) }
            GLES31.glFinish()

            // Quality, measured on the same state the timing run will use.
            // Frame time alone is a misleading PASS: an under-solved field is
            // both mushy and ink-generating however fast it runs.
            val convergence = sim.measureConvergence().toDouble()
            val inkBefore = sim.measure().ink.toDouble()

            val samples = DoubleArray(measureFrames)
            for (i in 0 until measureFrames) {
                val t0 = System.nanoTime()
                scriptedFrame(warmupFrames + i, dt)
                GLES31.glFinish()
                samples[i] = (System.nanoTime() - t0) / 1_000_000.0
            }
            val inkAfter = sim.measure().ink.toDouble()
            samples.sort()

            out.add(
                Result(
                    simRes = res,
                    dyeRes = res * dyeScale,
                    simW = sim.simW,
                    simH = sim.simH,
                    medianMs = samples[samples.size / 2],
                    p95Ms = samples[(samples.size * 95 / 100).coerceAtMost(samples.size - 1)],
                    meanMs = samples.average(),
                    vramMb = sim.vramBytes() / (1024.0 * 1024.0),
                    convergence = convergence,
                    inkDrift = if (inkBefore > 1e-6) (inkAfter - inkBefore) / inkBefore else 0.0,
                    refreshRate = refreshRate
                )
            )
        }
        results = out
        return out
    }

    /** A deterministic figure-eight stroke, so every resolution does the same work. */
    private fun scriptedFrame(frame: Int, dt: Float) {
        val t = frame * dt
        val u = 0.5f + 0.28f * sin(2.0 * PI * 0.35 * t).toFloat()
        val v = 0.5f + 0.28f * sin(2.0 * PI * 0.70 * t).toFloat()
        val du = 0.9f * sin(2.0 * PI * 0.35 * t + 1.2).toFloat()
        val dv = 0.9f * sin(2.0 * PI * 0.70 * t + 0.4).toFloat()
        sim.splat(u, v, du, dv, 0.6f, 0.25f, 0.9f)
        sim.step(dt)
    }

    fun report(deviceLine: String): String = buildString {
        appendLine("MaxPaint — resolution headroom sweep")
        appendLine(deviceLine)
        val refreshRateStr = if (refreshRate == refreshRate.toInt().toFloat()) refreshRate.toInt() else refreshRate
        appendLine("solver=${if (sim.useRedBlack) "RB-GS" else "Jacobi"}  iters=${sim.pressureIterations}  " +
                "dyeScale=${dyeScale}x  ${measureFrames} frames each  @ ${refreshRateStr}Hz")
        appendLine()
        appendLine("  grid       median     p95   est.fps    vram   solved   ink    verdict")
        appendLine("  ---------------------------------------------------------------------------")
        var lastW = -1
        var lastH = -1
        results.forEach { r ->
            if (r.simW == lastW && r.simH == lastH) return@forEach
            lastW = r.simW
            lastH = r.simH
            appendLine(
                String.format(
                    "  %-4d×%-4d %6.2fms %6.2fms %6.1f %6.1fMB %6.0f%% %+5.0f%%   %s",
                    r.simW, r.simH,
                    r.medianMs, r.p95Ms, r.estimatedFps, r.vramMb,
                    r.convergence * 100, r.inkDrift * 100,
                    when {
                        r.usable -> "USABLE"
                        r.fastEnough -> "under-solved"
                        else -> "too slow"
                    }
                )
            )
        }
        appendLine()
        appendLine("  solved  = divergence removed by one cold solve at this sweep count")
        appendLine("  ink     = change in total ink over the run; a positive number means")
        appendLine("            strokes are gaining mass and will bloom on their own")
        appendLine()

        val fastest = results.lastOrNull { it.fastEnough }
        val best = results.lastOrNull { it.usable }
        appendLine(
            if (best != null)
                "Highest usable resolution: ${best.simRes}² — " +
                "${best.medianMs.format()}ms, ${(best.convergence * 100).toInt()}% solved"
            else
                "No tested resolution is both fast and adequately solved."
        )
        if (fastest != null && (best == null || fastest.simRes > best.simRes)) {
            appendLine(
                "Note: ${fastest.simRes}² holds 60fps but is only " +
                "${(fastest.convergence * 100).toInt()}% solved — it will look worse " +
                "than the resolution above despite running fast."
            )
        }
    }

    private fun Double.format() = String.format("%.2f", this)
}

/**
 * How many particles this device can carry at 60fps.
 *
 * The answer is hardware-specific and cannot be reasoned out from a desk: the
 * cost is dominated by the atomic scatter in the particle-to-grid pass and by
 * fill rate in the draw, and both vary by an order of magnitude across mobile
 * GPUs. So measure it.
 */
class ParticleBenchmark(
    private val sim: FluidSim,
    private val counts: IntArray = intArrayOf(25_000, 50_000, 100_000, 200_000, 400_000),
    private val warmupFrames: Int = 15,
    private val measureFrames: Int = 45,
    private val refreshRate: Float = 60f
) {
    data class Row(val count: Int, val medianMs: Double, val p95Ms: Double, val refreshRate: Float) {
        val fps get() = if (medianMs > 0) 1000.0 / medianMs else 0.0
        val frameTimeMs get() = 1000.0 / refreshRate
        val holds60 get() = medianMs <= frameTimeMs && p95Ms <= frameTimeMs * 1.2
    }

    var rows: List<Row> = emptyList(); private set

    /** Blocking; call on the GL thread. */
    fun run(): List<Row> {
        val out = ArrayList<Row>()
        val dt = 1f / 60f
        val previousBrush = sim.brush
        sim.brush = Brush.FLIP

        for (target in counts) {
            if (target > sim.flip.capacity) continue

            sim.clear()
            // fill the pool in one burst, spread over the canvas
            var placed = 0
            var i = 0
            while (placed < target) {
                val t = i * 0.013f
                val u = 0.5f + 0.35f * kotlin.math.sin(t * 2.3f)
                val v = 0.5f + 0.35f * kotlin.math.sin(t * 1.7f)
                val n = sim.flip.countFor(sim.splatRadius, sim.canvasAspect)
                sim.flip.emit(u, v, 0.4f, 0.2f, sim.splatRadius, 1f,
                              sim.canvasAspect, perDab = n)
                placed += n
                i++
            }

            repeat(warmupFrames) { sim.step(dt) }
            GLES31.glFinish()

            val samples = DoubleArray(measureFrames)
            for (f in 0 until measureFrames) {
                val t0 = System.nanoTime()
                sim.step(dt)
                GLES31.glFinish()
                samples[f] = (System.nanoTime() - t0) / 1_000_000.0
            }
            samples.sort()
            out.add(
                Row(
                    count = placed,
                    medianMs = samples[samples.size / 2],
                    p95Ms = samples[(samples.size * 95 / 100).coerceAtMost(samples.size - 1)],
                    refreshRate = refreshRate
                )
            )
        }

        sim.brush = previousBrush
        sim.clear()
        rows = out
        return out
    }

    fun report(deviceLine: String): String = buildString {
        appendLine("MaxPaint — how many particles fit")
        appendLine(deviceLine)
        appendLine("grid ${sim.simW}x${sim.simH}, ${measureFrames} frames each")
        appendLine()
        appendLine("  particles    median      p95     fps    60fps")
        appendLine("  ------------------------------------------------")
        rows.forEach { r ->
            appendLine(
                String.format(
                    "  %9d %8.2fms %8.2fms %6.1f    %s",
                    r.count, r.medianMs, r.p95Ms, r.fps,
                    if (r.holds60) "yes" else "no"
                )
            )
        }
        appendLine()
        val best = rows.lastOrNull { it.holds60 }
        appendLine(
            if (best != null)
                "Most particles holding 60fps: ${best.count} " +
                "(${String.format("%.2f", best.medianMs)}ms median)"
            else
                "No tested count holds 60fps on this device."
        )
        appendLine()
        appendLine("Cost is dominated by the atomic scatter in particle-to-grid")
        appendLine("and by fill rate in the draw, so smaller particles help.")
    }
}
