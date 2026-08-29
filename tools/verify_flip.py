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
import re
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

    def emit(self, u, v, du, dv, n=64, radius=0.02, ink=0.14, aspect=1.0,
             axis=(1.0, 0.0), minor=1.0):
        glUseProgram(self.emit_p)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, self.buf)
        glUniform1i(uni(self.emit_p, "uHead"), self.head)
        glUniform1i(uni(self.emit_p, "uCount"), n)
        glUniform1i(uni(self.emit_p, "uCapacity"), CAP)
        glUniform2f(uni(self.emit_p, "uPoint"), u, v)
        glUniform2f(uni(self.emit_p, "uVel"), du, dv)
        glUniform1f(uni(self.emit_p, "uRadius"), radius)
        glUniform1f(uni(self.emit_p, "uAspect"), aspect)
        glUniform2f(uni(self.emit_p, "uAxis"), axis[0], axis[1])
        glUniform1f(uni(self.emit_p, "uMinor"), minor)
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

    def project(self, dt, iters=20, min_mass=0.08,
                rest=0.0, compression=0.0, cap=0.0):
        glUseProgram(self.blit_p)
        glUniform1i(uni(self.blit_p, "uSrc"), 0)
        self.vel.read_t.sampler(0)
        self.vel_old.image(0, GL_WRITE_ONLY)
        glDispatchCompute((self.gw + 7) // 8, (self.gh + 7) // 8, 1)
        self._bar()

        glUseProgram(self.div_p)
        glUniform1f(uni(self.div_p, "uRest"), rest)
        glUniform1f(uni(self.div_p, "uCompression"), compression)
        glUniform1f(uni(self.div_p, "uCap"), cap)
        self.vel.read_t.image(0, GL_READ_ONLY)
        self.div.image(1, GL_WRITE_ONLY)
        self.mass.image(2, GL_READ_ONLY)
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
            settle_speed=0.06, min_age=0.25, cohesion=0.0, rest=25.0):
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
        # same slider-to-speed mapping as FlipSystem.gridToParticles
        glUniform1f(uni(self.g2p_p, "uCohesionSpeed"), cohesion * 0.0025)
        glUniform1f(uni(self.g2p_p, "uRestMass"), rest)
        glUniform1f(uni(self.g2p_p, "uMaxSpeed"), 4.0)
        glUniform1i(uni(self.g2p_p, "uVelNew"), 0)
        glUniform1i(uni(self.g2p_p, "uVelOld"), 1)
        self.vel.read_t.sampler(0)
        self.vel_old.sampler(1)
        glDispatchCompute((CAP + 63) // 64, 1, 1)
        self._bar()

    def step(self, dt, vel_tex=None, flip_ratio=0.95, settle_speed=0.06,
             min_age=0.25, drag=0.25, aspect=1.0, cohesion=0.0, rest=25.0):
        self.p2g()
        self.project(dt)
        self.g2p(dt, flip_ratio, drag, aspect, settle_speed, min_age, cohesion,
                 rest)

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
    # read from the shader, so the check follows what actually ships
    _g2p = open(os.path.join(SHADERS, "flip_g2p.comp")).read()
    FLIP_RATIO_CAP = float(
        re.search(r"clamp\(uFlipRatio, 0\.0, ([\d.]+)\)", _g2p).group(1))

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

    # "There is no up" is really two claims, and the honest test is both:
    # undisturbed paint must not drift at all, and a throw must not leak much
    # into the cross axis. A single tolerance on a thrown blob conflates gravity
    # with the blob spreading against a nearby wall.
    still = Flip()
    still.make_grid(64, 64)
    still.emit(0.5, 0.5, 0.0, 0.0, n=256)
    p = still.read()
    live = p[:, 6] == 1.0
    s0 = p[live][:, :2].mean(axis=0)
    for _ in range(60):
        still.step(dt, settle_speed=0.0)
    p = still.read()
    live = p[:, 6] == 1.0
    s1 = p[live][:, :2].mean(axis=0)
    check("undisturbed paint does not drift: there is no up",
          float(abs(s1[0] - s0[0])) < 1e-4 and float(abs(s1[1] - s0[1])) < 1e-4,
          f"centroid moved ({s1[0] - s0[0]:+.5f}, {s1[1] - s0[1]:+.5f}) over 60 frames")

    along = abs(x1 - x0)
    across = abs(y1 - y0)
    check("a throw stays on its axis", across < along * 0.15,
          f"travelled {along:.3f} along, {across:.3f} across "
          f"({100 * across / max(along, 1e-9):.1f}%)")

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

    # ---- energy ----
    #
    # The invariant a fluid must not break: with no input at all, kinetic energy
    # falls. Nothing in the step is allowed to be a source.
    #
    # This replaces a check that asked whether the top of the motion-inheritance
    # slider "stays bounded", read peak speed at frame 60, and passed -- because
    # by frame 60 the runaway had already thrown everything into the walls and
    # retired it. It was measuring the aftermath and calling it stability. The
    # right question is whether energy rises at all, at any point.
    print("Energy:")

    def kinetic(ratio, frames=45):
        q = Flip()
        q.make_grid(244, 104)
        q.emit(0.5, 0.5, 0.3, 0.0, n=CAP // 3, radius=0.03, aspect=2.34)
        peak, first = 0.0, None
        for f in range(frames):
            q.p2g()
            q.project(dt, iters=60, rest=25.0, compression=0.01, cap=100.0)
            q.g2p(dt, drag=0.02, settle_speed=0.0, flip_ratio=ratio, cohesion=6.0)
            pp = q.read()
            live = pp[:, 6] == 1.0
            ke = float((pp[live][:, 2:4] ** 2).sum()) if live.sum() else 0.0
            if first is None:
                first = ke
            peak = max(peak, ke)
        return peak / max(first, 1e-9)

    for ratio in (0.3, 0.6, 0.9, 1.0):
        gain = kinetic(ratio)
        check(f"energy does not grow at {int(ratio * 100)}% motion inheritance",
              gain <= 1.05, f"peak is {gain:.2f}x the first frame")

    # and the shape of the failure that was shipped, so the cap cannot be
    # loosened again without this going red
    check("the blend is capped where energy stops being conserved",
          FLIP_RATIO_CAP <= 1.0, f"shader clamps to {FLIP_RATIO_CAP}")
    print()

    # ---- every shipped preset must come to rest ----
    #
    # The cohesion rework was driven by exactly this: a resting blob at the
    # shipped Mercury preset held a permanent kinetic-energy plateau with
    # particles pinned at the CFL cap, and could never dry. So the invariant is
    # checked at each preset's real numbers, cohesion included, not at one
    # flattering configuration. Mirrors Presets.kt; update together.
    print("Presets at rest:")
    PRESETS = [
        ("Wet Paint", 1.0, 0.25, 0.6, 0.06, 120.0),
        ("Splatter", 6.0, 0.02, 0.99, 0.10, 51.0),
        ("Fling", 8.0, 0.05, 0.97, 0.03, 29.0),
        ("Honey", 26.0, 1.6, 0.45, 0.02, 51.0),
        ("Mercury", 38.0, 0.04, 0.97, 0.015, 61.0),
    ]

    def rest_blob(coh, drag, ratio, settle, ppc, frames=120):
        q = Flip()
        q.make_grid(244, 104)
        q.emit(0.5, 0.5, 0.0, 0.0, n=1200, radius=0.03, aspect=2.34)
        for _ in range(frames):
            q.p2g()
            q.project(dt, iters=40)
            q.g2p(dt, flip_ratio=ratio, drag=drag, settle_speed=settle,
                  cohesion=coh, rest=ppc * 0.5)
        pp = q.read()
        live = pp[:, 6] == 1.0
        ke = float((pp[live][:, 2:4] ** 2).sum()) if live.sum() else 0.0
        # settled particles are drawn down and their slots cleared, so "dried"
        # is everything that is no longer live
        return ke, 1.0 - live.sum() / 1200.0

    for name, coh, drag, ratio, settle, ppc in PRESETS:
        ke, frac = rest_blob(coh, drag, ratio, settle, ppc)
        check(f"a resting blob dries at the {name} preset",
              frac > 0.7 and ke < 2.0,
              f"{frac * 100:.0f}% settled, residual KE {ke:.2f}")

    # and the top of the widened slider, which is where the old force was a
    # permanent explosion held together by the CFL clamp
    ke, frac = rest_blob(200.0, 0.02, 0.99, 0.06, 51.0)
    check("cohesion at slider max stays bounded and still dries",
          frac > 0.6 and ke < 2.0,
          f"{frac * 100:.0f}% settled, residual KE {ke:.2f}")
    print()

    # ---- pouring ----
    #
    # Emission is otherwise per dab and dabs only happen on movement, so a still
    # finger put down nothing at all. A pour runs on the clock instead: hold and
    # a volume builds, drag and its own momentum throws it.
    print("Pour:")

    PER, DENSITY = 96, 51.0

    def poured(frames, throw=False, compression=0.01):
        q = Flip()
        q.make_grid(244, 104)
        for _ in range(frames):
            q.emit(0.5, 0.5, 0.0, 0.0, n=PER, radius=0.02, aspect=2.34)
            q.p2g()
            q.project(dt, iters=60, rest=DENSITY * 0.5,
                      compression=compression, cap=DENSITY * 2)
            q.g2p(dt, drag=0.02, settle_speed=0.0, flip_ratio=1.15)
        built = frames * PER            # the volume, before anything is thrown
        if throw:
            # a drag across it: dabs carrying momentum, as a stroke makes
            for k in range(8):
                q.emit(0.5 + 0.01 * k, 0.5, 2.2, 0.0, n=PER // 2,
                       radius=0.02, aspect=2.34)
                q.p2g()
                q.project(dt, iters=60, rest=DENSITY * 0.5,
                          compression=compression, cap=DENSITY * 2)
                q.g2p(dt, drag=0.02, settle_speed=0.0, flip_ratio=1.15)
        else:
            for _ in range(8):
                q.p2g()
                q.project(dt, iters=60, rest=DENSITY * 0.5,
                          compression=compression, cap=DENSITY * 2)
                q.g2p(dt, drag=0.02, settle_speed=0.0, flip_ratio=1.15)
        pp = q.read()
        live = pp[:, 6] == 1.0
        cells = int((q.mass.read()[:, :, 0] > 0.08).sum())
        # only the particles that were poured, never the ones the drag added
        orig = np.zeros(len(pp), dtype=bool)
        orig[:built] = True
        vol = pp[orig & live]
        return dict(cells=cells, live=int(live.sum()),
                    peak=float(q.mass.read()[:, :, 0].max()),
                    spread=float(vol[:, 0].std()) if len(vol) else 0.0)

    short, long_ = poured(6), poured(24)
    check("holding still keeps putting paint down",
          long_["live"] > short["live"] * 3,
          f"{short['live']} particles after 6 frames, {long_['live']} after 24")

    check("and it spreads into a volume rather than stacking on one spot",
          long_["cells"] > short["cells"] * 3.0,
          f"{short['cells']} cells occupied, then {long_['cells']}")

    # the term that makes that happen, and what the medium does without it
    packed = poured(24, compression=0.0)
    check("without a cell pushing back, the same paint just piles up",
          packed["cells"] < long_["cells"] * 0.4 and packed["peak"] > long_["peak"] * 2,
          f"{packed['cells']} cells at density {packed['peak']:.0f}, "
          f"against {long_['cells']} at {long_['peak']:.0f}")

    still, thrown = poured(24), poured(24, throw=True)
    check("a drag throws the volume that was poured, not just the new paint",
          thrown["spread"] > still["spread"] * 1.3,
          f"the poured particles spread {still['spread']:.4f} left alone, "
          f"{thrown['spread']:.4f} when dragged through")
    print()

    # ---- the contact patch reaches the particles too ----
    #
    # A different implementation from the falloff brushes: the scatter disc is
    # squashed and turned rather than a distance being transformed, so it needs
    # its own coverage.
    print("Contact shape:")

    def cloud(axis=(1.0, 0.0), minor=1.0):
        q = Flip()
        q.emit(0.5, 0.5, 0.0, 0.0, n=CAP // 2, radius=0.05, axis=axis, minor=minor)
        pp = q.read()
        m = pp[:, 6] == 1.0
        return float(pp[m][:, 0].std()), float(pp[m][:, 1].std())

    rx, ry = cloud()
    check("a round contact scatters particles in a disc",
          abs(rx - ry) < rx * 0.15, f"spread {rx:.4f} x {ry:.4f}")

    fx, fy = cloud(minor=0.3)
    check("a flattened contact scatters them in an ellipse",
          fy < ry * 0.5 and abs(fx - rx) < rx * 0.15,
          f"spread {fx:.4f} x {fy:.4f} against {rx:.4f} x {ry:.4f} round")

    tx, ty = cloud(axis=(0.0, 1.0), minor=0.3)
    check("and turning the contact turns the cloud",
          abs(tx - fy) < fy * 0.25 and abs(ty - fx) < fx * 0.25,
          f"spread {tx:.4f} x {ty:.4f} against {fx:.4f} x {fy:.4f} unturned")
    print()

    # ---- the grid has to be coarse enough for particles to see each other ----
    #
    # FLIP is a particle method that borrows a grid so particles can feel each
    # other. The pressure solve couples a CELL to its neighbours, so a cell
    # holding one particle couples that particle to nothing. This measures the
    # coupling directly: two streams fired head-on, with the solve and without.
    src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "..", "app", "src", "main", "java", "com",
                            "maxpaint", "spike", "FluidSim.kt")).read()
    shipped_res = int(re.search(r"var flipRes = (\d+)", src).group(1))
    flip_src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                 "..", "app", "src", "main", "java", "com",
                                 "maxpaint", "spike", "FlipSystem.kt")).read()
    shipped_density = float(
        re.search(r"var particlesPerCell = ([\d.]+)f", flip_src).group(1))

    def coupling(g):
        def run(project):
            q = Flip()
            q.make_grid(g, g)
            q.emit(0.35, 0.5, 1.4, 0.0, n=CAP // 4, radius=0.03)
            q.emit(0.65, 0.5, -1.4, 0.0, n=CAP // 4, radius=0.03)
            for _ in range(45):
                q.p2g()
                if project:
                    q.project(dt, iters=30)
                q.g2p(dt, drag=0.0, settle_speed=0.0)
            pp = q.read()
            lv = pp[:, 6] == 1.0
            return float(np.std(pp[lv][:, 1])) if lv.sum() else 0.0

        probe = Flip()
        probe.make_grid(g, g)
        probe.emit(0.5, 0.5, 0.0, 0.0, n=CAP // 2, radius=0.03)
        probe.p2g()
        occupied = int((probe.mass.read()[:, :, 0] > 0.08).sum())
        off, on = run(False), run(True)
        return (CAP / 2) / max(occupied, 1), on / max(off, 1e-9)

    dense_per_cell, dense = coupling(192)
    check("the solve couples particles when a cell holds several of them",
          dense > 4.0,
          f"{dense_per_cell:.1f} particles per occupied cell, "
          f"streams spread {dense:.1f}x wider with the solve")

    # and the failure this replaced: one particle per cell is a spray of
    # independent points wearing a solver
    lone_per_cell, lone = coupling(768)
    check("and barely couples them at one particle per cell",
          lone < dense * 0.6,
          f"{lone_per_cell:.1f} per occupied cell gives only {lone:.1f}x")

    # Density must hold as the brush changes size. Coupling responds to
    # particles per cell, so a fixed count per dab means a wider brush spreads
    # the same particles thinner and Brush size silently changes how the medium
    # behaves -- measured 9.6 down to 1.0 per occupied cell over this range.
    # shaped from the shipped values, so the check follows the app rather than
    # a snapshot of what the app used to be
    ASPECT = 2.34
    RES = shipped_res
    FGW = int(RES * np.sqrt(ASPECT)) // 2 * 2
    FGH = int(RES / np.sqrt(ASPECT)) // 2 * 2
    CELL = np.sqrt(ASPECT) / RES

    def per_occupied(brush, fixed=None):
        r = brush * 0.5
        n = (fixed if fixed else
             int(np.clip(shipped_density * max(np.pi * r * r / (CELL * CELL), 1.0),
                         4, 2048)))
        dabs = min(12, max(1, CAP // n))
        q = Flip()
        q.make_grid(FGW, FGH)
        for d in range(dabs):
            q.emit(0.35 + (d * brush * 0.5) / ASPECT, 0.5, 0.6, 0.0,
                   n=n, radius=r, aspect=ASPECT)
        q.p2g()
        occ = int((q.mass.read()[:, :, 0] > 0.08).sum())
        return (n * dabs) / max(occ, 1)

    BRUSHES = (0.010, 0.023, 0.060)
    scaled = [per_occupied(b) for b in BRUSHES]
    check("particle density holds as the brush changes size",
          min(scaled) > 4.0 and max(scaled) / min(scaled) < 3.0,
          " ".join(f"{b:.3f}->{d:.1f}" for b, d in zip(BRUSHES, scaled)))

    fixed = [per_occupied(b, fixed=32) for b in BRUSHES]
    check("and a fixed count per dab does not",
          max(fixed) / min(fixed) > max(scaled) / min(scaled) * 1.5,
          " ".join(f"{b:.3f}->{d:.1f}" for b, d in zip(BRUSHES, fixed)))

    # the shipped grid must sit in the band that was measured to work
    check("the shipped particle grid is in that band",
          96 <= shipped_res <= 320, f"flipRes = {shipped_res}")
    print()

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

    # --- cohesion: paint must gather into droplets, not disperse ---
    print()
    print("Cohesion:")

    def gather(coh, frames=120):
        """Scatter a THIN film, then see whether it beads up.

        Thin on purpose: cohesion is gated off once local density reaches rest,
        so paint already at rest density only rounds its rim -- the crush of an
        at-density blob was the old energy pump, not a behaviour to test for.
        Beading is thin paint condensing toward rest density.
        """
        q = Flip()
        q.make_grid(64, 64)
        rng = np.random.default_rng(7)
        for k in range(24):
            a = float(rng.random()) * 2 * np.pi
            r = 0.28 * float(rng.random()) ** 0.5
            q.emit(0.5 + r * np.cos(a), 0.5 + r * np.sin(a), 0.0, 0.0,
                   n=CAP // 24, radius=0.045)
        p0 = q.read()
        c0 = p0[p0[:, 6] == 1.0][:, :2].mean(axis=0)
        for _ in range(frames):
            q.step(dt, drag=0.6, settle_speed=0.0, cohesion=coh, rest=12.0)
        q.p2g()
        m = q.mass.read()[:, :, 0]
        p = q.read()
        live = p[:, 6] == 1.0
        c1 = p[live][:, :2].mean(axis=0) if live.sum() else c0
        speeds = np.hypot(p[live][:, 2], p[live][:, 3]) if live.sum() else np.array([0.0])
        total = float(m.sum())
        return dict(cells=int((m > 0.08).sum()), peak=float(m.max()),
                    total=total,
                    condensed=float(m[m >= 0.8 * 12.0].sum()) / max(total, 1e-9),
                    meanv=float(speeds.mean()),
                    drift=float(np.hypot(*(c1 - c0))),
                    vmax=float(speeds.max()),
                    finite=bool(np.isfinite(p).all()))

    off = gather(0.0)
    on = gather(200.0)

    # Beading is: the paint's MASS condensing into cells at rest density. Not
    # the raw occupied-cell count -- a thin haze of stragglers occupies many
    # near-empty cells and counts them equally with beads -- and not peak
    # density, which measured the old bulk crush that was half of the energy
    # pump. The fraction of mass sitting in at-rest cells is the thing beading
    # actually changes.
    check("cohesion condenses a thin film into beads at rest density",
          on["condensed"] > off["condensed"] * 1.8,
          f"{off['condensed'] * 100:.0f}% of mass condensed without, "
          f"{on['condensed'] * 100:.0f}% with")

    # The half of the fix that matters: having beaded, the paint STOPS. The old
    # force held the rim at sustained speed forever, so nothing could ever dry.
    check("and the beads come to rest",
          on["meanv"] < 0.02,
          f"mean live speed {on['meanv']:.4f} after 120 frames")

    # It must gather in place. A skewed density field biases the gradient and
    # walks the whole liquid into a corner, which is exactly what the staggered
    # weights did before the cell-centred mass was reconstructed properly.
    check("cohesion gathers in place rather than drifting",
          on["drift"] < 0.05, f"centroid moved {on['drift']:.4f}")
    check("cohesion stays stable", on["finite"] and on["vmax"] <= 4.5,
          f"peak particle speed {on['vmax']:.2f}")
    check("cohesion does not collapse the liquid to a point",
          on["cells"] > 8, f"{on['cells']} cells still occupied")

    print()
    if FAILURES:
        print(f"{len(FAILURES)} CHECK(S) FAILED: {', '.join(FAILURES)}")
        return 1
    print("All FLIP checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
