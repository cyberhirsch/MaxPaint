#version 310 es
precision highp float;
in float vInk;
in vec3 vColour;
out vec4 fragColor;

void main() {
    vec2 d = gl_PointCoord - 0.5;
    float r2 = dot(d, d);
    if (r2 > 0.25) discard;

    // soft round kernel, premultiplied: black ink leaves rgb at 0, and a drop
    // that picked up a colour carries it in already multiplied by coverage
    float a = vInk * smoothstep(0.25, 0.0, r2);
    fragColor = vec4(vColour * a, a);
}
