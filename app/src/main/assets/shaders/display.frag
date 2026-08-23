#version 310 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
out vec4 fragColor;

layout(binding = 0) uniform highp sampler2D uDye;
uniform int uDebugView;   // 0 = dye, 1 = velocity

layout(binding = 1) uniform highp sampler2D uVel;

void main() {
    if (uDebugView == 1) {
        vec2 v = texture(uVel, vUv).xy;
        fragColor = vec4(0.5 + v * 0.5, 0.5, 1.0);
        return;
    }
    vec3 c = texture(uDye, vUv).rgb;
    fragColor = vec4(c, 1.0);
}
