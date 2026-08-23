# MaxPaint

Fluid-simulation painting for Android. See [docs/PRD.md](docs/PRD.md) for the product spec.

## M0 — resolution headroom spike

This milestone is the de-risking spike from the PRD: a gaseous 2D Navier–Stokes
solver running entirely in **OpenGL ES 3.1 compute shaders**, with touch
injecting both dye and momentum, and **selectable simulation resolutions** so we
can measure how far real hardware actually goes.

No baking yet — that is M1.

### Resolutions

Selectable from the on-screen panel:

`128² · 256² · 384² · 512² · 768² · 1024² · 1536² · 2048²`

Independently, the **dye grid** can run at 1× or 2× the velocity grid. This is the
cheapest way to buy visual detail: the eye reads dye resolution, not velocity
resolution, so 512² velocity + 1024² dye often looks better than 1024²/1024² and
costs far less. Pressure iterations are adjustable from 5 to 80 — this is the
dominant cost, so it is the first thing to trade.

Reallocation is live; changing resolution does not restart the app.

### The sweep

The **Sweep** button runs every resolution through an identical scripted
figure-eight stroke (20 warm-up + 90 measured frames each) and prints a table:

```
  sim     dye    median     p95     est.fps   vram    60fps
  ----------------------------------------------------------
  512²   512²    4.20ms   5.10ms    238.1    12.6MB   PASS
  1024²  1024²  14.80ms  17.90ms     67.6    50.3MB   PASS
  2048²  2048²  58.10ms  63.20ms     17.2   201.3MB   fail
```

Timing brackets the sim step with `glFinish`, so the numbers are honest GPU cost
rather than pipelined wall-clock — slightly pessimistic, which is the right bias
when sizing a budget. Results are written to the app's external files directory
as `maxpaint-sweep.txt` and can be dumped to logcat under the tag `MaxPaintSweep`.

### Pressure solver

The pressure projection dominates frame cost, so its convergence rate sets the
resolution ceiling. The spike ships **red-black Gauss-Seidel** rather than the
Jacobi solve the PRD assumed, on the strength of a measurement
(`tools/compare_solvers.py`, 128², cold start, divergence removed):

```
  sweeps   Jacobi    RB-Gauss-Seidel   advantage
      5     10.6%            18.4%      +7.7pp
     20     32.1%            48.3%     +16.2pp
     30     41.5%            58.4%     +16.9pp
     60     58.6%            73.9%     +15.2pp
    100     70.3%            82.5%     +12.2pp
```

**RB-GS at 30 sweeps matches Jacobi at 60** — half the work for the same result,
plus one pressure texture instead of two. The comparison is cost-fair: threads
are mapped compactly onto one colour class per dispatch over a half-width grid,
so a Gauss-Seidel sweep launches the same number of threads as a Jacobi
iteration. Toggle between them at runtime with the **RB-GS/Jacobi** button.

#### The resolution ceiling is convergence, not throughput

RB-GS does not remove the PRD's stated risk, it moves it by one resolution step.
Measuring the sweeps needed to clear 50% of divergence
(`python3 tools/compare_solvers.py --scaling`):

```
  grid     sweeps   vs previous   cost for equal quality
    32²        3            -         -
    64²        7          2.3x      9.3x
   128²       22          3.1x     12.6x
   256²       83          3.8x     15.1x
```

Sweeps scale with the **square** of grid width, so cost for equal quality
(cells × sweeps) approaches **16× per doubling**, not the 4× that raw pixel
count suggests. Confirmed directly: 30 sweeps clears 58.4% at 128², and it takes
120 sweeps to clear 59.2% at 256².

This is the single most important M0 result, and it reframes the question "how
high can we go?". Raw framerate is not the binding constraint — *convergence
per frame* is. A 2048² grid will happily run at 60fps with 30 sweeps and produce
visibly worse fluid than 512² does, because the pressure solve never propagates
information across the domain. Three consequences:

1. **The PRD's dye/velocity decoupling is load-bearing, not an optimisation.**
   Fine dye over a coarse, well-converged velocity field beats a high-resolution
   velocity field that is under-solved. Prefer 512² velocity + 1024² dye over
   1024²/1024².
2. **Multigrid moves from "mitigation" to "required"** for anything above ~1024².
   Its convergence is resolution-independent, which is exactly the property that
   iterative sweeps lack. This should be scheduled, not held in reserve.
3. **The sweep benchmark should report convergence alongside fps.** A PASS on
   frame time alone is misleading at high resolution.

In practice the frame loop warm-starts pressure from the previous frame
(decayed 0.8×), so steady-state divergence is better than any single cold solve
and does not accumulate over a run — which the verification asserts.

### Architecture

| File | Role |
|---|---|
| `assets/shaders/*.comp` | The solver: advect, splat, curl, vorticity, divergence, pressure, gradsub |
| `GLUtil.kt` | Shader/program compilation, `Tex` and `DoubleTex` (ping-pong) wrappers |
| `FluidSim.kt` | Field allocation and the per-frame pass sequence |
| `Benchmark.kt` | The scripted resolution sweep |
| `tools/verify.sh` | Shader + solver verification with no Android SDK |
| `FluidRenderer.kt` | GL thread, input queue, display pass, live stats |
| `MainActivity.kt` | Touch handling and the control panel |

Velocity is stored in **normalised-UV units per second**, which makes advection
resolution-independent and lets the dye grid differ from the velocity grid for
free.

Two ES-specific constraints shaped the design, and are worth remembering before
editing the shaders:

1. **ES 3.1 only allows read-write images for `r32f`/`r32i`/`r32ui`.** Every
   `rgba16f` field (velocity, dye) must therefore ping-pong through a separate
   readonly source and writeonly destination. Only the pressure warm-start
   decay, which is `r32f`, does an in-place read-modify-write.
2. **`R32F` is not filterable on ES** without `OES_texture_float_linear`, so
   pressure/curl/divergence are accessed purely via `imageLoad`, while velocity
   and dye use `sampler2D` for bilinear semi-Lagrangian backtracing.

## Building

Requires the Android SDK (compileSdk 34) and JDK 17+.

```
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
```

> **No APK has been produced yet.** The session this was developed in has
> `dl.google.com` blocked by egress policy, and that host serves both the Android
> SDK and the Android Gradle Plugin (`maven.google.com` redirects to it). Resource
> and manifest compilation needs `aapt2`, which is only published there, so the
> packaging step cannot run here. Everything short of packaging has been verified
> against real toolchains — see below.

## Verification

Packaging aside, the two things most likely to be wrong — the GLSL and the solver
logic — are checked against real implementations rather than by inspection.

```
tools/verify.sh              # shaders + solver, needs glslangValidator + Mesa + PyOpenGL
python3 tools/compare_solvers.py 128
```

| Layer | How | Status |
|---|---|---|
| GLSL, all 11 shaders | `glslangValidator` against the ES 3.1 spec | passing |
| Solver behaviour | Executed on a real GLES 3.1 driver (EGL surfaceless + Mesa llvmpipe) | 12/12 checks passing |
| Kotlin, all 5 sources | `kotlinc` against the real Android 14 framework classes (`org.robolectric:android-all`, from Maven Central) | compiles clean, 23 classes |
| Resource + manifest packaging | `aapt2` | **blocked** — Google Maven unreachable |

`tools/verify_solver.py` creates a genuine ES 3.1 context and runs the same pass
sequence as `FluidSim.kt`, asserting that the physics is right rather than merely
that the code parses: dye lands where it was splatted, projection reduces
divergence, more sweeps converge further, dye is transported along the injected
momentum, nothing goes non-finite, no flow crosses the walls, velocity decays
under drag (the mechanism M1's bake hangs off), and identical inputs produce
bit-identical output (PRD FR-20).

Two bugs were caught this way that inspection had missed:

1. **Illegal read-write images.** ES 3.1 permits read-write image qualifiers only
   for `r32f`/`r32i`/`r32ui`. The `rgba16f` velocity and dye fields were doing
   in-place read-modify-write in the splat, vorticity and gradient-subtract
   passes. They now ping-pong. This would have failed shader compilation on
   device with a driver-specific message.
2. **A slow pressure solve**, quantified rather than suspected — see above.

To reproduce the toolchain on a machine that can reach Google's servers, just use
Gradle normally; the harness above is only needed where the SDK is unavailable.
