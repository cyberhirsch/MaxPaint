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
                       "..", "android", "app", "src", "main", "assets", "shaders")
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
        self.sep_clear_p = compile_compute("flip_sep_clear.comp")
        self.sep_bin_p = compile_compute("flip_sep_bin.comp")
        self.sep_push_p = compile_compute("flip_sep_push.comp")
        self.integrate_p = compile_compute("flip_integrate.comp")
        self.solve_p = compile_compute("flip_solve.comp")
        self.copy_p = compile_compute("flip_copy.comp")
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

        # separation hash: allocated lazily at the spacing the pass needs,
        # 13 ints per cell (count + 12 ids), same as FlipSystem
        self.sep_w = self.sep_h = 0
        self.sep_buf = 0

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
             axis=(1.0, 0.0), minor=1.0, seg=None):
        # seg=(u2, v2, du2, dv2) emits along a segment with the motion vector
        # interpolated, as a moving pour does; None is a point dab
        u2, v2, du2, dv2 = seg if seg else (u, v, du, dv)
        glUseProgram(self.emit_p)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, self.buf)
        glUniform1i(uni(self.emit_p, "uHead"), self.head)
        glUniform1i(uni(self.emit_p, "uCount"), n)
        glUniform1i(uni(self.emit_p, "uCapacity"), CAP)
        glUniform2f(uni(self.emit_p, "uPoint"), u, v)
        glUniform2f(uni(self.emit_p, "uVel"), du, dv)
        glUniform2f(uni(self.emit_p, "uPointB"), u2, v2)
        glUniform2f(uni(self.emit_p, "uVelB"), du2, dv2)
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
        # six ints per cell: uMom, uW, vMom, vW, density, cellType
        glBufferData(GL_SHADER_STORAGE_BUFFER, w * h * 24,
                     np.zeros(w * h * 6, dtype=np.int32), GL_DYNAMIC_COPY)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
        self.u = Tex(w, h, GL_R32F, GL_NEAREST)
        self.v = Tex(w, h, GL_R32F, GL_NEAREST)
        self.u_old = Tex(w, h, GL_R32F, GL_NEAREST)
        self.v_old = Tex(w, h, GL_R32F, GL_NEAREST)
        self.density = Tex(w, h, GL_RGBA16F, GL_LINEAR)

    def mass_field(self):
        """Cell-centred density, particles per cell."""
        return self.density.read()[:, :, 0]

    def cell_types(self):
        """Cell type per cell: 0 solid, 1 air, 2 fluid. Shape (h, w)."""
        glMemoryBarrier(GL_ALL_BARRIER_BITS)
        glFinish()
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, self.grid)
        n = self.gw * self.gh * 6
        ptr = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, n * 4, GL_MAP_READ_BIT)
        buf = (ctypes.c_int32 * n).from_address(
            ctypes.cast(ptr, ctypes.c_void_p).value)
        out = np.frombuffer(bytes(buf), dtype=np.int32).reshape(
            self.gh, self.gw, 6)[:, :, 5].copy()
        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
        return out

    def div_field(self):
        """Divergence of the current grid field, per cell."""
        u = self.u.read()[:, :, 0]
        v = self.v.read()[:, :, 0]
        ur = np.zeros_like(u); ur[:, :-1] = u[:, 1:]   # right face; wall = 0
        vt = np.zeros_like(v); vt[:-1, :] = v[1:, :]   # top face; wall = 0
        return ur - u + vt - v

    def _bar(self):
        glMemoryBarrier(GL_ALL_BARRIER_BITS)

    def push_apart(self, aspect=1.0, ppc=50.0, iters=2):
        # same geometry as FlipSystem.pushApart: hash cells 2x the rest
        # distance, capped by a memory budget
        # 0.7x the hex rest distance: below the packing of a fully condensed
        # bead (1.8x rest density), so separation never unpacks what cohesion
        # gathered -- see FlipSystem.pushApart
        cell = aspect ** 0.5 / max(self.gw, 1)
        min_dist = 0.75 * cell / max(ppc, 1.0) ** 0.5
        spacing = 2.0 * min_dist
        budget = 600_000
        if aspect / spacing ** 2 > budget:
            spacing = (aspect / budget) ** 0.5
        min_dist = min(min_dist, 0.95 * spacing)
        w = max(int(np.ceil(aspect / spacing)), 8)
        h = max(int(np.ceil(1.0 / spacing)), 8)
        if self.sep_buf == 0 or (w, h) != (self.sep_w, self.sep_h):
            if self.sep_buf:
                glDeleteBuffers(1, [self.sep_buf])
            self.sep_w, self.sep_h = w, h
            self.sep_buf = glGenBuffers(1)
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, self.sep_buf)
            glBufferData(GL_SHADER_STORAGE_BUFFER, w * h * 13 * 4,
                         np.zeros(w * h * 13, dtype=np.int32), GL_DYNAMIC_COPY)
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
        cells = self.sep_w * self.sep_h
        for _ in range(iters):
            glUseProgram(self.sep_clear_p)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, self.sep_buf)
            glUniform1i(uni(self.sep_clear_p, "uCells"), cells)
            glDispatchCompute((cells + 63) // 64, 1, 1)
            self._bar()

            glUseProgram(self.sep_bin_p)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, self.buf)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, self.sep_buf)
            glUniform1i(uni(self.sep_bin_p, "uCapacity"), CAP)
            glUniform1f(uni(self.sep_bin_p, "uAspect"), aspect)
            glUniform1f(uni(self.sep_bin_p, "uSpacing"), spacing)
            glUniform2i(uni(self.sep_bin_p, "uSep"), self.sep_w, self.sep_h)
            glDispatchCompute((CAP + 63) // 64, 1, 1)
            self._bar()

            glUseProgram(self.sep_push_p)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, self.buf)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, self.sep_buf)
            glUniform1i(uni(self.sep_push_p, "uCapacity"), CAP)
            glUniform1f(uni(self.sep_push_p, "uAspect"), aspect)
            glUniform1f(uni(self.sep_push_p, "uSpacing"), spacing)
            glUniform1f(uni(self.sep_push_p, "uMinDist"), min_dist)
            glUniform2i(uni(self.sep_push_p, "uSep"), self.sep_w, self.sep_h)
            glDispatchCompute((CAP + 63) // 64, 1, 1)
            self._bar()
        return min_dist

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
        self.u.image(0, GL_WRITE_ONLY)
        self.v.image(2, GL_WRITE_ONLY)
        self.density.image(3, GL_WRITE_ONLY)
        glDispatchCompute((self.gw + 7) // 8, (self.gh + 7) // 8, 1)
        self._bar()

    def integrate(self, dt, aspect=1.0):
        glUseProgram(self.integrate_p)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, self.buf)
        glUniform1f(uni(self.integrate_p, "uDt"), dt)
        glUniform1i(uni(self.integrate_p, "uCapacity"), CAP)
        glUniform1f(uni(self.integrate_p, "uAspect"), aspect)
        glDispatchCompute((CAP + 63) // 64, 1, 1)
        self._bar()

    def snapshot(self):
        glUseProgram(self.copy_p)
        self.u.image(0, GL_READ_ONLY)
        self.u_old.image(1, GL_WRITE_ONLY)
        self.v.image(2, GL_READ_ONLY)
        self.v_old.image(3, GL_WRITE_ONLY)
        glUniform2i(uni(self.copy_p, "uGrid"), self.gw, self.gh)
        glDispatchCompute((self.gw + 7) // 8, (self.gh + 7) // 8, 1)
        self._bar()

    def solve(self, iters=40, omega=1.5, rest=50.0, compensate=1.0):
        glUseProgram(self.solve_p)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, self.grid)
        glUniform2i(uni(self.solve_p, "uGrid"), self.gw, self.gh)
        glUniform1f(uni(self.solve_p, "uOmega"), omega)
        glUniform1f(uni(self.solve_p, "uRest"), rest)
        glUniform1f(uni(self.solve_p, "uCompensate"), compensate)
        self.u.image(0, GL_READ_WRITE)
        self.v.image(2, GL_READ_WRITE)
        for _ in range(iters):
            for parity in (0, 1):
                glUniform1i(uni(self.solve_p, "uParity"), parity)
                glDispatchCompute(((self.gw + 1) // 2 + 7) // 8,
                                  (self.gh + 7) // 8, 1)
                glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
        self._bar()

    def g2p(self, dt, flip_ratio=0.95, drag=0.25,
            settle_time=1.0, cohesion=0.0, rest=50.0):
        glUseProgram(self.g2p_p)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, self.buf)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, self.grid)
        glUniform1f(uni(self.g2p_p, "uDt"), dt)
        glUniform1i(uni(self.g2p_p, "uCapacity"), CAP)
        glUniform2i(uni(self.g2p_p, "uGrid"), self.gw, self.gh)
        glUniform1f(uni(self.g2p_p, "uFlipRatio"), flip_ratio)
        glUniform1f(uni(self.g2p_p, "uDrag"), drag)
        # settling is purely a clock on particle age; <= 0 never dries
        glUniform1f(uni(self.g2p_p, "uSettleTime"), settle_time)
        glUniform2f(uni(self.g2p_p, "uTexel"), 1.0 / self.gw, 1.0 / self.gh)
        # same slider-to-speed mapping as FlipSystem.gridToParticles
        glUniform1f(uni(self.g2p_p, "uCohesionSpeed"), cohesion * 0.0025)
        glUniform1f(uni(self.g2p_p, "uRestMass"), rest)
        glUniform1f(uni(self.g2p_p, "uMaxSpeed"), 4.0)
        glUniform1i(uni(self.g2p_p, "uUNew"), 0)
        glUniform1i(uni(self.g2p_p, "uVNew"), 1)
        glUniform1i(uni(self.g2p_p, "uUOld"), 2)
        glUniform1i(uni(self.g2p_p, "uVOld"), 3)
        glUniform1i(uni(self.g2p_p, "uDensity"), 4)
        self.u.sampler(0)
        self.v.sampler(1)
        self.u_old.sampler(2)
        self.v_old.sampler(3)
        self.density.sampler(4)
        glDispatchCompute((CAP + 63) // 64, 1, 1)
        self._bar()

    def step(self, dt, vel_tex=None, flip_ratio=0.95, settle_time=1.0,
             drag=0.25, aspect=1.0, cohesion=0.0, rest=50.0,
             separation=2, iters=40, compensate=1.0):
        # the reference's simulate(): integrate, separate, to grid, solve,
        # from grid. rest is the emission density, particles per cell.
        self.integrate(dt, aspect)
        if separation:
            self.push_apart(aspect=aspect, ppc=rest, iters=separation)
        self.p2g()
        self.snapshot()
        if iters:
            self.solve(iters=iters, rest=rest, compensate=compensate)
        self.g2p(dt, flip_ratio, drag, settle_time, cohesion, rest)

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

    # A moving pour is a stream, not a chain of dots: particles land all along
    # the segment the finger travelled, and each takes the motion vector
    # interpolated to its own spot on the path.
    sf = Flip()
    sf.emit(0.2, 0.5, 0.0, 0.0, n=512, radius=0.01,
            seg=(0.8, 0.5, 2.0, 0.0))
    p = sf.read()
    live = p[p[:, 6] == 1.0]
    xs = live[:, 0]
    thirds = [((xs >= lo) & (xs < hi)).sum()
              for lo, hi in ((0.2, 0.4), (0.4, 0.6), (0.6, 0.8))]
    check("a moving pour streams along the whole segment",
          min(thirds) > 512 * 0.15,
          f"particles per third of the path: {thirds}")
    near = live[xs < 0.4][:, 2]
    far = live[xs > 0.6][:, 2]
    check("and the motion vector is interpolated along it",
          len(near) and len(far) and float(near.mean()) < float(far.mean()) * 0.5,
          f"mean vx {near.mean():.2f} at the start, {far.mean():.2f} at the end")

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
        still.step(dt, settle_time=0.0)
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
        d = f.div_field()
        m = f.mass_field() > 0.2
        interior = (m &
                    np.roll(m, 1, 0) & np.roll(m, -1, 0) &
                    np.roll(m, 1, 1) & np.roll(m, -1, 1))
        return (float(np.sqrt((d[interior] ** 2).mean()))
                if interior.sum() else 0.0), int(interior.sum())

    before, cells = rms_div(conv)
    conv.snapshot()
    conv.solve(iters=40, rest=50.0)
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
            q.step(dt, drag=0.02, settle_time=0.0, flip_ratio=ratio,
                   cohesion=6.0, rest=50.0, iters=60, aspect=2.34)
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
    # (name, cohesion, drag, flip ratio, settle TIME in seconds, density)
    PRESETS = [
        ("Wet Paint", 30.0, 0.25, 0.6, 2.0, 120.0),
        ("Splatter", 6.0, 0.02, 0.99, 1.5, 51.0),
        ("Fling", 8.0, 0.05, 0.97, 1.5, 29.0),
        ("Honey", 26.0, 1.6, 0.45, 2.5, 51.0),
        ("Mercury", 38.0, 0.04, 0.97, 2.5, 61.0),
    ]

    # 200 frames, not 120: Mercury's settle speed of 0.015 makes it the
    # slowest-drying preset by design, and at 120 frames its settled fraction
    # sat within driver variance of the bound (79% local Mesa, under 70% on
    # the CI runner's). The invariant is that every preset DRIES, not that it
    # dries in two seconds.
    def rest_blob(coh, drag, ratio, settle, ppc, frames=200):
        q = Flip()
        q.make_grid(244, 104)
        q.emit(0.5, 0.5, 0.0, 0.0, n=1200, radius=0.03, aspect=2.34)
        for _ in range(frames):
            q.step(dt, flip_ratio=ratio, drag=drag, settle_time=settle,
                   cohesion=coh, rest=ppc, aspect=2.34, iters=40)
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
    # permanent explosion held together by the CFL clamp. The KE bound is 10:
    # the pathology this guards against measured in the thousands, with every
    # particle pinned at the CFL cap, while the equilibrium surface creep that
    # legitimately remains at slider max lands at 0.2-2.3 depending on the
    # Mesa version (0.18 locally, 2.24 on the CI runner) -- a driver-marginal
    # number the check must not flap on.
    ke, frac = rest_blob(200.0, 0.02, 0.99, 0.06, 51.0)
    check("cohesion at slider max stays bounded and still dries",
          frac > 0.6 and ke < 10.0,
          f"{frac * 100:.0f}% settled, residual KE {ke:.2f}")
    print()

    # ---- pouring ----
    #
    # Emission is otherwise per dab and dabs only happen on movement, so a still
    # finger put down nothing at all. A pour runs on the clock instead: hold and
    # a volume builds, drag and its own momentum throws it.
    print("Pour:")

    PER, DENSITY = 96, 51.0

    def poured(frames, throw=False, compensate=1.0):
        q = Flip()
        q.make_grid(244, 104)
        for _ in range(frames):
            q.emit(0.5, 0.5, 0.0, 0.0, n=PER, radius=0.02, aspect=2.34)
            q.step(dt, drag=0.02, settle_time=0.0, flip_ratio=1.0,
                   rest=DENSITY, aspect=2.34, iters=60, compensate=compensate)
        built = frames * PER            # the volume, before anything is thrown
        if throw:
            # a drag across it: dabs carrying momentum, as a stroke makes
            for k in range(8):
                q.emit(0.5 + 0.01 * k, 0.5, 2.2, 0.0, n=PER // 2,
                       radius=0.02, aspect=2.34)
                q.step(dt, drag=0.02, settle_time=0.0, flip_ratio=1.0,
                       rest=DENSITY, aspect=2.34, iters=60,
                       compensate=compensate)
        else:
            for _ in range(8):
                q.step(dt, drag=0.02, settle_time=0.0, flip_ratio=1.0,
                       rest=DENSITY, aspect=2.34, iters=60,
                       compensate=compensate)
        pp = q.read()
        live = pp[:, 6] == 1.0
        m = q.mass_field()
        cells = int((m > 0.2).sum())
        # only the particles that were poured, never the ones the drag added
        orig = np.zeros(len(pp), dtype=bool)
        orig[:built] = True
        vol = pp[orig & live]
        return dict(cells=cells, live=int(live.sum()),
                    peak=float(m.max()),
                    spread=float(vol[:, 0].std()) if len(vol) else 0.0)

    short, long_ = poured(6), poured(24)
    check("holding still keeps putting paint down",
          long_["live"] > short["live"] * 3,
          f"{short['live']} particles after 6 frames, {long_['live']} after 24")

    check("and it spreads into a volume rather than stacking on one spot",
          long_["cells"] > short["cells"] * 2.5,
          f"{short['cells']} cells occupied, then {long_['cells']}")

    # The reference's drift compensation holds a pour AT the emission density
    # rather than blasting it apart: the peak should sit near rest, however
    # much is poured. The old term left the pour at half rest -- bloated.
    check("and holds the pour at rest density",
          long_["peak"] < DENSITY * 1.5,
          f"peak density {long_['peak']:.0f} against rest {DENSITY:.0f}")

    # the term that makes that happen, and what the medium does without it
    packed = poured(24, compensate=0.0)
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
                            "..", "android", "app", "src", "main", "java", "com",
                            "maxpaint", "spike", "FluidSim.kt")).read()
    shipped_res = int(re.search(r"var flipRes = (\d+)", src).group(1))
    flip_src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                 "..", "android", "app", "src", "main", "java", "com",
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
                q.step(dt, drag=0.0, settle_time=0.0, rest=50.0,
                       iters=30 if project else 0, separation=0)
            pp = q.read()
            lv = pp[:, 6] == 1.0
            return float(np.std(pp[lv][:, 1])) if lv.sum() else 0.0

        probe = Flip()
        probe.make_grid(g, g)
        probe.emit(0.5, 0.5, 0.0, 0.0, n=CAP // 2, radius=0.03)
        probe.p2g()
        occupied = int((probe.mass_field() > 0.2).sum())
        off, on = run(False), run(True)
        return (CAP / 2) / max(occupied, 1), on / max(off, 1e-9)

    dense_per_cell, dense = coupling(192)
    check("the solve couples particles when a cell holds several of them",
          dense > 4.0,
          f"{dense_per_cell:.1f} particles per occupied cell, "
          f"streams spread {dense:.1f}x wider with the solve")

    # and the failure this replaced: one particle per cell is a spray of
    # independent points wearing a solver
    # The reference solve narrows the old gap: its air-aware face weights
    # let even one particle per cell feel the field, so the fine grid is no
    # longer a spray of independent points -- but the coarse grid still
    # couples measurably harder, which is what the Coupling slider trades on.
    lone_per_cell, lone = coupling(768)
    check("and couples them less at one particle per cell",
          lone < dense * 0.85,
          f"{lone_per_cell:.1f} per occupied cell gives {lone:.1f}x")

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
        occ = int((q.mass_field() > 0.2).sum())
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
            q.step(dt, drag=0.0, settle_time=0.0, rest=50.0,
                   iters=30 if project else 0, separation=0)
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
    mass = t.mass_field()
    u = t.u.read()[:, :, 0]
    check("particles deposit mass on the grid", float(mass.sum()) > 0,
          f"total mass {mass.sum():.1f}")
    check("particles deposit momentum on the grid",
          float(u.max()) > 0.5,
          f"peak grid vx {u.max():.3f}")
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
            q.step(dt, drag=0.6, settle_time=0.0, cohesion=coh, rest=24.0)
        q.p2g()
        m = q.mass_field()
        p = q.read()
        live = p[:, 6] == 1.0
        c1 = p[live][:, :2].mean(axis=0) if live.sum() else c0
        speeds = np.hypot(p[live][:, 2], p[live][:, 3]) if live.sum() else np.array([0.0])
        total = float(m.sum())
        return dict(cells=int((m > 0.08).sum()), peak=float(m.max()),
                    total=total,
                    condensed=float(m[m >= 0.8 * 24.0].sum()) / max(total, 1e-9),
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

    # The half of the fix that matters: the paint stays bounded and slows.
    # At slider MAX the surface legitimately keeps creeping (the target speed
    # is 0.5 there, and drift compensation answers back), so the bound is 0.1
    # -- measured 0.089 at 120 frames and still falling at 360 -- against the
    # CFL-cap-pinned 4.0 the old force produced. Every shipped preset dries,
    # which the preset checks above prove at their real numbers.
    check("and the beads slow toward rest",
          on["meanv"] < 0.1,
          f"mean live speed {on['meanv']:.4f} after 120 frames")

    # The from-scratch rewrite reset the cohesion baseline: the reference
    # solve's drift compensation holds beads AT rest density instead of
    # letting them pack past it, so the condensed fraction reads lower for
    # the same visual beading. 39% measured at Wet Paint's settings on the
    # new solver; the pin holds that level until the look is re-approved on
    # device.
    wp = gather(30.0)
    check("Wet Paint's cohesion holds its measured level",
          wp["condensed"] > 0.33,
          f"{wp['condensed'] * 100:.0f}% of mass condensed at its settings")

    # It must gather in place. A skewed density field biases the gradient and
    # walks the whole liquid into a corner, which is exactly what the staggered
    # weights did before the cell-centred mass was reconstructed properly.
    check("cohesion gathers in place rather than drifting",
          on["drift"] < 0.05, f"centroid moved {on['drift']:.4f}")
    check("cohesion stays stable", on["finite"] and on["vmax"] <= 4.5,
          f"peak particle speed {on['vmax']:.2f}")
    check("cohesion does not collapse the liquid to a point",
          on["cells"] > 8, f"{on['cells']} cells still occupied")

    # --- separation: the anti-grain pass from the reference solver ---
    print()
    print("Separation:")

    def nn_dist(p):
        live = p[p[:, 6] == 1.0][:, :2]
        if len(live) < 2:
            return 0.0
        d = np.linalg.norm(live[:, None, :] - live[None, :, :], axis=2)
        np.fill_diagonal(d, np.inf)
        return float(np.median(d.min(axis=1)))

    s = Flip()
    s.make_grid(64, 64)
    # overlapped ~3x tighter than rest packing -- the state a fresh dab or a
    # crossing of two strokes leaves particles in
    s.emit(0.5, 0.5, 0.0, 0.0, n=512, radius=0.03)
    before = nn_dist(s.read())
    md = s.push_apart(ppc=24.0, iters=4)
    p_after = s.read()
    after = nn_dist(p_after)
    check("push-apart spreads a clump toward even spacing",
          after > before * 1.5 and after > 0.5 * md,
          f"median nearest-neighbour {before:.5f} -> {after:.5f}, "
          f"rest spacing {md:.5f}")
    check("and keeps the paint where it was put",
          abs(float(p_after[p_after[:, 6] == 1.0][:, :2].mean(axis=0)[0]) - 0.5) < 0.02
          and np.isfinite(p_after).all(),
          "centroid held, values finite")

    # positions move, velocities must not: separation is geometry, not energy
    v_before = s.read()[:, 2:4].copy()
    s.push_apart(ppc=24.0, iters=2)
    v_after = s.read()[:, 2:4]
    check("separation adds no kinetic energy",
          np.array_equal(v_before, v_after), "velocities bit-identical")

    # SOR: the slider bottoms out at 4 sweeps; with overrelaxation even a
    # handful must still make a real dent in the divergence. Measured on the
    # grid field itself -- before projection and after -- not by rebuilding
    # the grid from particles that were never updated.
    def rms_div_of(q):
        # fluid cells only: the free surface legitimately leaves air cells
        # divergent, and the solve does not touch them
        d = q.div_field()
        fluid = q.cell_types() == 2
        return float(np.sqrt((d[fluid] ** 2).mean())) if fluid.sum() else 0.0

    def residual_at(omega):
        q = Flip()
        q.make_grid(64, 64)
        q.emit(0.45, 0.5, 1.0, 0.0, n=1024, radius=0.05)
        q.emit(0.55, 0.5, -1.0, 0.0, n=1024, radius=0.05)
        q.p2g()
        r0 = rms_div_of(q)
        q.snapshot()
        q.solve(iters=8, omega=omega, rest=50.0, compensate=0.0)
        return r0, rms_div_of(q)

    r0, sor = residual_at(1.5)
    _, plain = residual_at(1.0)
    check("overrelaxation makes the same few sweeps cut deeper",
          sor < plain * 0.85 and sor < r0,
          f"rms divergence {r0:.4f} -> {plain:.4f} plain, {sor:.4f} with "
          f"omega 1.5, in 8 sweeps")

    print()
    if FAILURES:
        print(f"{len(FAILURES)} CHECK(S) FAILED: {', '.join(FAILURES)}")
        return 1
    print("All FLIP checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
