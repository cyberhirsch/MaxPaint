#version 310 es
precision highp float;

// The particle buffer is bound as vertex attributes rather than read as an SSBO:
// ES 3.1 guarantees zero storage blocks in vertex shaders.
layout(location = 0) in vec4 aPosVel;   // pos.xy, vel.xy
layout(location = 1) in vec4 aMeta;     // ink, age, state, seed

uniform float uPointSize;
uniform float uWantState;   // draw only particles in this state

out float vInk;

void main() {
    if (abs(aMeta.z - uWantState) > 0.5) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);   // offscreen
        gl_PointSize = 0.0;
        vInk = 0.0;
        return;
    }
    vInk = aMeta.x;
    gl_PointSize = uPointSize;
    gl_Position = vec4(aPosVel.xy * 2.0 - 1.0, 0.0, 1.0);
}
