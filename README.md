# MaxPaint

Fluid-simulation painting for Android. See [docs/PRD.md](docs/PRD.md) for the product spec.

## Where it is

**M0** — gaseous 2D Navier–Stokes in ES 3.1 compute, selectable resolutions, sweep benchmark. Done.
**M1** — the bake: black ink on white paper, background layer, settle/transfer, Freeze Now, drag. Done.

## M1 — the bake

Paint is black ink on white paper. A stroke injects dye and momentum; drag slows
it; once it falls below the settle speed it transfers out of the simulation and
into the background layer, where it is permanent and costs nothing to keep.

| Control | What it does |
|---|---|
| **Drag** | The one dial that matters. More drag, paint sets sooner. Measured: at 0.05 a stroke is 80% set after 2s, at 2.0 it is 98% set. |
| **Freeze** button, or a two-finger tap | Commits the whole canvas immediately. |
| **Heat** | Tints live fluid by how close it is to setting, so you can see what is about to freeze (PRD UX-3). |

Ink is premultiplied by coverage, so compositing paper → baked → live is a plain
"over". The bake accumulates additively rather than compositing over, which is
what makes it exactly conservative: what leaves the dye field is what arrives in
the background. Compositing "over" there would saturate, and repeatedly laying
down a tenth of a stroke would converge on full coverage instead of the stroke's
real density — quietly destroying ink.

## Versioning

Versions come from git, so every APK is identifiable and `versionCode` only ever
increases:

```
versionCode   commit count
versionName   0.<minor>.<count>-<sha>[-dirty]     e.g. 0.3.14-92a0a5d
```

The APK is named for its version (`maxpaint-0.3.14-92a0a5d-debug.apk`) and the CI
artifact takes the same name, so downloaded builds neither collide in your
downloads folder nor look identical in the Actions UI. The running version is
shown in the on-screen HUD.

CI checks out with `fetch-depth: 0` — a shallow clone reports one commit, and
every build would claim `versionCode` 1.

## What is and is not built

Built: the gas/FLIP/watercolor/vortex/solvent/freeze/thaw media, presets over
each, the bake and its four dials, the resolution sweep with quality columns,
stylus pressure and tilt, and tilt-driven gravity.

Layers are deliberately out — one canvas, one background layer. The rest of
milestone M3, the app around the paint, is not built:

| Missing | PRD |
|---|---|
| Undo / redo | FR-17, FR-18 |
| Export (PNG, JPEG, `.maxpaint`) | FR-4 |
| Time-lapse export | FR-5 |
| Replay log, and the hi-res re-render it enables | FR-19 – FR-21 |
| Canvas pan / zoom / rotate, 3-finger undo | FR-7 |
| Colour: palettes, eyedropper, pigment mixing | FR-10 – FR-12 |
| Boundary conditions, including wrap for tiling | FR-13, FR-15 |
| Tap-to-hide UI, onboarding, accessibility | UX-1, UX-4, UX-5 |

Determinism is *verified* (identical inputs give bit-identical output) but the
replay log that would exploit it is not written yet.

## The canvas grid is not square

The simulation grid takes the shape of the canvas. Picking a resolution N sets a
*cell budget*, not a side length:

```
simW = N·√aspect      simH = N/√aspect
```

which keeps cells square in world space (`dx == dy`, so the pressure stencil
stays isotropic) while holding the cell count near N², so a given resolution
costs about what it always did. At 2.2:1 a budget of 128 becomes a 188×86 grid —
16168 cells against 16384 nominal.

This required velocity to be stored in **world** units rather than UV, where the
canvas spans x ∈ [0, aspect] and y ∈ [0, 1]. In UV units a diagonal stroke on a
stretched grid curves, because a UV step means a different distance on each axis.
The verification asserts isotropy directly: equal world velocity components must
produce equal world displacement, and they now agree to within 0.2%.

## Brushes

A brush is a *medium* — which solver it drives and how it deposits paint — plus
parameters over it. The force-only brushes carry no pigment at all; they exist to
reshape what is already on the canvas, which is what makes marbling a workflow
rather than an accident.

| Brush | Deposits | What it does |
|---|---|---|
| **Gas** | ink + momentum | The hero brush. Ink-in-water bloom and curl. |
| **Nib** | hard-edged ink | A pen. Writes to its own field so the fluid never smears it, and creeps into the paper by capillary action — hold still and the mark blooms. |
| **Flip** | particles | Paint that pours, drips and runs down the canvas, then dries where it pools. One slider goes splashy to viscous. Device tilt can steer gravity. |
| **Water** | water + pigment | Shallow-water pigment on paper: bleeds, blooms, darkens at the edges, granulates on the paper grain. |
| **Vortex** | momentum only | Swirl, push, pinch, or comb. Comb is a marbling rake. |
| **Solvent** | lifts ink | Scales pigment down where it bites and drives the rest outward — the alcohol-drop halo, not an erase. |
| **Freeze** | — | Local Freeze Now: bakes only what it touches, so one good vortex can be locked while the rest keeps moving. |
| **Thaw** | — | The inverse: lifts baked paint back into the simulation to be restirred. |

### Two measured findings from building them

**A force brush must scale by dt.** Without it the brush adds its full strength
every frame, velocity accumulates without bound, and the semi-Lagrangian
backtrace walks off the grid — the first version multiplied total ink by 130×.
There is now a CFL speed clamp as a second line of defence.

**MacCormack advection is the wrong upgrade here, and the numbers say so.**
Second-order advection is the standard fix for smeared fluid, so it went in —
and then measured worse. Over 240 frames after one flick of momentum:

```
  sweeps    plain   MacCormack        peak density (sharpness)
      15     1.50x        2.28x         plain       0.1725
      30     1.36x        1.88x         MacCormack  0.1783
      60     1.14x        1.78x
     120     1.04x        1.63x
```

It is ~3% sharper and ~50% worse at conserving ink, because reducing numerical
diffusion sharpens the dye peaks and the mass-duplication artefact under shear
is driven by peak sampling. For a paint app, conserving ink beats a marginally
crisper edge, so it ships off, behind a toggle.

That artefact is a real remaining limitation: sustained hard stirring still
inflates total ink (~1.5× for 30 frames of swirl at the default strength). It is
*not* the pressure solve — it plateaus at ~3.2× even at 240 sweeps, and pure
advection with no forcing conserves exactly. The fix is a conservative or
mass-renormalised advection step, which belongs with the M4 quality work.

### Watercolor

A second solver, not Navier–Stokes, after Curtis et al. (SIGGRAPH '97). Water
runs down the gradient of depth plus paper grain — so the paper's own texture
steers it, which is where granulation comes from — and pigment rides the same
fluxes with upwind concentration. **Evaporation is this brush's bake:** as a cell
dries, what it holds commits to the background permanently. Paper grain is
procedural rather than a texture, so the medium stays reproducible from the
replay log (FR-20).

Four things the tests caught, all real:

- **Water was being destroyed.** A dry cell returned early before computing
  flow, so water arriving on dry paper vanished, and the dry threshold was a
  hidden sink. It is now an explicit parameter; with it off, flow conserves
  water exactly (±0.000%).
- **fp16 was not enough.** Watercolor fluxes are small against the depth they
  modify and half floats round them away outright. The water field is RGBA32F.
- **Adsorption was far too fast.** At 3/s pigment stuck before it could travel,
  which destroyed both edge darkening and wet-on-wet bleed.
- **Edge darkening came from the wrong term.** Boosting deposition at the rim
  does not produce it. Faster *evaporation* at the rim does, by drawing water
  and pigment outward — the coffee-ring effect. The radial profile now peaks
  off-centre instead of pooling in the middle.

### FLIP

Particles carry their own velocity and blend it toward the grid's each step:
near 1 keeps particle momentum and reads splashy, near 0 follows the grid and
reads viscous. Particles that slow and age out dry where they lie and are drawn
once into the background, which is what gives dried-droplet edges rather than
the uniform fade a grid bake produces.

Two constraints shaped it. ES 3.1 has no float atomics, so particles are drawn
as soft additive points rather than scattered by hand. And ES 3.1 guarantees
*zero* storage blocks in vertex shaders, so the particle pool is one buffer
bound two ways — as an SSBO for the compute passes, and as a vertex buffer for
the draws.

It is **one-way coupled**: particles read the grid but do not write back to it.
Full FLIP scatters particle momentum onto the grid, which needs the fixed-point
atomic workaround; that belongs with M2 rather than the spike.

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
  sim     dye    median     p95   est.fps    vram   solved   ink    verdict
  ---------------------------------------------------------------------------
  512²   512²    4.20ms   5.10ms  238.1   12.6MB     78%   +2%    USABLE
  1024²  1024²  14.80ms  17.90ms   67.6   50.3MB     41%  +19%    under-solved
  2048²  2048²  58.10ms  63.20ms   17.2  201.3MB     12%  +64%    too slow
```

It reports **quality alongside speed**, because frame time alone gives a
misleading PASS. `solved` is the fraction of divergence one cold solve removes at
that sweep count; `ink` is how much total ink changed over the run — a positive
number means strokes are gaining mass and will bloom on their own. A resolution
that runs at 60fps but is only 40% solved will look *worse* than a slower one, so
the verdict now requires both. The numbers above are illustrative; run it on your
device for real ones.

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

#### Under-solving manufactures ink

M1 turned up a third consequence, and for a paint app it is the most visible
one. Semi-Lagrangian advection is not mass-conservative, and an under-solved
velocity field has convergent regions that concentrate dye — so total ink
*grows*. Measured at 128², dye mass drift over 240 frames:

```
   5 sweeps   +151.8%          60 sweeps    +14.4%
  15 sweeps    +49.7%         120 sweeps     +4.0%
  30 sweeps    +36.0%
```

At the default 30 sweeps a stroke gains a third of its ink in four seconds: it
blooms and darkens on its own. This is not a bake bug — the bake is exactly
conservative, and the verification asserts that separately — it is the pressure
solve showing up in the artwork. It is the strongest argument yet for multigrid,
and it means adaptive quality must protect sweep count, not spend it.

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

### Getting an APK without a local SDK (including from a phone)

Every push builds a debug APK in CI and uploads it, so a browser is the only
requirement — no toolchain on the device:

**Actions → latest run → Artifacts → `maxpaint-apk`**

It arrives as a zip; unzip and install the APK (needs "install from unknown
sources" for whatever app opens it). This is the recommended route on Android.

Building on the device itself with Termux is possible but fiddly: the Android
Gradle Plugin fetches an x86_64 `aapt2` binary that will not execute on an ARM
phone, so it needs an ARM-native `aapt2` and an
`android.aapt2FromMavenOverride=<path>` entry in `gradle.properties`.

> The environment this was developed in has `dl.google.com` blocked by egress
> policy, and that host serves both the Android SDK and the Android Gradle
> Plugin (`maven.google.com` redirects to it). Packaging therefore happens in
> CI rather than locally. The verification harness below exists so that
> everything short of packaging can still be checked without an SDK.

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
| Resource + manifest packaging | `aapt2` via Gradle, on CI | passing — APK builds clean |

`tools/verify_solver.py` creates a genuine ES 3.1 context and runs the same pass
sequence as `FluidSim.kt`, asserting that the physics is right rather than merely
that the code parses: dye lands where it was splatted, projection reduces
divergence, more sweeps converge further, dye is transported along the injected
momentum, nothing goes non-finite, no flow crosses the walls, velocity decays
under drag (the mechanism M1's bake hangs off), and identical inputs produce
bit-identical output (PRD FR-20).

The APK compiled on its first CI run with no build errors, which is the payoff
from checking the Kotlin against the real framework classes rather than trusting
inspection.

Two bugs were caught this way that inspection had missed:

1. **Illegal read-write images.** ES 3.1 permits read-write image qualifiers only
   for `r32f`/`r32i`/`r32ui`. The `rgba16f` velocity and dye fields were doing
   in-place read-modify-write in the splat, vorticity and gradient-subtract
   passes. They now ping-pong. This would have failed shader compilation on
   device with a driver-specific message.
2. **A slow pressure solve**, quantified rather than suspected — see above.

To reproduce the toolchain on a machine that can reach Google's servers, just use
Gradle normally; the harness above is only needed where the SDK is unavailable.
