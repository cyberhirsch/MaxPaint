package com.maxpaint.spike

/**
 * A preset is a named set of parameters over a medium. Media are built in;
 * presets are how a medium becomes several distinct-feeling brushes without
 * asking the artist to reason about vorticity or adsorption rates.
 */
class Preset(val label: String, val apply: (FluidSim) -> Unit)

object Presets {

    private val gas = listOf(
        Preset("Smoke") { s ->
            s.vorticity = 22f; s.dyeDissipation = 0.05f
            s.splatRadius = 0.02f; s.velocityGain = 1.0f
        },
        Preset("Ink Drop") { s ->
            s.vorticity = 8f; s.dyeDissipation = 0.0f
            s.splatRadius = 0.035f; s.velocityGain = 0.4f
        },
        Preset("Nebula") { s ->
            s.vorticity = 38f; s.dyeDissipation = 0.02f
            s.splatRadius = 0.05f; s.velocityGain = 1.6f
        },
        Preset("Aurora") { s ->
            s.vorticity = 30f; s.dyeDissipation = 0.08f
            s.splatRadius = 0.03f; s.velocityGain = 2.2f
        },
        Preset("Steam") { s ->
            s.vorticity = 14f; s.dyeDissipation = 0.35f
            s.splatRadius = 0.045f; s.velocityGain = 0.9f
        }
    )

    private val nib = listOf(
        Preset("Fine") { s ->
            s.nibRadius = 0.005f; s.nibHardness = 0.94f
            s.nibSoak = 0.7f; s.nibDry = 0.9f; s.nibGrain = 0.5f
        },
        Preset("Broad") { s ->
            s.nibRadius = 0.014f; s.nibHardness = 0.9f
            s.nibSoak = 0.8f; s.nibDry = 0.8f; s.nibGrain = 0.5f
        },
        Preset("Bleed") { s ->
            // creeps far and dries slowly, so holding still blooms visibly
            s.nibRadius = 0.007f; s.nibHardness = 0.92f
            s.nibSoak = 3.2f; s.nibDry = 0.25f; s.nibGrain = 0.8f
        },
        Preset("Dry Pen") { s ->
            s.nibRadius = 0.004f; s.nibHardness = 0.99f
            s.nibSoak = 0.15f; s.nibDry = 2.5f; s.nibGrain = 0.85f
        }
    )

    // No gravity: these differ by how far the stroke's momentum carries the
    // paint and how abruptly drag stops it.
    private val flip = listOf(
        // Cohesion 30 is not a feel change: measured to reproduce what the old
        // force did at its setting of 1, which the saturation bug made far
        // stronger than the number suggested -- 80.7% of a thin film condensed
        // against 79.5% under the old shader, 206 cells against 203. The look
        // the user signed off on, minus the energy source underneath it.
        Preset("Wet Paint") { s ->
            s.flip.flowRate = 40f; s.flip.compression = 1f
            s.flip.flipRatio = 0.6f; s.flip.particleDrag = 0.25f
            s.flip.settleSpeed = 0.06f; s.flip.cohesion = 30f
            s.flip.pointSize = 3f; s.flip.particlesPerCell = 120f
        },
        // Hold and it pours into a volume; drag and that volume's own momentum
        // throws it. The splash is the gesture, not the emitter.
        Preset("Splatter") { s ->
            s.flip.flipRatio = 0.99f; s.flip.particleDrag = 0.02f
            s.flip.settleSpeed = 0.10f; s.flip.cohesion = 6f
            s.flip.pointSize = 3f; s.flip.particlesPerCell = 51f
            s.flip.flowRate = 12f; s.flip.compression = 1f
        },
        Preset("Fling") { s ->
            s.flip.flowRate = 24f; s.flip.compression = 1f
            // travels a long way before it stops
            s.flip.flipRatio = 0.97f; s.flip.particleDrag = 0.05f
            s.flip.settleSpeed = 0.03f; s.flip.cohesion = 8f
            s.flip.pointSize = 4f; s.flip.particlesPerCell = 29f
        },
        Preset("Honey") { s ->
            s.flip.flowRate = 10f; s.flip.compression = 1f
            s.flip.flipRatio = 0.45f; s.flip.particleDrag = 1.6f
            s.flip.settleSpeed = 0.02f; s.flip.cohesion = 26f
            s.flip.pointSize = 6f; s.flip.particlesPerCell = 51f
        },
        Preset("Mercury") { s ->
            s.flip.flowRate = 24f; s.flip.compression = 1f
            s.flip.flipRatio = 0.97f; s.flip.particleDrag = 0.04f
            // very high tension: beads up hard and stays whole
            s.flip.settleSpeed = 0.015f; s.flip.cohesion = 38f
            s.flip.pointSize = 5f; s.flip.particlesPerCell = 61f
        }
    )

    private val watercolor = listOf(
        Preset("Wash") { s ->
            s.wcFlow = 6f; s.wcEvaporate = 0.22f; s.wcAdsorb = 0.12f
            s.wcEdge = 6f; s.wcGrain = 0.35f
            s.wcLoadWater = 0.55f; s.wcLoadPigment = 0.30f
        },
        Preset("Wet-on-Wet") { s ->
            s.wcFlow = 11f; s.wcEvaporate = 0.10f; s.wcAdsorb = 0.05f
            s.wcEdge = 3f; s.wcGrain = 0.2f
            s.wcLoadWater = 0.9f; s.wcLoadPigment = 0.22f
        },
        Preset("Dry Brush") { s ->
            s.wcFlow = 2f; s.wcEvaporate = 0.8f; s.wcAdsorb = 0.9f
            s.wcEdge = 2f; s.wcGrain = 0.85f
            s.wcLoadWater = 0.12f; s.wcLoadPigment = 0.42f
        },
        Preset("Bloom") { s ->
            s.wcFlow = 16f; s.wcEvaporate = 0.14f; s.wcAdsorb = 0.04f
            s.wcEdge = 11f; s.wcGrain = 0.25f
            s.wcLoadWater = 1.1f; s.wcLoadPigment = 0.18f
        },
        Preset("Salt Texture") { s ->
            s.wcFlow = 7f; s.wcEvaporate = 0.3f; s.wcAdsorb = 0.35f
            s.wcEdge = 9f; s.wcGrain = 1.5f
            s.wcLoadWater = 0.5f; s.wcLoadPigment = 0.34f
        }
    )

    private val vortex = listOf(
        Preset("Stir") { s -> s.forceStrength = 1.0f; s.combFrequency = 14f; s.pickup = 2.5f },
        Preset("Hard Stir") { s -> s.forceStrength = 2.2f; s.combFrequency = 14f; s.pickup = 5f },
        Preset("Fine Rake") { s -> s.forceStrength = 1.2f; s.combFrequency = 26f; s.pickup = 3f },
        Preset("Wide Rake") { s -> s.forceStrength = 1.2f; s.combFrequency = 7f; s.pickup = 3f },
        Preset("Smear Only") { s -> s.forceStrength = 1.0f; s.combFrequency = 14f; s.pickup = 0f }
    )

    private val solvent = listOf(
        Preset("Alcohol Drop") { s -> s.solventBite = 0.45f; s.forceStrength = 1.0f; s.pickup = 2.5f },
        Preset("Hard Lift") { s -> s.solventBite = 0.12f; s.forceStrength = 1.6f; s.pickup = 6f },
        Preset("Soft Lift") { s -> s.solventBite = 0.75f; s.forceStrength = 0.5f; s.pickup = 1.2f }
    )

    // How a charcoal smudge differs: how wide the finger is, how much it takes
    // with it, and how far it drags before letting go.
    private val smear = listOf(
        Preset("Finger") { s ->
            s.smearRadius = 0.05f; s.smearStrength = 0.85f; s.smearReach = 0.05f
        },
        Preset("Stump") { s ->
            s.smearRadius = 0.022f; s.smearStrength = 0.95f; s.smearReach = 0.03f
        },
        Preset("Chamois") { s ->
            s.smearRadius = 0.11f; s.smearStrength = 0.55f; s.smearReach = 0.08f
        },
        Preset("Long Drag") { s ->
            s.smearRadius = 0.045f; s.smearStrength = 0.98f; s.smearReach = 0.16f
        }
    )

    private val none = listOf(Preset("Default") { })

    fun forBrush(b: Brush): List<Preset> = when (b) {
        Brush.GAS -> gas
        Brush.NIB -> nib
        Brush.FLIP -> flip
        Brush.WATERCOLOR -> watercolor
        Brush.VORTEX -> vortex
        Brush.SOLVENT -> solvent
        Brush.SMEAR -> smear
        Brush.FREEZE, Brush.THAW -> none
    }
}
