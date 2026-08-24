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
from verify_solver import (make_context, compile_compute, uni, Tex, Double,
                           gstr, check, FAILURES)

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
        self.clear_p = compile_compute("flip_clear_grid.comp")
        self.p2g_p = compile_compute("flip_p2g.comp")
        self.norm_p = compile_compute("flip_normalize.comp")
        self.g2p_p = compile_compute("flip_g2p.comp")
        self.div_p = compile_compute("divergence_flip.comp")
        self.pres_p = compile_compute("pressure_flip.comp")
        self.grad_p = compile_compute("gradsub_flip.comp")
        self.blit_p = compile_compute("blit.comp")
        self.draw_p = link_draw()

        self.gw = self.gh = 0
        self.grid = None

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

    def make_grid(self, w, h):
        self.gw, self.gh = w, h
        self.grid = glGenBuffers(1)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, self.grid)
        glBufferData(GL_SHADER_STORAGE_BUFFER, w * h * 16,
                     np.zeros(w * h * 4, dtype=np.int32), GL_DYNAMIC_COPY)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
        self.vel = Double(w, h, GL_RGBA16F, GL_LINEAR)
        self.vel_old = Tex(w, h, GL_RGBA16F, GL_LINEAR)
        self.mass = Tex(w, h, GL_R32F, GL_NEAREST)
        self.pres = Double(w, h, GL_R32F, GL_NEAREST)
        self.div = Tex(w, h, GL_R32F, GL_NEAREST)

    def _bar(self):
        glMemoryBarrier(GL_ALL_BARRIER_BITS)

    def p2g(self):
        glUseProgram(self.clear_p)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, self.grid)
        glUniform1i(uni(self.clear_p, "uCells"), self.gw * self.gh)
        glDispatchCompute((self.gw * self.gh + 63) // 64, 1, 1)
        self._bar()

        glUseProgram(self.p2g_p)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, self.buf)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, self.grid)
        glUniform1i(uni(self.p2g_p, "uCapacity"), CAP)
        glUniform2i(uni(self.p2g_p, "uGrid"), self.gw, self.gh)
        glDispatchCompute((CAP + 63) // 64, 1, 1)
        self._bar()

        glUseProgram(self.norm_p)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, self.grid)
        glUniform2i(uni(self.norm_p, "uGrid"), self.gw, self.gh)
        self.vel.read_t.image(0, GL_WRITE_ONLY)
        self.mass.image(1, GL_WRITE_ONLY)
        glDispatchCompute((self.gw + 7) // 8, (self.gh + 7) // 8, 1)
        self._bar()

    def project(self, dt, iters=20, min_mass=0.08):
        glUseProgram(self.blit_p)
        glUniform1i(uni(self.blit_p, "uSrc"), 0)
        self.vel.read_t.sampler(0)
        self.vel_old.image(0, GL_WRITE_ONLY)
        glDispatchCompute((self.gw + 7) // 8, (self.gh + 7) // 8, 1)
        self._bar()

        glUseProgram(self.div_p)
        self.vel.read_t.image(0, GL_READ_ONLY)
        self.div.image(1, GL_WRITE_ONLY)
        glDispatchCompute((self.gw + 7) // 8, (self.gh + 7) // 8, 1)
        self._bar()

        glUseProgram(self.pres_p)
        glUniform1f(uni(self.pres_p, "uMinMass"), min_mass)
        self.div.image(2, GL_READ_ONLY)
        self.mass.image(3, GL_READ_ONLY)
        for _ in range(iters):
            for parity in (0, 1):
                self.pres.read_t.image(0, GL_READ_WRITE)
                glUniform1i(uni(self.pres_p, "uParity"), parity)
                glDispatchCompute(((self.gw + 1) // 2 + 7) // 8, (self.gh + 7) // 8, 1)
        self._bar()

        glUseProgram(self.grad_p)
        glUniform1f(uni(self.grad_p, "uDrag"), 0.0)
        glUniform1f(uni(self.grad_p, "uDt"), dt)
        glUniform1f(uni(self.grad_p, "uMaxSpeed"), 4.0)
        self.vel.read_t.image(0, GL_READ_ONLY)
        self.vel.write_t.image(1, GL_WRITE_ONLY)
        self.pres.read_t.image(2, GL_READ_ONLY)
        glDispatchCompute((self.gw + 7) // 8, (self.gh + 7) // 8, 1)
        self.vel.swap()
        self._bar()

    def g2p(self, dt, flip_ratio=0.95, drag=0.25, aspect=1.0,
            settle_speed=0.06, min_age=0.25):
        glUseProgram(self.g2p_p)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, self.buf)
        glUniform1f(uni(self.g2p_p, "uDt"), dt)
        glUniform1i(uni(self.g2p_p, "uCapacity"), CAP)
        glUniform1f(uni(self.g2p_p, "uFlipRatio"), flip_ratio)
        glUniform1f(uni(self.g2p_p, "uDrag"), drag)
        glUniform1f(uni(self.g2p_p, "uAspect"), aspect)
        glUniform1f(uni(self.g2p_p, "uSettleSpeed"), settle_speed)
        glUniform1f(uni(self.g2p_p, "uSettleMinAge"), min_age)
        glUniform2f(uni(self.g2p_p, "uTexel"), 1.0 / self.gw, 1.0 / self.gh)
        glUniform1i(uni(self.g2p_p, "uVelNew"), 0)
        glUniform1i(uni(self.g2p_p, "uVelOld"), 1)
        self.vel.read_t.sampler(0)
        self.vel_old.sampler(1)
        glDispatchCompute((CAP + 63) // 64, 1, 1)
        self._bar()

    def step(self, dt, vel_tex=None, flip_ratio=0.95, settle_speed=0.06,
             min_age=0.25, drag=0.25, aspect=1.0):
        self.p2g()
        self.project(dt)
        self.g2p(dt, flip_ratio, drag, aspect, settle_speed, min_age)

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
    f.make_grid(64, 64)
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

    # There is no gravity, so paint must travel on the stroke's momentum and
    # stop where drag stops it -- not fall.
    m = Flip()
    m.make_grid(64, 64)
    m.emit(0.3, 0.5, 1.6, 0.0, n=256)      # thrown to the right
    p = m.read()
    live = p[:, 6] == 1.0
    x0 = float(p[live][:, 0].mean())
    y0 = float(p[live][:, 1].mean())
    for _ in range(40):
        m.step(dt, vel)
    p = m.read()
    live = p[:, 6] == 1.0
    x1 = float(p[live][:, 0].mean()) if live.sum() else x0
    y1 = float(p[live][:, 1].mean()) if live.sum() else y0
    check("paint travels on the momentum of the stroke", x1 > x0 + 0.02,
          f"x {x0:.3f} -> {x1:.3f}")
    check("paint does not fall: there is no up", abs(y1 - y0) < 0.01,
          f"y {y0:.3f} -> {y1:.3f} (drift {y1 - y0:+.4f})")

    # and it must come to rest rather than coasting forever
    for _ in range(400):
        m.step(dt, vel)
    p = m.read()
    speeds = np.hypot(p[:, 2], p[:, 3])[p[:, 6] == 1.0]
    check("drag brings paint to rest",
          len(speeds) == 0 or float(speeds.mean()) < 0.1,
          f"{len(speeds)} still moving" if len(speeds) else "all settled")

    # settling and retirement, with no floor to land on
    f2 = Flip()
    f2.make_grid(64, 64)
    f2.emit(0.5, 0.5, 0.8, 0.4, n=256)
    for _ in range(700):
        f2.step(dt, vel)
        f2.draw(2.0, bg)
    p = f2.read()
    check("particles settle and retire once dry",
          (p[:, 6] == 1.0).sum() < 256 * 0.2,
          f"{int((p[:,6]==1.0).sum())} of 256 still live")

    img = bg.read()
    ink = float(img[:, :, 3].sum())
    check("dried particles land in the background layer", ink > 0,
          f"background ink {ink:.1f}")

    # flip ratio must actually change the character of the motion
    def spread(ratio):
        g = Flip()
        g.make_grid(64, 64)
        g.emit(0.5, 0.5, 1.5, 0.0, n=256)
        for _ in range(40):
            g.step(dt, flip_ratio=ratio, drag=0.6)
        q = g.read()
        m = q[:, 6] == 1.0
        return float(q[m][:, 0].std()) if m.sum() else 0.0

    splashy, viscous = spread(0.98), spread(0.2)
    check("flip ratio changes the feel: high keeps momentum, low follows the grid",
          splashy > viscous, f"0.98 spread {splashy:.4f} vs 0.2 spread {viscous:.4f}")

    # --- incompressibility: the thing that makes it liquid ---
    print()
    print("Incompressibility:")

    # Incompressibility means the grid velocity is made divergence-free. It does
    # NOT push a static over-dense blob apart -- clumping is a known FLIP
    # artefact that needs APIC or explicit position correction. What it does
    # guarantee is that converging flow cannot keep converging, which is what
    # makes particles behave as one body of liquid.
    conv = Flip()
    conv.make_grid(64, 64)
    n = CAP // 4
    # a ring of particles all heading for the centre
    for k in range(8):
        ang = k / 8.0 * 2 * np.pi
        conv.emit(0.5 + 0.12 * np.cos(ang), 0.5 + 0.12 * np.sin(ang),
                  -1.2 * np.cos(ang), -1.2 * np.sin(ang),
                  n=n // 8, radius=0.02)
    conv.p2g()

    def rms_div(f):
        """Divergence in INTERIOR liquid cells only.

        A free surface is not divergence-free at its boundary and must not be:
        the air is not simulated, so liquid has to be free to move into it.
        Incompressibility is a claim about the interior, so that is what is
        measured -- cells whose four neighbours also hold liquid.
        """
        glUseProgram(f.div_p)
        f.vel.read_t.image(0, GL_READ_ONLY)
        f.div.image(1, GL_WRITE_ONLY)
        glDispatchCompute((f.gw + 7) // 8, (f.gh + 7) // 8, 1)
        f._bar()
        d = f.div.read()[:, :, 0]
        m = f.mass.read()[:, :, 0] > 0.08
        interior = (m &
                    np.roll(m, 1, 0) & np.roll(m, -1, 0) &
                    np.roll(m, 1, 1) & np.roll(m, -1, 1))
        return (float(np.sqrt((d[interior] ** 2).mean()))
                if interior.sum() else 0.0), int(interior.sum())

    before, cells = rms_div(conv)
    conv.project(dt, iters=40)
    after, _ = rms_div(conv)
    check("converging flow is made divergence-free in the interior",
          cells > 0 and after < before * 0.5,
          f"rms divergence {before:.5f} -> {after:.5f} "
          f"({100 * (1 - after / max(before, 1e-9)):.0f}% removed, "
          f"{cells} interior cells)")

    # and behaviourally: colliding streams must resist passing through each other
    def collide(project):
        q = Flip()
        q.make_grid(64, 64)
        q.emit(0.35, 0.5, 1.4, 0.0, n=CAP // 4, radius=0.03)
        q.emit(0.65, 0.5, -1.4, 0.0, n=CAP // 4, radius=0.03)
        for _ in range(45):
            q.p2g()
            if project:
                q.project(dt, iters=30)
            else:
                glUseProgram(q.blit_p)
                glUniform1i(uni(q.blit_p, "uSrc"), 0)
                q.vel.read_t.sampler(0)
                q.vel_old.image(0, GL_WRITE_ONLY)
                glDispatchCompute((q.gw + 7) // 8, (q.gh + 7) // 8, 1)
                q._bar()
            q.g2p(dt, drag=0.0, settle_speed=0.0)
        p = q.read()
        live = p[:, 6] == 1.0
        # spread across the collision axis: pass-through keeps them narrow,
        # resisting spreads them
        return float(np.std(p[live][:, 1])) if live.sum() else 0.0

    with_p, without_p = collide(True), collide(False)
    check("colliding streams push each other aside rather than passing through",
          with_p > without_p * 1.15,
          f"cross-axis spread {without_p:.4f} without the solve, {with_p:.4f} with")

    # the grid must actually receive momentum
    t = Flip()
    t.make_grid(64, 64)
    t.emit(0.5, 0.5, 1.0, 0.0, n=512, radius=0.05)
    t.p2g()
    mass = t.mass.read()[:, :, 0]
    vel = t.vel.read_t.read()
    check("particles deposit mass on the grid", float(mass.sum()) > 0,
          f"total mass {mass.sum():.1f}")
    check("particles deposit momentum on the grid",
          float(vel[:, :, 0].max()) > 0.5,
          f"peak grid vx {vel[:,:,0].max():.3f}")
    check("empty cells stay empty", float((mass > 0).mean()) < 0.5,
          f"{100 * (mass > 0).mean():.1f}% of cells hold liquid")

    print()
    if FAILURES:
        print(f"{len(FAILURES)} CHECK(S) FAILED: {', '.join(FAILURES)}")
        return 1
    print("All FLIP checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
