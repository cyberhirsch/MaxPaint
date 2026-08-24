#!/usr/bin/env python3
"""
Headless verification of the FLIP particle medium.

Exercises the part of the design most likely to be wrong on real hardware: one
buffer bound both as a shader storage buffer for compute and as a vertex buffer
for drawing, which is how the system avoids reading storage buffers in a vertex
shader (ES 3.1 guarantees zero of those).
"""
import ctypes
import os
import sys

import numpy as np
from OpenGL.GLES3 import *
# PyOpenGL's wrapper for glVertexAttribPointer keeps per-context bookkeeping that
# it cannot resolve under surfaceless EGL; the raw entry point skips it.
from OpenGL.raw.GLES2.VERSION.GLES2_2_0 import glVertexAttribPointer as rawVertexAttribPointer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_solver import make_context, compile_compute, uni, Tex, gstr, check, FAILURES

SHADERS = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "app", "src", "main", "assets", "shaders")
STRIDE = 32
CAP = 4096


def link_draw():
    def sh(kind, path):
        src = open(os.path.join(SHADERS, path)).read()
        s = glCreateShader(kind)
        glShaderSource(s, src)
        glCompileShader(s)
        if not glGetShaderiv(s, GL_COMPILE_STATUS):
            raise RuntimeError(f"{path}: {gstr(glGetShaderInfoLog(s))}")
        return s
    p = glCreateProgram()
    glAttachShader(p, sh(GL_VERTEX_SHADER, "particle.vert"))
    glAttachShader(p, sh(GL_FRAGMENT_SHADER, "particle.frag"))
    glLinkProgram(p)
    if not glGetProgramiv(p, GL_LINK_STATUS):
        raise RuntimeError(gstr(glGetProgramInfoLog(p)))
    return p


class Flip:
    def __init__(self):
        self.emit_p = compile_compute("flip_emit.comp")
        self.update_p = compile_compute("flip_update.comp")
        self.draw_p = link_draw()

        self.buf = glGenBuffers(1)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, self.buf)
        glBufferData(GL_SHADER_STORAGE_BUFFER, CAP * STRIDE,
                     np.zeros(CAP * 8, dtype=np.float32), GL_DYNAMIC_DRAW)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)

        self.vao = glGenVertexArrays(1)
        glBindVertexArray(self.vao)
        glBindBuffer(GL_ARRAY_BUFFER, self.buf)
        glEnableVertexAttribArray(0)
        rawVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, STRIDE, ctypes.c_void_p(0))
        glEnableVertexAttribArray(1)
        rawVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, STRIDE, ctypes.c_void_p(16))
        glBindVertexArray(0)

        self.head = 0
        self.seed = 1.0

    def read(self):
        # ES has no glGetBufferSubData; map the range instead. The barrier
        # matters: mapping straight after a draw that consumed the buffer can
        # otherwise hand back stale contents.
        glMemoryBarrier(GL_ALL_BARRIER_BITS)
        glFinish()
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, self.buf)
        ptr = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, CAP * STRIDE, GL_MAP_READ_BIT)
        buf = (ctypes.c_float * (CAP * 8)).from_address(
            ctypes.cast(ptr, ctypes.c_void_p).value)
        out = np.frombuffer(bytes(buf), dtype=np.float32).reshape(CAP, 8).copy()
        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
        return out

    def emit(self, u, v, du, dv, n=64, radius=0.02, ink=0.14):
        glUseProgram(self.emit_p)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, self.buf)
        glUniform1i(uni(self.emit_p, "uHead"), self.head)
        glUniform1i(uni(self.emit_p, "uCount"), n)
        glUniform1i(uni(self.emit_p, "uCapacity"), CAP)
        glUniform2f(uni(self.emit_p, "uPoint"), u, v)
        glUniform2f(uni(self.emit_p, "uVel"), du, dv)
        glUniform1f(uni(self.emit_p, "uRadius"), radius)
        glUniform1f(uni(self.emit_p, "uInk"), ink)
        glUniform1f(uni(self.emit_p, "uJitterSeed"), self.seed)
        glDispatchCompute((n + 63) // 64, 1, 1)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
        self.head = (self.head + n) % CAP
        self.seed = (self.seed + 13.37) % 1000.0

    def step(self, dt, vel_tex, flip_ratio=0.92, gravity=(0.0, -0.55),
             settle_speed=0.06, min_age=0.25, drag=0.25):
        glUseProgram(self.update_p)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, self.buf)
        glUniform1f(uni(self.update_p, "uDt"), dt)
        glUniform1i(uni(self.update_p, "uCapacity"), CAP)
        glUniform1f(uni(self.update_p, "uFlipRatio"), flip_ratio)
        glUniform2f(uni(self.update_p, "uGravity"), *gravity)
        glUniform1f(uni(self.update_p, "uSettleSpeed"), settle_speed)
        glUniform1f(uni(self.update_p, "uSettleMinAge"), min_age)
        glUniform1f(uni(self.update_p, "uDrag"), drag)
        glUniform1i(uni(self.update_p, "uVel"), 0)
        vel_tex.sampler(0)
        glDispatchCompute((CAP + 63) // 64, 1, 1)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT)

    def draw(self, state, target, point_size=5.0):
        fbo = glGenFramebuffers(1)
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_2D, target.id, 0)
        glViewport(0, 0, target.w, target.h)
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE)
        glUseProgram(self.draw_p)
        glUniform1f(uni(self.draw_p, "uPointSize"), point_size)
        glUniform1f(uni(self.draw_p, "uWantState"), state)
        glBindVertexArray(self.vao)
        glDrawArrays(GL_POINTS, 0, CAP)
        glBindVertexArray(0)
        glDisable(GL_BLEND)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glDeleteFramebuffers(1, [fbo])


def main():
    make_context()
    print(f"Driver: {gstr(glGetString(GL_RENDERER))}\n")
    print("FLIP particles:")

    dt = 1 / 60.0
    vel = Tex(128, 128, GL_RGBA16F, GL_LINEAR)     # still grid: gravity only
    bg = Tex(256, 256, GL_RGBA16F, GL_LINEAR)

    f = Flip()
    f.emit(0.5, 0.8, 0.0, 0.0, n=256)
    p = f.read()
    live = p[:, 6] == 1.0
    check("emission fills the pool", live.sum() == 256, f"{int(live.sum())} live")
    check("particles start where the brush was",
          abs(float(p[live][:, 0].mean()) - 0.5) < 0.03 and
          abs(float(p[live][:, 1].mean()) - 0.8) < 0.03,
          f"centroid ({p[live][:,0].mean():.3f}, {p[live][:,1].mean():.3f})")
    check("emission is jittered, not a single point",
          float(p[live][:, 0].std()) > 1e-4, f"x spread {p[live][:,0].std():.4f}")

    y0 = float(p[live][:, 1].mean())
    for _ in range(30):
        f.step(dt, vel)
    p = f.read()
    live = p[:, 6] == 1.0
    y1 = float(p[live][:, 1].mean()) if live.sum() else 0.0
    check("paint falls under gravity", y1 < y0 - 0.01,
          f"y {y0:.3f} -> {y1:.3f}")

    # run until they pool at the floor and dry
    for _ in range(700):
        f.step(dt, vel)
        f.draw(2.0, bg)
    p = f.read()
    check("particles settle and retire once dry",
          (p[:, 6] == 1.0).sum() < 256 * 0.2,
          f"{int((p[:,6]==1.0).sum())} of 256 still live")

    img = bg.read()
    ink = float(img[:, :, 3].sum())
    check("dried particles land in the background layer", ink > 0,
          f"background ink {ink:.1f}")

    rows = img[:, :, 3].sum(axis=1)
    lowest = int(np.argmax(rows > rows.max() * 0.2)) if rows.max() > 0 else -1
    check("paint pools at the floor rather than mid-air",
          lowest >= 0 and lowest < img.shape[0] * 0.35,
          f"lowest inked row {lowest} of {img.shape[0]}")

    # flip ratio must actually change the character of the motion
    def spread(ratio):
        vel2 = Tex(128, 128, GL_RGBA16F, GL_LINEAR)
        g = Flip()
        g.emit(0.5, 0.5, 1.5, 0.0, n=256)
        for _ in range(40):
            g.step(dt, vel2, flip_ratio=ratio, gravity=(0.0, 0.0), drag=0.6)
        q = g.read()
        m = q[:, 6] == 1.0
        return float(q[m][:, 0].std()) if m.sum() else 0.0

    splashy, viscous = spread(0.98), spread(0.2)
    check("flip ratio changes the feel: high keeps momentum, low follows the grid",
          splashy > viscous, f"0.98 spread {splashy:.4f} vs 0.2 spread {viscous:.4f}")

    print()
    if FAILURES:
        print(f"{len(FAILURES)} CHECK(S) FAILED: {', '.join(FAILURES)}")
        return 1
    print("All FLIP checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
