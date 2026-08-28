package com.maxpaint.spike

/**
 * A brush is a medium — which solver it drives and how it deposits paint —
 * plus parameters over that medium. Media are built in; presets are parameters.
 *
 * The force-only brushes (Vortex, Solvent) carry no pigment. They exist to
 * reshape paint that is already on the canvas, which is what turns marbling
 * and suminagashi into a workflow rather than an accident.
 */
enum class Brush(val label: String, val carriesPigment: Boolean, val short: String) {
    /** Eulerian gas: the hero brush. Injects dye and momentum. */
    GAS("Gas", true, "gas"),

    /**
     * A hard-edged pen. Writes to its own field so the fluid never smears it,
     * and creeps into the paper by capillary action, so holding still blooms.
     */
    NIB("Nib", true, "nib"),

    /** FLIP particles: paint that pours, drips and splatters. */
    FLIP("Flip", true, "drip"),

    /** Shallow-water pigment on paper: bleeds, blooms, darkens at the edges. */
    WATERCOLOR("Water", true, "wash"),

    /** Force only. Stirs and combs live paint. */
    VORTEX("Vortex", false, "stir"),

    /** Lifts pigment and pushes it outward — the alcohol-drop halo. */
    SOLVENT("Solvent", false, "lift"),

    /**
     * Drags pigment already on the paper, the way a finger moves charcoal
     * dust. Deposits nothing; it warps what is set rather than pushing fluid.
     */
    SMEAR("Smear", false, "smudge"),

    /** Local Freeze Now: bakes only what it touches. */
    FREEZE("Freeze", false, "set"),

    /** The inverse: lifts baked paint back into the simulation. */
    THAW("Thaw", false, "melt"),

    /**
     * Diagnostic. Draws the contact patch the digitiser reported, exactly as
     * the paint brushes are told to use it, straight onto the permanent layer.
     */
    PROBE("Probe", false, "probe");

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
