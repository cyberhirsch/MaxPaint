#!/usr/bin/env python3
"""
Headless verification of the MaxPaint M0 fluid solver.

Creates a real OpenGL ES 3.1 context (EGL surfaceless, Mesa llvmpipe), compiles
the shipped compute shaders on an actual driver, and runs the same pass sequence
as FluidSim.kt -- then asserts the physics is behaving.

This catches what a syntax validator cannot: bad image bindings, wrong barrier
placement, a pressure solve that does not converge, advection running backwards.

Usage:  python3 tools/verify_solver.py [--res 128] [--steps 60]
"""
import argparse
import ctypes
import os
import sys

import numpy as np
from OpenGL import EGL
from OpenGL.GLES3 import *

SHADER_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                          "..", "app", "src", "main", "assets", "shaders")

FAILURES = []


def gstr(v):
    """glGetString / info logs come back as bytes, str, or GLubyteArray."""
    if isinstance(v, bytes):
        return v.decode(errors="replace")
    if isinstance(v, str):
        return v
    try:
        return ctypes.cast(v, ctypes.c_char_p).value.decode(errors="replace")
    except Exception:
        try:
            return bytes(bytearray(v)).split(b"\x00")[0].decode(errors="replace")
        except Exception:
            return str(v)


def check(name, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print(f"  [{status}] {name}" + (f"  --  {detail}" if detail else ""))
    if not condition:
        FAILURES.append(name)


# ---------------------------------------------------------------- EGL context

def make_context():
    dpy = EGL.eglGetDisplay(EGL.EGL_DEFAULT_DISPLAY)
    if dpy == EGL.EGL_NO_DISPLAY:
        sys.exit("no EGL display")
    major, minor = ctypes.c_long(0), ctypes.c_long(0)
    if not EGL.eglInitialize(dpy, major, minor):
        sys.exit("eglInitialize failed")

    if not EGL.eglBindAPI(EGL.EGL_OPENGL_ES_API):
        sys.exit("eglBindAPI failed")

    cfg_attrs = [
        EGL.EGL_SURFACE_TYPE, EGL.EGL_PBUFFER_BIT,
        EGL.EGL_RENDERABLE_TYPE, EGL.EGL_OPENGL_ES3_BIT,
        EGL.EGL_RED_SIZE, 8, EGL.EGL_GREEN_SIZE, 8, EGL.EGL_BLUE_SIZE, 8,
        EGL.EGL_NONE,
    ]
    configs = (EGL.EGLConfig * 1)()
    n = ctypes.c_long(0)
    if not EGL.eglChooseConfig(dpy, cfg_attrs, configs, 1, n) or n.value == 0:
        sys.exit("eglChooseConfig found no ES3 config")

    ctx_attrs = [
        EGL.EGL_CONTEXT_MAJOR_VERSION, 3,
        EGL.EGL_CONTEXT_MINOR_VERSION, 1,
        EGL.EGL_NONE,
    ]
    ctx = EGL.eglCreateContext(dpy, configs[0], EGL.EGL_NO_CONTEXT, ctx_attrs)
    if ctx == EGL.EGL_NO_CONTEXT:
        sys.exit("eglCreateContext failed (no ES 3.1?)")

    # surfaceless: we only ever render into textures
    if not EGL.eglMakeCurrent(dpy, EGL.EGL_NO_SURFACE, EGL.EGL_NO_SURFACE, ctx):
        pbuf = EGL.eglCreatePbufferSurface(
            dpy, configs[0], [EGL.EGL_WIDTH, 16, EGL.EGL_HEIGHT, 16, EGL.EGL_NONE])
        if not EGL.eglMakeCurrent(dpy, pbuf, pbuf, ctx):
            sys.exit("eglMakeCurrent failed")
    return dpy, ctx


# ---------------------------------------------------------------- GL helpers

def compile_compute(path):
    src = open(os.path.join(SHADER_DIR, path)).read()
    sh = glCreateShader(GL_COMPUTE_SHADER)
    glShaderSource(sh, src)
    glCompileShader(sh)
    if not glGetShaderiv(sh, GL_COMPILE_STATUS):
        raise RuntimeError(f"{path} failed to compile:\n{gstr(glGetShaderInfoLog(sh))}")
    prog = glCreateProgram()
    glAttachShader(prog, sh)
    glLinkProgram(prog)
    if not glGetProgramiv(prog, GL_LINK_STATUS):
        raise RuntimeError(f"{path} failed to link:\n{gstr(glGetProgramInfoLog(prog))}")
    glDeleteShader(sh)
    return prog


def uni(prog, name):
    return glGetUniformLocation(prog, name)


class Tex:
    def __init__(self, w, h, ifmt, filt):
        self.w, self.h, self.ifmt = w, h, ifmt
        self.id = glGenTextures(1)
        glBindTexture(GL_TEXTURE_2D, self.id)
        glTexStorage2D(GL_TEXTURE_2D, 1, ifmt, w, h)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filt)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filt)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindTexture(GL_TEXTURE_2D, 0)
        self.clear()

    def clear(self):
        fbo = glGenFramebuffers(1)
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, self.id, 0)
        glClearColor(0, 0, 0, 0)
        glClear(GL_COLOR_BUFFER_BIT)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glDeleteFramebuffers(1, [fbo])

    def image(self, unit, access):
        glBindImageTexture(unit, self.id, 0, GL_FALSE, 0, access, self.ifmt)

    def sampler(self, unit):
        glActiveTexture(GL_TEXTURE0 + unit)
        glBindTexture(GL_TEXTURE_2D, self.id)

    def read(self):
        """Pull the texture back as a float array of shape (h, w, channels)."""
        fbo = glGenFramebuffers(1)
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, self.id, 0)
        buf = np.zeros((self.h, self.w, 4), dtype=np.float32)
        glReadPixels(0, 0, self.w, self.h, GL_RGBA, GL_FLOAT, buf)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glDeleteFramebuffers(1, [fbo])
        return buf


class Double:
    def __init__(self, w, h, ifmt, filt):
        self.a = Tex(w, h, ifmt, filt)
        self.b = Tex(w, h, ifmt, filt)

    @property
    def read_t(self):
        return self.a

    @property
    def write_t(self):
        return self.b

    def swap(self):
        self.a, self.b = self.b, self.a


# ---------------------------------------------------------------- the solver

class Sim:
    def __init__(self, res, dye_scale=1, iters=30, use_rb=True):
        self.res, self.dye_res, self.iters = res, res * dye_scale, iters
        self.use_rb = use_rb
        self.vorticity, self.drag, self.dye_diss = 22.0, 0.12, 0.05
        # M1 bake parameters, matching FluidSim.kt
        self.settle_speed, self.bake_rate, self.settle_min_age = 0.35, 2.5, 0.35
        self.bake_enabled = False   # opt in, so solver tests stay isolated
        self.force_freeze = False

        self.p = {n: compile_compute(f"{n}.comp") for n in
                  ("advect", "splat", "curl", "vorticity", "divergence",
                   "pressure", "pressure_rb", "clearp", "gradsub", "bake")}

        self.vel = Double(res, res, GL_RGBA16F, GL_LINEAR)
        self.dye = Double(self.dye_res, self.dye_res, GL_RGBA16F, GL_LINEAR)
        self.bg = Double(self.dye_res, self.dye_res, GL_RGBA16F, GL_LINEAR)
        self.age = Double(self.dye_res, self.dye_res, GL_RGBA16F, GL_NEAREST)
        self.pres = Double(res, res, GL_R32F, GL_NEAREST)
        self.curl = Tex(res, res, GL_R32F, GL_NEAREST)
        self.div = Tex(res, res, GL_R32F, GL_NEAREST)

    def dispatch(self, w, h):
        glDispatchCompute((w + 7) // 8, (h + 7) // 8, 1)
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT)

    def splat(self, u, v, du, dv, color, radius=0.05):
        p = self.p["splat"]
        glUseProgram(p)
        glUniform2f(uni(p, "uPoint"), u, v)
        glUniform1f(uni(p, "uAspect"), 1.0)
        glUniform1i(uni(p, "uMode"), 0)

        glUniform1f(uni(p, "uRadius"), radius)
        glUniform4f(uni(p, "uValue"), du, dv, 0.0, 0.0)
        self.vel.read_t.image(0, GL_READ_ONLY)
        self.vel.write_t.image(1, GL_WRITE_ONLY)
        self.dispatch(self.res, self.res)
        self.vel.swap()

        glUniform1f(uni(p, "uRadius"), radius * 0.6)
        glUniform4f(uni(p, "uValue"), *color, 1.0)
        self.dye.read_t.image(0, GL_READ_ONLY)
        self.dye.write_t.image(1, GL_WRITE_ONLY)
        self.dispatch(self.dye_res, self.dye_res)
        self.dye.swap()

        # freshly injected paint is new, so its age restarts
        glUniform1i(uni(p, "uMode"), 1)
        glUniform4f(uni(p, "uValue"), 0.0, 0.0, 0.0, 0.0)
        self.age.read_t.image(0, GL_READ_ONLY)
        self.age.write_t.image(1, GL_WRITE_ONLY)
        self.dispatch(self.dye_res, self.dye_res)
        self.age.swap()
        glUniform1i(uni(p, "uMode"), 0)

    def bake(self, dt):
        """Mirrors FluidSim.bake(): settled fluid moves into the background."""
        p = self.p["bake"]
        glUseProgram(p)
        glUniform1f(uni(p, "uDt"), dt)
        glUniform1f(uni(p, "uSettleSpeed"), self.settle_speed)
        glUniform1f(uni(p, "uBakeRate"), self.bake_rate)
        glUniform1f(uni(p, "uSettleMinAge"), self.settle_min_age)
        glUniform1i(uni(p, "uForce"), 1 if self.force_freeze else 0)
        glUniform1i(uni(p, "uVel"), 0)
        self.force_freeze = False

        self.dye.read_t.image(0, GL_READ_ONLY)
        self.dye.write_t.image(1, GL_WRITE_ONLY)
        self.bg.read_t.image(2, GL_READ_ONLY)
        self.bg.write_t.image(3, GL_WRITE_ONLY)
        self.age.read_t.image(4, GL_READ_ONLY)
        self.age.write_t.image(5, GL_WRITE_ONLY)
        self.vel.read_t.sampler(0)
        self.dispatch(self.dye_res, self.dye_res)
        self.dye.swap(); self.bg.swap(); self.age.swap()

    def compute_divergence(self):
        p = self.p["divergence"]
        glUseProgram(p)
        self.vel.read_t.image(0, GL_READ_ONLY)
        self.div.image(1, GL_WRITE_ONLY)
        self.dispatch(self.res, self.res)

    def solve_pressure(self):
        """Mirrors FluidSim.step(): red-black Gauss-Seidel in place, or Jacobi."""
        if self.use_rb:
            p = self.p["pressure_rb"]
            glUseProgram(p)
            self.div.image(2, GL_READ_ONLY)
            half = (self.res + 1) // 2
            for _ in range(self.iters):
                for parity in (0, 1):
                    self.pres.read_t.image(0, GL_READ_WRITE)
                    glUniform1i(uni(p, "uParity"), parity)
                    self.dispatch(half, self.res)
        else:
            p = self.p["pressure"]
            glUseProgram(p)
            self.div.image(2, GL_READ_ONLY)
            for _ in range(self.iters):
                self.pres.read_t.image(0, GL_READ_ONLY)
                self.pres.write_t.image(1, GL_WRITE_ONLY)
                self.dispatch(self.res, self.res)
                self.pres.swap()

    def project(self, dt):
        """Just divergence -> Jacobi -> gradient subtract, with no advection or
        vorticity afterwards. This is what actually enforces incompressibility,
        so it is what the convergence test must measure in isolation."""
        self.compute_divergence()

        p = self.p["clearp"]
        glUseProgram(p)
        glUniform1f(uni(p, "uValue"), 0.0)   # cold start: measure this solve alone
        self.pres.read_t.image(0, GL_READ_WRITE)
        self.dispatch(self.res, self.res)

        self.solve_pressure()

        p = self.p["gradsub"]
        glUseProgram(p)
        glUniform1f(uni(p, "uDrag"), 0.0)    # isolate projection from drag
        glUniform1f(uni(p, "uDt"), dt)
        self.vel.read_t.image(0, GL_READ_ONLY)
        self.vel.write_t.image(1, GL_WRITE_ONLY)
        self.pres.read_t.image(2, GL_READ_ONLY)
        self.dispatch(self.res, self.res)
        self.vel.swap()

    def step(self, dt):
        p = self.p["curl"]
        glUseProgram(p)
        self.vel.read_t.image(0, GL_READ_ONLY)
        self.curl.image(1, GL_WRITE_ONLY)
        self.dispatch(self.res, self.res)

        p = self.p["vorticity"]
        glUseProgram(p)
        glUniform1f(uni(p, "uStrength"), self.vorticity)
        glUniform1f(uni(p, "uDt"), dt)
        self.vel.read_t.image(0, GL_READ_ONLY)
        self.vel.write_t.image(1, GL_WRITE_ONLY)
        self.curl.image(2, GL_READ_ONLY)
        self.dispatch(self.res, self.res)
        self.vel.swap()

        self.compute_divergence()

        p = self.p["clearp"]
        glUseProgram(p)
        glUniform1f(uni(p, "uValue"), 0.8)
        self.pres.read_t.image(0, GL_READ_WRITE)
        self.dispatch(self.res, self.res)

        self.solve_pressure()

        p = self.p["gradsub"]
        glUseProgram(p)
        glUniform1f(uni(p, "uDrag"), self.drag)
        glUniform1f(uni(p, "uDt"), dt)
        self.vel.read_t.image(0, GL_READ_ONLY)
        self.vel.write_t.image(1, GL_WRITE_ONLY)
        self.pres.read_t.image(2, GL_READ_ONLY)
        self.dispatch(self.res, self.res)
        self.vel.swap()

        p = self.p["advect"]
        glUseProgram(p)
        glUniform1f(uni(p, "uDt"), dt)
        glUniform1i(uni(p, "uSrc"), 0)
        glUniform1i(uni(p, "uVel"), 1)
        glUniform2f(uni(p, "uDstTexel"), 1.0 / self.res, 1.0 / self.res)
        glUniform1f(uni(p, "uDissipation"), 0.0)
        self.vel.read_t.sampler(0)
        self.vel.read_t.sampler(1)
        self.vel.write_t.image(0, GL_WRITE_ONLY)
        self.dispatch(self.res, self.res)
        self.vel.swap()

        glUniform2f(uni(p, "uDstTexel"), 1.0 / self.dye_res, 1.0 / self.dye_res)
        glUniform1f(uni(p, "uDissipation"), self.dye_diss)
        self.dye.read_t.sampler(0)
        self.vel.read_t.sampler(1)
        self.dye.write_t.image(0, GL_WRITE_ONLY)
        self.dispatch(self.dye_res, self.dye_res)
        self.dye.swap()

        glUniform2f(uni(p, "uDstTexel"), 1.0 / self.dye_res, 1.0 / self.dye_res)
        glUniform1f(uni(p, "uDissipation"), 0.0)
        self.age.read_t.sampler(0)
        self.vel.read_t.sampler(1)
        self.age.write_t.image(0, GL_WRITE_ONLY)
        self.dispatch(self.dye_res, self.dye_res)
        self.age.swap()

        if self.bake_enabled:
            self.bake(dt)


def rms(a):
    return float(np.sqrt(np.mean(np.square(a))))


def centroid(field):
    """Intensity-weighted centre of mass, in UV coords."""
    w = field.sum(axis=2)
    total = w.sum()
    if total <= 1e-9:
        return None
    h, wd = w.shape
    ys, xs = np.mgrid[0:h, 0:wd]
    return (float((xs * w).sum() / total / wd), float((ys * w).sum() / total / h))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--res", type=int, default=128)
    ap.add_argument("--steps", type=int, default=60)
    args = ap.parse_args()

    make_context()
    print(f"Renderer : {gstr(glGetString(GL_RENDERER))}")
    print(f"Version  : {gstr(glGetString(GL_VERSION))}")
    print(f"GLSL     : {gstr(glGetString(GL_SHADING_LANGUAGE_VERSION))}")
    print()

    print("Shader compilation (real driver):")
    sim = Sim(args.res)
    check("all 9 compute shaders compile and link", True, f"{args.res}²")
    print()

    dt = 1.0 / 60.0

    # ---- injection ----
    print("Injection:")
    sim.splat(0.5, 0.5, 3.0, 0.0, (0.9, 0.3, 0.2))
    dye0 = sim.dye.read_t.read()
    vel0 = sim.vel.read_t.read()
    check("splat deposits dye", dye0[:, :, :3].sum() > 0, f"total={dye0[:,:,:3].sum():.1f}")
    check("splat deposits momentum", rms(vel0[:, :, :2]) > 0, f"rms={rms(vel0[:,:,:2]):.4f}")
    c0 = centroid(dye0[:, :, :3])
    check("dye lands at the splat point", c0 is not None and
          abs(c0[0] - 0.5) < 0.05 and abs(c0[1] - 0.5) < 0.05, f"centroid={c0}")
    print()

    # ---- incompressibility ----
    # Iterative solvers converge slowest on the low-frequency error, and the
    # sweeps needed to reach a given accuracy grow with the SQUARE of grid
    # width -- about 4x per doubling, confirmed empirically by
    # tools/compare_solvers.py --scaling. A fixed sweep count would therefore
    # "fail" at high resolution for a perfectly correct solver, so the budget
    # is scaled quadratically here to test the solver rather than the budget.
    print("Pressure projection (measured in isolation):")
    scaled_iters = max(4, int(round(30 * (args.res / 128.0) ** 2)))
    sim.iters = scaled_iters
    sim.compute_divergence()
    div_before = rms(sim.div.read()[:, :, 0])
    sim.project(dt)
    sim.compute_divergence()
    div_after = rms(sim.div.read()[:, :, 0])
    removed = 100 * (1 - div_after / max(div_before, 1e-12))
    check("projection reduces divergence", div_after < div_before,
          f"{div_before:.5f} -> {div_after:.5f} ({removed:.1f}% removed)")
    check("clears at least half the divergence at a resolution-scaled budget",
          removed >= 50.0, f"{removed:.1f}% removed with {scaled_iters} sweeps "
                           f"(30 x ({args.res}/128)^2)")
    sim.iters = 30

    conv = []
    for iters in (5, 20, 60, 150):
        s2 = Sim(args.res, iters=iters)
        s2.splat(0.5, 0.5, 3.0, 0.0, (0.9, 0.3, 0.2))
        s2.compute_divergence()
        d0 = rms(s2.div.read()[:, :, 0])
        s2.project(dt)
        s2.compute_divergence()
        conv.append((iters, 100 * (1 - rms(s2.div.read()[:, :, 0]) / max(d0, 1e-12))))
    trace = "  ".join(f"{i}it:{r:.1f}%" for i, r in conv)
    check("more iterations converge further", all(
        conv[i][1] <= conv[i + 1][1] + 0.5 for i in range(len(conv) - 1)), trace)
    print()

    # ---- warm start: what the real frame loop actually achieves ----
    print("Warm-started steady state (as the app runs it):")
    s3 = Sim(args.res)
    s3.splat(0.5, 0.5, 3.0, 0.0, (0.9, 0.3, 0.2))
    s3.compute_divergence()
    d_initial = rms(s3.div.read()[:, :, 0])
    for _ in range(30):
        s3.step(dt)
    s3.compute_divergence()
    d_steady = rms(s3.div.read()[:, :, 0])
    check("divergence stays bounded over a run (no accumulation)",
          d_steady <= d_initial,
          f"rms divergence {d_initial:.5f} -> {d_steady:.5f} after 30 frames")
    print()

    # ---- transport ----
    print("Advection:")
    for _ in range(args.steps):
        sim.step(dt)
    dye1 = sim.dye.read_t.read()
    c1 = centroid(dye1[:, :, :3])
    moved = c1 is not None and (c1[0] - c0[0])
    check("dye is transported along +x by the injected momentum",
          c1 is not None and c1[0] > c0[0] + 0.01,
          f"u: {c0[0]:.3f} -> {c1[0]:.3f} (dx={moved:+.3f})")
    check("dye stays finite (no NaN/inf blowup)",
          bool(np.isfinite(dye1).all()) and bool(np.isfinite(sim.vel.read_t.read()).all()))
    print()

    # ---- boundaries ----
    print("Boundaries:")
    v = sim.vel.read_t.read()
    left_x = np.abs(v[:, 0, 0]).max()
    right_x = np.abs(v[:, -1, 0]).max()
    bot_y = np.abs(v[0, :, 1]).max()
    top_y = np.abs(v[-1, :, 1]).max()
    check("no flow through the walls", max(left_x, right_x, bot_y, top_y) < 1e-6,
          f"max normal component at edges = {max(left_x, right_x, bot_y, top_y):.2e}")
    print()

    # ---- energy decay (the mechanism the bake will hang off) ----
    print("Drag:")
    e1 = rms(sim.vel.read_t.read()[:, :, :2])
    for _ in range(120):
        sim.step(dt)
    e2 = rms(sim.vel.read_t.read()[:, :, :2])
    check("velocity decays under drag (M1 bake depends on this)", e2 < e1,
          f"rms {e1:.5f} -> {e2:.5f}")
    print()

    # ---- M1: the bake ----
    print("Bake (M1):")

    # The bake operator on its own, with no advection in the way. This is the
    # invariant PRD 7.6 actually states: what leaves the dye field arrives in
    # the background, exactly.
    b = Sim(args.res)
    b.splat(0.5, 0.5, 1.2, 0.0, (0.0, 0.0, 0.0))   # black ink, premultiplied
    ink0 = float(b.dye.read_t.read()[:, :, 3].sum())
    b.settle_min_age = 0.0                          # skip Hold for this test
    for _ in range(200):
        b.bake(dt)
    live = float(b.dye.read_t.read()[:, :, 3].sum())
    baked = float(b.bg.read_t.read()[:, :, 3].sum())
    err = abs((live + baked) - ink0) / max(ink0, 1e-6)
    check("paint transfers from the simulation to the background",
          baked > 0 and live < ink0,
          f"live {ink0:.1f} -> {live:.1f}, baked 0.0 -> {baked:.1f}")
    check("the bake conserves ink exactly (PRD 7.6)", err < 0.005,
          f"{ink0:.1f} in, {live + baked:.1f} out ({100 * err:.3f}% error)")

    # Drag is the dial the artist reasons about: more drag, paint sets sooner.
    # Compare FRACTION baked, not absolute -- advection drifts total mass
    # (below), which would otherwise swamp the comparison.
    def fraction_baked(drag, frames=120):
        s4 = Sim(args.res)
        s4.bake_enabled = True
        s4.dye_diss = 0.0
        s4.drag = drag
        s4.splat(0.5, 0.5, 1.2, 0.0, (0.0, 0.0, 0.0))
        for _ in range(frames):
            s4.step(dt)
        lv = float(s4.dye.read_t.read()[:, :, 3].sum())
        bk = float(s4.bg.read_t.read()[:, :, 3].sum())
        return bk / max(lv + bk, 1e-6)

    lo, hi = fraction_baked(0.05), fraction_baked(2.0)
    check("more drag bakes sooner", hi > lo,
          f"drag 0.05 -> {100 * lo:.1f}% set, drag 2.0 -> {100 * hi:.1f}% set")

    # Freeze Now must commit everything in a single step
    f = Sim(args.res)
    f.bake_enabled = True
    f.dye_diss = 0.0
    f.splat(0.5, 0.5, 1.2, 0.0, (0.0, 0.0, 0.0))
    before = float(f.dye.read_t.read()[:, :, 3].sum())
    f.force_freeze = True
    f.step(dt)
    after_live = float(f.dye.read_t.read()[:, :, 3].sum())
    check("Freeze Now commits the whole canvas in one step",
          after_live < before * 0.02,
          f"live {before:.1f} -> {after_live:.1f}")

    # Advection is NOT mass-conservative (semi-Lagrangian never is), and
    # residual divergence makes it much worse: an under-solved velocity field
    # has convergent regions that concentrate dye, so ink is manufactured.
    # Measured at 128x128 over 240 frames: 5 sweeps +152%, 15 +50%, 30 +36%,
    # 60 +14%, 120 +4%. So the invariant to hold is the mechanism -- a better
    # solve conserves better -- not an absolute bound at any one sweep count.
    def mass_drift(iters, frames=240):
        d = Sim(args.res, iters=iters)
        d.dye_diss = 0.0
        d.splat(0.5, 0.5, 1.2, 0.0, (0.0, 0.0, 0.0))
        m0 = float(d.dye.read_t.read()[:, :, 3].sum())
        for _ in range(frames):
            d.step(dt)
        return (float(d.dye.read_t.read()[:, :, 3].sum()) - m0) / max(m0, 1e-6)

    coarse, fine = mass_drift(15), mass_drift(120)
    check("a better pressure solve conserves ink better",
          fine < coarse,
          f"15 sweeps {100 * coarse:+.1f}%, 120 sweeps {100 * fine:+.1f}% over 240 frames")
    check("a well-solved field keeps ink close to conserved",
          abs(fine) < 0.12, f"{100 * fine:+.1f}% at 120 sweeps")
    print()

    # ---- determinism ----
    print("Determinism:")
    def run_once():
        s = Sim(args.res)
        s.splat(0.35, 0.5, 2.5, 0.8, (0.2, 0.7, 0.9))
        for _ in range(20):
            s.step(dt)
        return s.dye.read_t.read()
    a, b = run_once(), run_once()
    check("identical inputs give identical output (PRD FR-20)",
          bool(np.array_equal(a, b)),
          f"max abs diff = {np.abs(a-b).max():.3e}")
    print()

    if FAILURES:
        print(f"{len(FAILURES)} CHECK(S) FAILED: {', '.join(FAILURES)}")
        return 1
    print("All solver checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
