#version 310 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
out vec4 fragColor;

layout(binding = 0) uniform highp sampler2D uDye;         // live fluid
layout(binding = 1) uniform highp sampler2D uVel;
layout(binding = 2) uniform highp sampler2D uBackground;  // baked paint
layout(binding = 3) uniform highp sampler2D uWater;       // watercolor: depth, suspended, adsorbed
layout(binding = 4) uniform highp sampler2D uFlip;        // live particle ink

uniform int   uDebugView;    // 0 = paint, 1 = velocity
uniform int   uHeat;         // 1 = tint live fluid by how close it is to setting
uniform float uSettleSpeed;  // speed below which paint starts to set
uniform int   uShowWater;    // 1 = draw wet watercolor pigment and a damp sheen

const vec3 PAPER = vec3(1.0);

void main() {
    if (uDebugView == 1) {
        vec2 v = texture(uVel, vUv).xy;
        fragColor = vec4(0.5 + v * 0.5, 0.5, 1.0);
        return;
    }

    vec4 baked = texture(uBackground, vUv);
    vec4 live  = texture(uDye, vUv);

    // Dye and background are premultiplied, so compositing is a plain "over":
    // paper first, then baked paint, then the live fluid on top.
    vec3 col = PAPER * (1.0 - clamp(baked.a, 0.0, 1.0)) + baked.rgb;

    if (uShowWater == 1) {
        vec3 w = texture(uWater, vUv).xyz;
        // pigment still in suspension or freshly adsorbed but not yet dry
        float wetInk = clamp(w.y + w.z, 0.0, 1.0);
        col *= (1.0 - wetInk);
        // a faint cool sheen where the paper is still damp, so wet-on-wet is
        // visible while you are painting into it
        col = mix(col, col * vec3(0.93, 0.95, 1.0), clamp(w.x * 2.0, 0.0, 0.35));
    }

    vec3 liveRgb = live.rgb;
    if (uHeat == 1) {
        // UX-3: show what is about to freeze. Fast fluid reads warm, fluid that
        // has slowed below the settle threshold cools toward its final colour.
        float speed = length(texture(uVel, vUv).xy);
        float settling = 1.0 - clamp(speed / max(uSettleSpeed, 1e-5), 0.0, 1.0);
        vec3 hot = vec3(0.85, 0.30, 0.05) * live.a;   // still moving
        liveRgb = mix(hot, live.rgb, settling);
    }

    col = col * (1.0 - clamp(live.a, 0.0, 1.0)) + liveRgb;

    vec4 drops = texture(uFlip, vUv);
    col = col * (1.0 - clamp(drops.a, 0.0, 1.0)) + drops.rgb;

    fragColor = vec4(col, 1.0);
}
