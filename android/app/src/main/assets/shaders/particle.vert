#version 310 es
precision highp float;

// The particle buffer is bound as vertex attributes rather than read as an SSBO:
// ES 3.1 guarantees zero storage blocks in vertex shaders.
layout(location = 0) in vec4 aPosVel;   // pos.xy, vel.xy
layout(location = 1) in vec4 aMeta;     // ink, age, state, seed

uniform float uPointSize;
uniform float uWantState;   // draw only particles in this state
// aMeta.w holds the seed the emitter rolled, unless the drop picked up a
// colour instead -- six bits a channel in one small integer. Only the host
// knows which, so it says.
uniform int uColoured;

out float vInk;
out vec3 vColour;

void main() {
    if (abs(aMeta.z - uWantState) > 0.5) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);   // offscreen
        gl_PointSize = 0.0;
        vInk = 0.0;
        vColour = vec3(0.0);
        return;
    }
    vInk = aMeta.x;
    if (uColoured == 1) {
        float code = floor(aMeta.w + 0.5);
        float r = floor(code / 4096.0);
        float g = floor((code - r * 4096.0) / 64.0);
        float b = code - r * 4096.0 - g * 64.0;
        vColour = vec3(r, g, b) / 63.0;
    } else {
        vColour = vec3(0.0);
    }
    gl_PointSize = uPointSize;
    gl_Position = vec4(aPosVel.xy * 2.0 - 1.0, 0.0, 1.0);
}
