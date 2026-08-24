package com.maxpaint.spike

import android.opengl.GLES31
import kotlin.math.PI
import kotlin.math.sin

/**
 * Sweeps the selectable resolutions, running an identical scripted stroke at each,
 * and reports honest GPU-side timings. This is the M0 exit-criteria instrument:
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
    private val measureFrames: Int = 90
) {
    data class Result(
        val simRes: Int,
        val dyeRes: Int,
        val medianMs: Double,
        val p95Ms: Double,
        val meanMs: Double,
        val vramMb: Double,
        /** Fraction of divergence one cold solve removes, at this sweep count. */
        val convergence: Double,
        /** Fractional change in total ink across the measured run. */
        val inkDrift: Double
    ) {
        val estimatedFps get() = if (medianMs > 0) 1000.0 / medianMs else 0.0
        val fastEnough get() = medianMs <= 16.6 && p95Ms <= 20.0
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
                    medianMs = samples[samples.size / 2],
                    p95Ms = samples[(samples.size * 95 / 100).coerceAtMost(samples.size - 1)],
                    meanMs = samples.average(),
                    vramMb = sim.vramBytes() / (1024.0 * 1024.0),
                    convergence = convergence,
                    inkDrift = if (inkBefore > 1e-6) (inkAfter - inkBefore) / inkBefore else 0.0
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
        appendLine("MaxPaint M0 — resolution headroom sweep")
        appendLine(deviceLine)
        appendLine("solver=${if (sim.useRedBlack) "RB-GS" else "Jacobi"}  iters=${sim.pressureIterations}  " +
                "dyeScale=${dyeScale}x  ${measureFrames} frames each")
        appendLine()
        appendLine("  sim     dye    median     p95   est.fps    vram   solved   ink    verdict")
        appendLine("  ---------------------------------------------------------------------------")
        results.forEach { r ->
            appendLine(
                String.format(
                    "  %-6s %-6s %6.2fms %6.2fms %6.1f %6.1fMB %6.0f%% %+5.0f%%   %s",
                    "${r.simRes}²", "${r.dyeRes}²",
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
