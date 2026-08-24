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
            s.vorticity = 22f; s.velocityDrag = 0.12f; s.dyeDissipation = 0.05f
            s.splatRadius = 0.02f; s.velocityGain = 1.0f
            s.settleSpeed = 0.35f; s.bakeRate = 2.5f; s.settleMinAge = 0.35f
        },
        Preset("Ink Drop") { s ->
            s.vorticity = 8f; s.velocityDrag = 0.9f; s.dyeDissipation = 0.0f
            s.splatRadius = 0.035f; s.velocityGain = 0.4f
            s.settleSpeed = 0.5f; s.bakeRate = 4f; s.settleMinAge = 0.15f
        },
        Preset("Nebula") { s ->
            s.vorticity = 38f; s.velocityDrag = 0.03f; s.dyeDissipation = 0.02f
            s.splatRadius = 0.05f; s.velocityGain = 1.6f
            s.settleSpeed = 0.12f; s.bakeRate = 0.8f; s.settleMinAge = 2.0f
        },
        Preset("Aurora") { s ->
            s.vorticity = 30f; s.velocityDrag = 0.06f; s.dyeDissipation = 0.08f
            s.splatRadius = 0.03f; s.velocityGain = 2.2f
            s.settleSpeed = 0.2f; s.bakeRate = 1.2f; s.settleMinAge = 1.2f
        },
        Preset("Steam") { s ->
            s.vorticity = 14f; s.velocityDrag = 0.25f; s.dyeDissipation = 0.35f
            s.splatRadius = 0.045f; s.velocityGain = 0.9f
            s.settleSpeed = 0.3f; s.bakeRate = 1.5f; s.settleMinAge = 0.8f
        }
    )

    private val flip = listOf(
        Preset("Wet Paint") { s ->
            s.flip.flipRatio = 0.92f; s.flip.gravityY = -0.55f
            s.flip.particleDrag = 0.25f; s.flip.settleSpeed = 0.06f
            s.flip.pointSize = 5f; s.flip.emitPerSample = 12
        },
        Preset("Drip") { s ->
            s.flip.flipRatio = 0.88f; s.flip.gravityY = -1.4f
            s.flip.particleDrag = 0.08f; s.flip.settleSpeed = 0.03f
            s.flip.pointSize = 4f; s.flip.emitPerSample = 6
        },
        Preset("Splatter") { s ->
            s.flip.flipRatio = 0.99f; s.flip.gravityY = -0.4f
            s.flip.particleDrag = 0.02f; s.flip.settleSpeed = 0.12f
            s.flip.pointSize = 3f; s.flip.emitPerSample = 28
        },
        Preset("Honey") { s ->
            s.flip.flipRatio = 0.45f; s.flip.gravityY = -0.35f
            s.flip.particleDrag = 1.6f; s.flip.settleSpeed = 0.02f
            s.flip.pointSize = 8f; s.flip.emitPerSample = 10
        },
        Preset("Mercury") { s ->
            s.flip.flipRatio = 0.97f; s.flip.gravityY = -0.9f
            s.flip.particleDrag = 0.04f; s.flip.settleSpeed = 0.015f
            s.flip.pointSize = 7f; s.flip.emitPerSample = 14
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
        Preset("Stir") { s -> s.forceStrength = 1.0f; s.combFrequency = 14f },
        Preset("Hard Stir") { s -> s.forceStrength = 2.2f; s.combFrequency = 14f },
        Preset("Fine Rake") { s -> s.forceStrength = 1.2f; s.combFrequency = 26f },
        Preset("Wide Rake") { s -> s.forceStrength = 1.2f; s.combFrequency = 7f }
    )

    private val solvent = listOf(
        Preset("Alcohol Drop") { s -> s.solventBite = 0.45f; s.forceStrength = 1.0f },
        Preset("Hard Lift") { s -> s.solventBite = 0.12f; s.forceStrength = 1.6f },
        Preset("Soft Lift") { s -> s.solventBite = 0.75f; s.forceStrength = 0.5f }
    )

    private val none = listOf(Preset("Default") { })

    fun forBrush(b: Brush): List<Preset> = when (b) {
        Brush.GAS -> gas
        Brush.FLIP -> flip
        Brush.WATERCOLOR -> watercolor
        Brush.VORTEX -> vortex
        Brush.SOLVENT -> solvent
        Brush.FREEZE, Brush.THAW -> none
    }
}
