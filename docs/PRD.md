# MaxPaint — Product Requirements Document

**Product:** MaxPaint — a fluid-simulation painting app for Android
**Version:** 1.0 (MVP scope) with 1.x roadmap
**Status:** Draft
**Owner:** cyberhirsch

---

## 1. Summary

MaxPaint is an Android painting app where paint is not a static mark but a
*living 2D fluid*. Every stroke injects velocity and dye into a gaseous
(Eulerian, Navier–Stokes) fluid field. The paint swirls, curls, diffuses and
advects in real time. As the fluid loses energy to drag, it slows, settles, and
is **baked** — permanently composited down into the background raster layer,
freeing the simulation to stay cheap and letting the artist build up a painting
in layers of frozen motion.

The core creative loop is therefore: **inject → watch it move → let it freeze**.
The artist paints with *momentum*, not with pixels.

Different brushes swap out the underlying solver (gaseous / FLIP particle /
pigment-diffusion) so that "fluid" is a family of media rather than one effect.

### Why this is worth building
- Existing mobile paint apps (Procreate, Infinite Painter, ibisPaint) all treat
  a stroke as a stamped decal. Fluid response is at most a post-filter.
- Existing fluid toys (Fluid Simulation by Pavel Dobryakov, Magic Fluids) are
  beautiful but produce nothing permanent — there is no artwork at the end.
- MaxPaint sits in the empty middle: a *real* art tool whose medium is
  simulated fluid, with a durable, exportable canvas.

---

## 2. Goals & Non-Goals

### Goals
- G1 — Real-time gaseous 2D fluid painting at ≥ 60 fps on a mid-range 2022+
  Android device at 1024×1024 simulation resolution.
- G2 — A physically-motivated, artist-legible **bake** mechanic that turns
  transient fluid into permanent paint.
- G3 — At least four distinct fluid brush *media* (gas, FLIP, watercolor,
  and one more) that feel materially different, not merely re-tinted.
- G4 — Standard art-app hygiene: layers, undo, export at ≥ 4K, pressure/tilt
  stylus support.
- G5 — Deterministic replay of a painting session (enables undo, time-lapse
  export, and reproducible bakes).

### Non-Goals (v1)
- 3D fluids, smoke volumes, or any 3D rendering.
- Multi-user / collaborative canvases.
- iOS, desktop, or web ports (architecture should not preclude them).
- Animation timeline / frame-by-frame animation.
- Cloud sync or accounts. Files are local; export is the sharing mechanism.
- Physically accurate pigment spectral rendering (Kubelka–Munk is a v1.x
  stretch, see §6.3).
- **Gravity, and any notion of canvas orientation.** A canvas has no up. Paint
  travels on the momentum of the stroke and stops where drag stops it. This
  removes device-tilt gravity (was FR-8) and the "runs down the canvas" language
  from the FLIP medium. Device rotation likewise does not reshape the canvas —
  the surface takes its shape once. Orientation may matter again at export, for
  the framing of the saved image, and nowhere else.
- ~~**Multiple layers.**~~ Reinstated after use. Dropped at review on the
  argument that the bake already controls what is permanent, but that only
  covers *when* paint becomes permanent, not *what it sits over* — with one
  sheet there is no way to work over a finished passage without touching it.
  Shipped in a form that keeps the solver ignorant of layers: it bakes into the
  active layer only, and the rest of the stack is flattened above and below it
  (§7.7). Checkpoint memory (§7.3) is still a concern and caps the stack at 8.

---

## 3. Target Users

| Persona | Need | What MaxPaint gives them |
|---|---|---|
| **Nadia, digital illustrator** (owns a stylus tablet, uses Procreate) | Wants textures and organic marks she can't get from stamped brushes | A medium that generates unrepeatable, organic detail from her own gesture |
| **Theo, generative/abstract artist** | Wants a system that surprises him | A simulation he steers rather than commands |
| **Casual "fluid toy" user** | Wants something mesmerizing to fiddle with | Instant gratification; the toy leaves a keepable artifact |
| **Concept/matte artist** | Wants fast atmospheric under-painting (smoke, mist, nebulae) | Gas brush + bake produces atmosphere in seconds |

Primary persona for v1 decisions: **Nadia**. When a trade-off pits "toy
delight" against "usable art tool", the art tool wins.

---

## 4. The Core Concept: Inject, Move, Bake

### 4.1 Three-stage material lifecycle

Every bit of paint on the canvas is in exactly one of three states:

1. **Live** — held in the simulation's dye/particle field. Fully mobile,
   advected by velocity, affected by every force. Rendered on top of everything.
2. **Settling** — the fluid's local velocity has fallen below a threshold. The
   paint is still in the sim but is being progressively transferred out of it.
   Visually this reads as the swirl "crisping up".
3. **Baked** — composited into the background raster layer. Immutable (except
   by undo, erase, or later strokes painted over it). Costs zero simulation
   budget.

### 4.2 The bake rule

Per simulation cell (or per FLIP particle), each frame:

```
speed        = |velocity|
age          = frames since this dye was injected
settle_score = clamp01( (settle_speed_threshold - speed) / settle_speed_threshold )
             * clamp01( age / settle_min_age )

bake_amount  = settle_score * bake_rate * dt
background  += dye * bake_amount      // premultiplied, over-composite
dye         -= dye * bake_amount
```

Consequences that make this *feel* right:
- Fast, energetic parts of a stroke stay alive and keep swirling — a whip of the
  finger leaves a long-lived vortex.
- Slow, trailing edges freeze first, so strokes crystallise from the outside in.
- Drag (velocity dissipation) is what *causes* baking, so the artist controls
  permanence with a single intuitive dial: **more drag = paint sets faster**.

### 4.3 Artist-facing controls for the bake

| Control | Range | Effect |
|---|---|---|
| **Drag / Viscosity** | 0–1 | How fast the fluid loses momentum. High = paint sets almost immediately (behaves like a normal brush). Low = paint stays alive for many seconds. |
| **Set Speed** (bake_rate) | 0–1 | Once settling begins, how quickly dye transfers to background. |
| **Hold** (settle_min_age) | 0–5 s | Minimum time paint must stay live before it may bake. Lets the artist guarantee a swirl before it locks. |
| **Freeze Now** (button/gesture) | — | Immediately bakes 100% of live fluid. The "commit" gesture. |
| **Thaw** (button) | — | Lifts the most recent bake generation back into the sim (bounded: last N bakes; see §7.3). |

`Freeze Now` is bound to a two-finger tap and is the single most-used control
after the brush itself. It must be reachable without leaving the canvas.

---

## 5. Brushes

Each brush is a **medium** (which solver + how paint is represented) plus a
**preset** (parameters over that medium). Artists can create and save presets;
media are built in.

### 5.1 Gas Brush (Eulerian) — *the hero brush*

- **Solver:** semi-Lagrangian advection + Jacobi/multigrid pressure projection
  on a MAC grid; vorticity confinement to preserve curl.
- **Injection:** stroke velocity → velocity field impulse; brush color →
  dye field; optional buoyancy from a temperature field.
- **Feel:** smoke, ink-in-water, nebula, aurora. Marks bloom outward and curl.
- **Key params:** vorticity strength, buoyancy, dye diffusion, impulse radius,
  velocity gain (how much of your gesture becomes momentum).
- **Presets:** *Smoke*, *Ink Drop*, *Nebula*, *Aurora*, *Steam*.

### 5.2 FLIP Brush (particle) — *liquid*

- **Solver:** FLIP/PIC hybrid — particles carry velocity + color, transfer to a
  grid for pressure solve, then blend back (`flip_ratio` ≈ 0.95 for splashy,
  ≈ 0.6 for viscous).
- **Injection:** each stroke emits particles with the stylus velocity, jittered
  by brush radius.
- **Feel:** flung paint — droplets, splatters, and trails that carry as far as
  the gesture threw them. No gravity, so nothing runs "down": drag alone decides
  where a droplet stops.
- **Baking:** per-particle. A particle whose speed stays below threshold for
  `settle_min_age` splats itself into the background with a soft round kernel
  scaled by particle radius, then dies. This gives lovely dried-droplet edges.
- **Key params:** flip ratio, particle mass/size, drag, surface tension,
  cohesion, settle threshold.
- **Presets:** *Wet Paint*, *Splatter*, *Fling*, *Honey* (high drag),
  *Mercury* (low drag, high cohesion).

### 5.3 Watercolor Brush — *pigment on paper*

Not a Navier–Stokes brush; a shallow-water + pigment-deposition model
(after Curtis et al., *Computer-Generated Watercolor*, SIGGRAPH '97).

- **Layers per cell:** water depth, pigment concentration (per channel),
  paper height field (fixed, procedural), capacity, wet mask.
- **Passes per frame:** shallow-water velocity update → water moved along
  gradients + paper capillary flow → pigment advected by water → pigment
  adsorbed to / desorbed from paper → water evaporates.
- **Edge darkening** falls out naturally from the wet-mask boundary term — this
  is the single most recognisable watercolor cue and must not be faked.
- **Baking:** evaporation *is* baking. As water depth → 0, adsorbed pigment is
  committed to the background layer. "Drag" maps to evaporation rate.
- **Wet-on-wet vs wet-on-dry:** the persistent wet mask means painting into a
  still-damp area bleeds; painting onto dry paper does not. This is the brush's
  core expressive mechanic and needs a visible wetness overlay toggle.
- **Key params:** wetness, pigment load, granulation, paper grain + scale,
  evaporation rate, backrun strength (blooms/cauliflowers).
- **Presets:** *Wash*, *Wet-on-Wet*, *Dry Brush*, *Bloom*, *Salt Texture*.

### 5.4 Additional brush ideas

Ranked; the top two ship in v1 alongside gas/FLIP/watercolor.

1. **Vortex / Rake (force-only brush)** — *ships v1.* Injects velocity but **no
   dye**. Lets the artist stir, smear and re-shape paint that is still live, or
   thaw-and-restir baked paint. Modes: *swirl* (curl impulse), *push*
   (directional), *pinch* (convergent), *comb* (multi-tine, like a marbling
   rake). Turns marbling/suminagashi into a first-class workflow.
2. **Alcohol / Solvent brush** — *ships v1.* Injects negative pigment density
   and high local diffusion: pushes existing wet pigment away from the stroke,
   creating the classic alcohol-drop halo. Also usable as a "wet eraser".
3. **Electrostatic / Field brush** — paints a scalar potential; live fluid is
   attracted or repelled. Enables gravity wells, orbiting dye, magnetic
   ferrofluid spikes.
4. **Impasto / Viscous Gel** — very high viscosity FLIP with a height field;
   bakes to a normal map so the background layer gets real relief lit by a
   movable light. Thick oil-paint ridges.
5. **Reaction–Diffusion brush** — seeds a Gray–Scott field that grows coral /
   Turing patterns inside the wet region, advected by the fluid, then bakes.
6. **Smoke-Trail Stamp** — drop a persistent emitter on the canvas that keeps
   injecting fluid until removed. Turns the canvas into a system to compose,
   not just a surface to mark. (Pairs with time-lapse export.)
7. **Chromatic / Dispersion brush** — per-channel differing diffusion and
   advection so colours separate as they flow (chromatography look).
8. **Freeze brush** — a *local* Freeze Now: bakes only what the brush touches.
   Lets the artist lock a good vortex while the rest keeps moving. Its inverse
   is the **Thaw brush**, lifting baked paint back into the sim locally.

The Freeze/Thaw brush pair (8) is high-value and low-cost — recommended for v1
if schedule allows, since it reuses the bake path.

---

## 6. Functional Requirements

### 6.1 Canvas & document
- FR-1 Canvas sizes: 1K, 2K, 4K square + common aspect ratios. Simulation grid
  resolution is decoupled from canvas resolution (see §7.2).
- FR-2 Layers: up to 8, each with visibility and opacity, reorderable, with
  per-layer wipe. Built.
- FR-3 One live layer at a time: the simulation bakes into the active layer and
  no other. Layers above it composite over the wet paint as well as the dry.
  Built.
- FR-4 Export: PNG (with/without alpha), JPEG, and a `.maxpaint` document
  carrying the replay log. PNG is built — saved at canvas resolution rather
  than the screen's, so the file is the painting and not the viewport.
- FR-5 Time-lapse export: MP4 of the session, driven by the replay log (§6.6).

### 6.2 Input
- FR-6 Stylus pressure → dye amount and brush radius; tilt → impulse direction
  spread; velocity → momentum injected. Explicit per-brush mapping matrix,
  artist-editable.
- FR-6a A stroke is a **path, not a list of touch events**: dabs are stamped at a
  fixed spacing in canvas units, with the leftover distance carried across
  events, and the batched samples Android reports in each event are all read.
  Load therefore means ink per brush-width travelled, and the same stroke drawn
  twice weighs the same regardless of how busy the frame was.
- FR-7 Multi-touch: 2-finger pan/zoom/rotate on canvas; 2-finger tap =
  Freeze Now; 3-finger swipe left/right = undo/redo (Procreate-compatible
  muscle memory).
- ~~FR-8 Device tilt drives FLIP gravity~~ — dropped, see Non-Goals. There is no
  gravity and no canvas orientation, so the accelerometer is not read at all.
- FR-9 Samsung S-Pen and generic USI/AES styluses supported; palm rejection.

### 6.3 Color
- FR-10 sRGB working space, linear-light compositing internally.
- FR-11 Pigment mixing option: subtractive mixing (Mixbox-style / Kubelka–Munk
  approximation) for the watercolor and FLIP brushes so blue + yellow = green,
  not grey. v1: two-parameter KM approximation; v1.x: full spectral.
- FR-12 Palettes, eyedropper (samples composited result), harmony wheel.

### 6.4 Simulation controls (global panel)
- FR-13 Global: sim resolution, time step, iteration count, boundary condition
  (walls / open / wrap), global drag multiplier.
- FR-15 Boundary "wrap" enables seamless tiling textures — a genuinely
  differentiating export mode.
- FR-16 Pause/step: freeze the simulation clock without baking. Essential for
  composing a mark, and for screenshotting.

### 6.5 Undo
- FR-17 Undo granularity = one stroke *plus its resulting bake*. Built, but by
  snapshot rather than checkpoint + replay (§7.3). Replay reproduces the *live*
  simulation, which undo does not need: what an artist wants back is the paint
  that set, and only one layer can change during a stroke. Undo restores that
  layer's pixels and stills the simulation, since anything still live would bake
  again a moment later and undo the undo.
- FR-18 Minimum 20 undo steps; target 50. **Built to a memory budget instead**:
  a snapshot is a whole canvas — 4.7 MB at 1174×502, 15 MB at 2048 detail — so a
  fixed step count would be a memory cliff on exactly the canvases that can
  least afford one. 64 MB of history, which is 13 steps at the default detail.
  Structural layer changes clear it.

### 6.6 Replay log
- FR-19 Every input event (position, pressure, tilt, timestamp, brush, params)
  and every RNG seed is logged. The document stores this log.
- FR-20 Given the log and a fixed time step, the painting reproduces
  bit-identically. This underpins undo, time-lapse, and "re-render at 4K".
- FR-21 **Render at higher resolution:** re-run the log at a larger sim grid to
  produce a print-resolution version of a sketch. High-value, and only possible
  because of determinism — call this out in marketing.

---

## 7. Technical Design

### 7.1 Stack
- Kotlin + Jetpack Compose for UI; `AndroidView`/`SurfaceView` for the canvas.
- **Rendering/compute: Vulkan compute shaders** as primary path, with an
  **OpenGL ES 3.1 compute** fallback for older devices. Rationale: fluid solves
  are compute-bound and need ping-pong storage images; ES 3.1 compute covers
  ~99% of active devices, Vulkan gives headroom and better profiling.
- All simulation state lives in GPU textures/buffers; never round-trips to CPU
  except for checkpointing.

### 7.2 Resolution decoupling
- Simulation grid: 256² … 1024² (device-tier dependent, user-overridable).
- Dye/pigment field: up to 2× sim grid (dye can be finer than velocity — this is
  the cheapest way to buy visual detail).
- Background/baked layer: full canvas resolution (up to 4K+), a plain RGBA
  texture. Baking is a splat from the dye field into this texture, so the
  permanent artwork is high-res even when the sim is coarse.

### 7.3 Checkpointing & undo
- A checkpoint = {background layer texture (compressed), velocity field, dye
  field, particle buffer, RNG state}. Roughly 30–60 MB uncompressed at 1024².
- Strategy: keep the last 3 checkpoints in GPU/host memory, older ones on disk,
  and reconstruct intermediate states by replaying the log forward from the
  nearest checkpoint. Checkpoint at most every N strokes (adaptive to memory
  pressure).
- **Thaw** uses the same machinery: it restores dye from the delta between two
  background-layer checkpoints.

### 7.4 Performance budget (per frame, 60 fps → 16.6 ms)

| Stage | Budget |
|---|---|
| Advection (velocity + dye) | 2.0 ms |
| Pressure projection (20–40 Jacobi iters, or multigrid) | 6.0 ms |
| Vorticity confinement + forces | 1.0 ms |
| Bake pass (settle + splat to background) | 1.0 ms |
| FLIP P2G/G2P (when FLIP brush active, replaces some of the above) | 4.0 ms |
| Composite + present | 2.0 ms |
| UI + input | 2.0 ms |
| Headroom | 2.6 ms |

- Pressure solve is the risk. **M0 shipped red-black Gauss–Seidel** (measured at
  2× Jacobi's convergence per sweep, at equal thread count and half the pressure
  memory). A 3-level multigrid V-cycle is still needed above ~1024², because
  iterative sweep counts scale quadratically with grid width while multigrid
  convergence is resolution-independent. Drop iteration count adaptively when
  frame time slips — but note that under-solving degrades fluid quality, so
  adaptive quality should step resolution down before it steps sweeps down.
  M1 sharpened this: under-solving also *manufactures ink*, because convergent
  regions in a divergent field concentrate dye, so strokes bloom and darken on
  their own. Sweep count is therefore the last thing adaptive quality should
  spend, not the first.
- **Adaptive quality**: a controller monitors frame time and steps down
  (iterations → dye resolution → sim resolution) before it drops frames.
  Never let the canvas stutter; degrade silently.

### 7.5 Thermal & battery
- Target ≤ 5 W sustained. Cap sim rate at 60 Hz even on 120 Hz displays; render
  the UI at display rate, the sim at a fixed 60 Hz with interpolation.
- Suspend the solver entirely when there is no live fluid and no input — an
  all-baked canvas costs nothing.

### 7.6 Testing
- Golden-image tests: replay a fixed log, compare against reference frames
  within tolerance, per GPU vendor.
- Solver unit tests: divergence after projection < ε; bake conserves total dye
  (live + baked) to within ε. **Note from M1:** advection is *not*
  mass-conservative and must not be asserted as such — semi-Lagrangian never is,
  and an under-solved velocity field concentrates dye badly (dye mass drift over
  240 frames at 128²: +152% at 5 sweeps, +36% at 30, +4% at 120). The invariant
  to test is that a better solve conserves better, plus a bound at a
  well-converged sweep count.
- Device matrix: Pixel (Mali/Adreno), Samsung (Xclipse/Adreno), one budget
  MediaTek device, one tablet.

### 7.7 Layers

The solver has one background texture and knows nothing about a stack. Layers
are built around it rather than through it: `background` is simply whichever
layer is active, so every existing pass — bake, soak, dry, smear, the FLIP
retire — keeps writing to exactly one texture and needed no change.

Display then needs the rest of the stack. Sampling eight layers per pixel per
frame is wasteful when only one of them ever changes, so everything below the
active layer is flattened into one texture and everything above into another,
recomposed only when the stack itself changes — a layer selected, reordered,
hidden, or its opacity moved. Painting never dirties them. The display shader
therefore costs two extra texture reads regardless of stack depth, and both are
skipped outright when that half of the stack is empty.

Compositing is the premultiplied over operator, `dst = src + dst·(1−src.a)`,
with layer opacity scaling colour and coverage together. Live fluid sits between
the two flattened halves: paint that has not yet baked belongs to the active
layer, so layers above it cover the wet paint the same as the dry.

Cost is two full-canvas RGBA16F textures per layer, plus up to two more for the
flattened halves — about 4.7 MB each at 1174×502. Eight is the cap.

### 7.8 The particle grid is not the ink grid

A third resolution, on top of the two in §7.2. FLIP borrows a grid so particles
can feel each other, and the pressure solve couples a *cell* to its neighbours,
so a cell holding one particle couples that particle to nothing. Several
particles must share a cell — four to eight in 2D.

Running the particle solver on the ink grid gave 0.005 particles per cell, with
every particle alone inside a free surface. Measured coupling was 2.6x against a
peak of 7.1x at thirteen per occupied cell. The particle grid is now its own
budget (192, canvas-shaped) and is exposed as a Coupling control, because how
coarse it is *is* how thick the medium reads.

The same ratio's other half: emission is specified as a **density** — particles
per cell of the dab's footprint — rather than a count per dab. A fixed count
does not hold density, so a wider brush spread the same particles thinner and
Brush size silently changed how the medium behaved (9.6 down to 1.0 per occupied
cell across the size range).

---

## 8. UX Requirements

- UX-1 **Canvas first.** Chrome is a thin translucent rail; a single tap on
  empty canvas hides all UI.
- UX-2 The brush panel shows *at most 5* sliders by default, with an "Advanced"
  expander for the full solver parameter set. Fluid solvers have too many knobs;
  the default view must be curated per medium.
- UX-3 Live/baked state must be legible. A toggleable "heat" overlay tints live
  fluid by remaining lifetime so the artist can see what is about to freeze.
- UX-4 Onboarding: a 30-second interactive first-run that teaches exactly one
  thing — flick to swirl, two-finger tap to freeze.
- UX-5 Accessibility: all gestures have button equivalents; no colour-only
  status; respect system font scaling in panels; reduced-motion setting damps
  idle simulation animation.

---

## 9. Success Metrics

| Metric | Target (90 days post-launch) |
|---|---|
| D1 / D7 / D30 retention | 40% / 18% / 8% |
| Median session length | ≥ 8 min |
| % sessions ending in an export or save | ≥ 35% |
| Median frames/sec on target device tier | ≥ 58 |
| Crash-free sessions | ≥ 99.5% |
| Play Store rating | ≥ 4.4 |
| % of users who use ≥ 3 different brush media | ≥ 50% |

Leading indicator for the *core concept working*: **% of strokes followed by a
deliberate `Freeze Now` within 10 s**. If this is low, the bake mechanic is not
being understood and onboarding needs rework.

---

## 10. Milestones

| Phase | Scope | Exit criteria |
|---|---|---|
| **M0 — Spike** (3 wks) | GLES compute Navier–Stokes on device; touch injects dye | 512² at 60 fps on target device |
| **M1 — Bake** (3 wks) | Background layer, settle/bake pass, Freeze Now, drag control | A stroke can be made permanent and the sim returns to idle cost |
| **M2 — Media** (6 wks) | FLIP brush, watercolor brush, vortex brush, solvent brush; brush/preset system | Four media, visually distinct, each with 3+ presets |
| **M3 — App** (6 wks) | Layers, undo via checkpoint+replay, export, color, gestures, UI | Can produce and export a finished piece end-to-end |
| **M4 — Polish** (4 wks) | Adaptive quality, thermal, onboarding, accessibility, device matrix | Meets performance and crash-free targets on full device matrix |
| **M5 — Beta** (4 wks) | Closed beta with 50 illustrators; time-lapse export; hi-res re-render | Metrics instrumented; retention baseline established |

Total ≈ 26 weeks to public beta.

---

## 11. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Pressure solve too slow on mid-range GPUs | High | Multigrid; adaptive iterations; ship with lower default sim res and let power users raise it. **Measured in M0:** the binding constraint is convergence, not throughput — iterative sweeps scale quadratically with grid width (~16× cost per doubling for equal quality), so multigrid is required above ~1024² rather than optional. Red-black Gauss-Seidel now ships in place of Jacobi, worth ~2× |
| Bake mechanic confuses users ("my paint disappeared / won't stop moving") | High | Heat overlay (UX-3); high default drag so v1 behaves near-conventionally out of the box; onboarding teaches freeze first |
| Checkpoint memory blows up on 4K canvases | Med | Checkpoint the background layer at full res but the sim state at sim res; compress; disk-back older checkpoints |
| Watercolor model is a second full engine, not a brush | Med | Scope it as its own solver with its own budget; it does not need to coexist with N-S in the same frame |
| Thermal throttling ruins long sessions | Med | 60 Hz sim cap; idle suspension; watchdog that steps quality down |
| GPU driver variance (Mali vs Adreno vs Xclipse) | Med | Golden-image tests per vendor in CI on a device farm; avoid exotic extensions |
| Feature creep across 8 brush ideas | Med | v1 ships 6 media (gas, FLIP, watercolor, vortex, solvent, freeze/thaw); the rest are roadmap |

---

## 12. Open Questions

1. ~~Should baked paint remain *slightly* interactive, or be strictly
   immutable?~~ **Resolved: interactive.** Immutable shipped first and was wrong
   in use — stir and lift did nothing to the marks on the canvas, which is the
   one thing an artist expects a brush to act on. The force brushes now lift set
   paint back into the simulation under the brush, by an adjustable amount, and
   setting that amount to zero restores immutability for anyone who wants it.
2. Is per-layer simulation state worth it, or is one shared sim over a single
   live layer sufficient? **Proposal:** one shared sim in v1.
3. Pricing: one-time purchase, or free with a paid "Pro brushes" unlock?
   Illustrator persona strongly prefers one-time purchase.
4. Does the tilt-driven FLIP gravity delight or annoy? Beta question.
5. Do we need a canvas-edge behaviour beyond walls/open/wrap — e.g. absorbent
   edges for watercolor?

---

## Appendix A — Glossary

- **Advection** — transport of a quantity (dye, velocity) along the velocity field.
- **Bake** — permanent transfer of live fluid dye into the background raster layer.
- **FLIP/PIC** — Fluid-Implicit-Particle / Particle-In-Cell; hybrid particle-grid liquid solver.
- **Pressure projection** — the step that makes the velocity field divergence-free (incompressible).
- **Vorticity confinement** — an artificial force that re-injects curl lost to numerical dissipation; what makes simulated smoke look "swirly" rather than "blurry".
- **Live / Settling / Baked** — the three states of paint in MaxPaint (§4.1).
