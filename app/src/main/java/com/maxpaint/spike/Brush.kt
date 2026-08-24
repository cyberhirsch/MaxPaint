package com.maxpaint.spike

/**
 * A brush is a medium — which solver it drives and how it deposits paint —
 * plus parameters over that medium. Media are built in; presets are parameters.
 *
 * The force-only brushes (Vortex, Solvent) carry no pigment. They exist to
 * reshape paint that is already on the canvas, which is what turns marbling
 * and suminagashi into a workflow rather than an accident.
 */
enum class Brush(val label: String, val carriesPigment: Boolean) {
    /** Eulerian gas: the hero brush. Injects dye and momentum. */
    GAS("Gas", true),

    /** FLIP particles: paint that pours, drips and splatters. */
    FLIP("Flip", true),

    /** Shallow-water pigment on paper: bleeds, blooms, darkens at the edges. */
    WATERCOLOR("Water", true),

    /** Force only. Stirs and combs live paint. */
    VORTEX("Vortex", false),

    /** Lifts pigment and pushes it outward — the alcohol-drop halo. */
    SOLVENT("Solvent", false),

    /** Local Freeze Now: bakes only what it touches. */
    FREEZE("Freeze", false),

    /** The inverse: lifts baked paint back into the simulation. */
    THAW("Thaw", false);

    companion object {
        val labels: List<String> get() = entries.map { it.label }
    }
}

/** Sub-modes for the vortex/rake brush, matching force.comp. */
enum class ForceMode(val label: String, val code: Int) {
    SWIRL("Swirl", 0),
    PUSH("Push", 1),
    PINCH("Pinch", 2),
    COMB("Comb", 3);

    companion object {
        val labels: List<String> get() = entries.map { it.label }
    }
}
