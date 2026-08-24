#version 310 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
out vec4 fragColor;

layout(binding = 0) uniform highp sampler2D uDye;         // live fluid
layout(binding = 1) uniform highp sampler2D uVel;
layout(binding = 2) uniform highp sampler2D uBackground;  // baked paint

uniform int   uDebugView;    // 0 = paint, 1 = velocity
uniform int   uHeat;         // 1 = tint live fluid by how close it is to setting
uniform float uSettleSpeed;  // speed below which paint starts to set

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

    fragColor = vec4(col, 1.0);
}
