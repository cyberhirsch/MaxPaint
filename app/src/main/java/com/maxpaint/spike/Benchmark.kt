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
        val vramMb: Double
    ) {
        val estimatedFps get() = if (medianMs > 0) 1000.0 / medianMs else 0.0
        val holds60 get() = medianMs <= 16.6 && p95Ms <= 20.0
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

            val samples = DoubleArray(measureFrames)
            for (i in 0 until measureFrames) {
                val t0 = System.nanoTime()
                scriptedFrame(warmupFrames + i, dt)
                GLES31.glFinish()
                samples[i] = (System.nanoTime() - t0) / 1_000_000.0
            }
            samples.sort()

            out.add(
                Result(
                    simRes = res,
                    dyeRes = res * dyeScale,
                    medianMs = samples[samples.size / 2],
                    p95Ms = samples[(samples.size * 95 / 100).coerceAtMost(samples.size - 1)],
                    meanMs = samples.average(),
                    vramMb = sim.vramBytes() / (1024.0 * 1024.0)
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
        appendLine("  sim     dye    median     p95     est.fps   vram    60fps")
        appendLine("  ----------------------------------------------------------")
        results.forEach { r ->
            appendLine(
                String.format(
                    "  %-6s %-6s %6.2fms %6.2fms %7.1f %7.1fMB   %s",
                    "${r.simRes}²", "${r.dyeRes}²",
                    r.medianMs, r.p95Ms, r.estimatedFps, r.vramMb,
                    if (r.holds60) "PASS" else "fail"
                )
            )
        }
        appendLine()
        val best = results.lastOrNull { it.holds60 }
        appendLine(
            if (best != null)
                "Highest resolution holding 60fps: ${best.simRes}² (${best.medianMs.format()}ms median)"
            else
                "No tested resolution holds 60fps. Lower pressure iterations and re-run."
        )
    }

    private fun Double.format() = String.format("%.2f", this)
}
