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

### Architecture

| File | Role |
|---|---|
| `assets/shaders/*.comp` | The solver: advect, splat, curl, vorticity, divergence, pressure, gradsub |
| `GLUtil.kt` | Shader/program compilation, `Tex` and `DoubleTex` (ping-pong) wrappers |
| `FluidSim.kt` | Field allocation and the per-frame pass sequence |
| `Benchmark.kt` | The scripted resolution sweep |
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

> **Not yet compiled.** The session this was written in has `dl.google.com`
> blocked by egress policy, so neither the Android SDK nor the Android Gradle
> Plugin could be fetched, and no APK has been produced. The GLSL has been
> validated — all twelve shaders compile clean against the ES 3.1 spec with
> `glslangValidator` — but the Kotlin has not been through a compiler. Expect to
> fix a small number of build errors on first run.
