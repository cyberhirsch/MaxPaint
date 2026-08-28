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
| **Set speed / Hold** | How fast settled paint transfers, and how long it must stay live first. Shipped at maximum and zero: paint sets early. |
| **Freeze** button, or a two-finger tap | Commits the whole canvas immediately. |
| **Heat** | Tints live fluid by how close it is to setting, so you can see what is about to freeze (PRD UX-3). |

Ink is premultiplied by coverage, so compositing paper → baked → live is a plain
"over". The bake accumulates additively rather than compositing over, which is
what makes it exactly conservative: what leaves the dye field is what arrives in
the background — 0.000% error on a single transfer. Across a couple of hundred
partial transfers it drifts about 0.7%, and that is storage rather than
arithmetic: the background is `rgba16f`, so once it holds a large value the last
thin residues of a stroke fall below its ulp. The two are asserted separately, so
a regression in the operator cannot hide behind the rounding budget. Compositing "over" there would saturate, and repeatedly laying
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

## Bugs found by review

A pass over the code after the first real session. Most were invisible until
looked for, which is why they are recorded here:

| Bug | Effect |
|---|---|
| Every medium's solver ran every frame | With the gas brush, each frame still updated **120,000 particles and drew them twice**, plus two unused full-grid passes. Each medium is now dormant until first used. |
| The particle pool was walked in full from the first stroke | The ring buffer only holds particles up to the write head; the rest was waste. Only the filled span is updated and drawn now. |
| A framebuffer was created and destroyed **three times a frame** | Driver churn for nothing. One reusable scratch FBO now. |
| The nib shared one "previous point" across all pointers | A second finger joined its mark to the first, drawing a line across the canvas. The previous sample is now per pointer. |
| Grid dimensions ignored `GL_MAX_TEXTURE_SIZE` | Shaping the grid to the canvas makes the long side much longer. At a 1536 budget with 2× ink detail on a 2.2:1 screen the dye texture wants **4556px**, past the 4096 many mobile GPUs report — allocation fails and the canvas goes black. Now clamped to the reported limit. |
| Opening a settings panel reset the settings | Widgets initialised from hardcoded values and fired their callbacks, so opening a panel silently overwrote the preset just chosen. Widgets read the live value now, and the preset picker ignores the initial selection Android fires for it. |
| Sweep wiped the canvas without warning | It reallocates at every resolution. It asks first now. |

**Fixed since:** the canvas no longer reshapes at all. It takes its shape once
and keeps it, because a painting is not a view that reflows — rotating the device
must not reallocate every field and throw the artwork away. Only starting a new
canvas changes the shape.

**Also removed: gravity.** A canvas has no up. The FLIP medium carried a gravity
vector and an accelerometer binding, which meant paint "fell" toward whatever the
phone thought was down. Paint now travels on the momentum of the stroke and stops
where drag stops it; the accelerometer is not read at all. Orientation may matter
again at export, for the framing of the saved image, and nowhere else.

### Paint sets early

Defaults are drag 3.0, set speed at maximum, hold at zero, and a generous settle
threshold. A stroke is **22% set after 0.1s, 69% after 0.25s, and completely set
within a second**. That also matters for the force brushes: they can only pick up
paint that has actually set, so slow baking left them with nothing to grab.

The nib and watercolor keep their own drying rates — a nib is supposed to soak
slowly while held, which is the opposite requirement.

### Load, on every tool

Every tool has a **Load** slider. For the pigment brushes it is the opacity of
the mark — it scales what that medium puts down, verified across gas, nib and
watercolor. For the tools that deposit nothing it scales how hard they act.

### Smear

A finger through charcoal. The vortex brush pushes the *velocity field*, which
only moves paint still live in the simulation; pigment that has set is on the
paper, so the only way to move it is to resample it. Smear warps the background
(and the wet nib field, when that is in use) along the stroke.

It is **conservative**, which took a second attempt. Blending each cell toward
the colour behind it leaves the source untouched, so the mark is *copied* forward
rather than moved — measured at **2.6× the ink** after a single drag. Each cell
now gives up its own share and takes the share the cell behind it gives up, which
holds the total: 46.3 → 45.8 across a full smudge.

Presets: *Finger*, *Stump*, *Chamois*, *Long Drag*.

### Set paint is smearable

The PRD left this as an open question: should baked paint keep a thin "wet skin"
a vortex brush can still smear, or be strictly immutable? It shipped immutable,
with the Thaw brush as the escape hatch — and in use that is plainly wrong. Stir
and lift did nothing to the marks actually on the canvas, which is the one thing
an artist expects a brush to act on.

Both force brushes now lift a little set paint back into the simulation under the
brush before acting, controlled by a **Pickup** slider. Measured: a fully set
mark, stirred, gives up 20.9 of its 46.3 units of ink to the live field, which is
then stirrable. Pickup at zero restores the old behaviour exactly (set paint
46.3 → 46.3), and the *Smear Only* vortex preset ships that way.

## Every brush has its own size

`splatRadius` was one shared field driving gas, drip, wash, stir, lift, set and
melt — with a Brush size slider on the gas panel and nowhere else. So setting
the gas brush silently resized six other tools, and there was no way to size a
drip at all. ("Drop size" on the drip panel is the particle *sprite*, not the
dab.)

Size is now stored per brush, behind an accessor that returns whichever brush is
painting, so every reader in the solver is unchanged — they all wanted the active
brush's size, which is what they were finally given. The slider appears on every
panel whose brush has a footprint; nib and smear keep their own controls.

## The finger, not a point

Android reports rather more about a touch than a coordinate. Per pointer, and
through the batched historical samples too:

| | |
|---|---|
| `getTouchMajor` / `getTouchMinor` | the axes of the **contact patch** ellipse, in device surface units |
| `getToolMajor` / `getToolMinor` | the size of the tool itself, rather than the part touching |
| `getOrientation` | that ellipse's angle, radians clockwise from vertical |
| `getSize` | contact area, normalised against the largest the panel can sense |
| `getPressure` | normalised so 1.0 is a normal touch; may exceed it |
| `getToolType` | finger / stylus / eraser / mouse |
| `AXIS_TILT`, `AXIS_DISTANCE` | stylus tilt from perpendicular, hover height |

How much of that carries a real signal is device-specific and not worth
guessing. On most capacitive panels **pressure is derived from contact area**,
so the two are one signal wearing two names, and plenty of devices report a
constant for both. Some report `touchMajor == touchMinor` — an area dressed as
an ellipse. A stylus inverts it: pressure is genuine, contact size is not.

So the app both **reports** and **uses** it. `Touch` in settings prints the live
per-sample values in the HUD; `Axes` lists what the driver claims to support,
via `InputDevice.getMotionRange`, which is a different question from what it
actually sends.

The dab then takes that shape. Gas, watercolor and the particle emitter all
squash their footprint across the contact's short axis and turn it to lie along
the long one, so a fingertip rolled onto its side makes an oval mark angled the
way the finger is. Pixels convert to world units through the view height, since
world y spans 1.0 over it.

**Contact shape** is a slider on the brush panel, on at 100%: a ratio is
dimensionless and bounded, so a device that only reports circles simply keeps
the mark round rather than getting it wrong.

**Fingerprint** is a mode rather than a blend — a checkbox in settings that
switches the footprint over to the contact area and greys out Brush size, which
is the honest way to present it: two states that cannot both be half true. Off by
default, because the measurement is in device-calibrated absolute units and a
panel reporting a constant would leave the mark stuck at one size with no
control. In that mode the radius is *not* clamped toward Brush size — that would
defeat the point — but to an absolute band, wide enough to be honest and narrow
enough that nonsense cannot fill the canvas.

### The Probe brush

Whether any of this does anything depends on hardware nobody can inspect from
here, so there is a brush that answers it. Probe draws the reported contact patch
straight onto the layer: **a dot at the touch point, always**, and a ring around
it at the reported ellipse with a tick along its long axis, **only when a contact
was reported**.

Dot alone means the panel reports no contact geometry and Fingerprint has nothing
to work with. Dot inside a ring means it does, and the ring is exactly the size
and shape the brushes are being handed. No numbers to read, and the two answers
cannot be confused — which is the only property a diagnostic really needs.

Six checks hold it to that: that nothing reported draws a 2-cell dot and a
contact draws a 28-cell ring, that the ring is a ring rather than a disc, that it
takes the reported shape and orientation, and that halving the reported radius
halves the ring — a diagnostic that draws the wrong size is worse than none.

Building it turned up the failure mode worth guarding: an **unset uniform is
zero**, and a zero minor axis inflates the dab across the entire canvas. Both
suites went red at once — the sort of bug that would have been a black screen on
some code path months later. The shaders now read a degenerate frame as "no
contact reported" and fall back to a circle, which is the failure-safe meaning.

Nine checks: that a round contact reproduces the old dab **bit-identically**,
that a flattened one is elliptical, that turning it swaps the axes, that a
diagonal lands between the two, that an ellipse deposits less ink than a round
dab of the same length, and the same shape/turn pair again for the particle
emitter, which squashes a scatter disc rather than transforming a distance and
so needs its own coverage.

## A stroke is a path, not a list of points

The gas brush beaded on fast strokes — a row of separate dabs with white
between them. Two causes, both in the input layer rather than the solver:

1. **One splat per touch event, at the current point.** The previous point was
   passed along but only used to derive velocity, so the mark was one dab per
   event and the gap between dabs was however far the finger had travelled. The
   nib and smear never beaded because they draw a capsule from the previous
   point, which is the same fix by another name.
2. **The batched samples were thrown away.** Android packs several positions
   into each `ACTION_MOVE` and exposes the extras through `historySize`. Reading
   only the last one discards most of what the digitiser reported and cuts
   corners across.

Both are fixed. Dabs are stamped at a fixed spacing in canvas units, and the
leftover distance is **carried across touch events** — without that the spacing
restarts at every event and the dab count follows the report rate again. Half a
radius apart is enough: the falloff is `exp(-d²/r²)`, and measured coverage never
drops below 74% of the peak, while every dab is a full-canvas pass so closer
spacing costs frames for a mark that is already continuous.

The first attempt shared each *segment's* load between its dabs, which the tests
rejected outright: the same path reported as 2 events gave 19.6 ink and as 20
events gave 195.6. **Load is ink per brush-width travelled**, not per event and
not per dab — the only definition of the three that does not depend on how busy
the frame was. Measured: 299.4 either way, and half the path deposits half the
ink.

**The constant needed calibrating, and the first measurement of it was wrong.**
The rule is principled but its constant cannot be: matching the old per-event
meaning would need the event rate the rule exists to remove. It was left at
spacing over radius, which I measured as 1.5x heavier than before — *on a square
canvas*. The number of extra dabs scales with the **world** length of the path,
so on a 2.34 canvas it is 2.34x worse again: 3.7x the ink, and at Load 2 the
mark saturated to solid black. A square-canvas measurement understates this
exactly by the aspect ratio, which is why it read as harmless.

`DAB_CALIBRATION` is now 0.29, set so a reference stroke deposits 658 units of
baked ink against 658 for the behaviour it replaced (2271 without it). The check
that guards this is measured on a **wide** canvas for the same reason.

The stroke does read lighter than the beaded version at equal ink, because the
same paint is now spread evenly instead of piled at the points the digitiser
happened to report — peak grey 112 against 30. That is the fix working, but it
means Load settings tuned against the beaded mark now come out weak, so the Load
range goes to 4.0.

## Undo and redo

`undo` and `redo` sit on the tool rail. Undo is by **snapshot**, not by the
checkpoint-and-replay the PRD specified. Replay reproduces the live simulation,
which undo does not need: what an artist wants back is the paint that *set*, and
only one layer can change during a stroke — bake, soak, smear and the FLIP retire
all write to the active layer and nowhere else. So one texture is the whole edit.

Undo also stills the simulation. Anything still live would bake again a moment
later and undo the undo.

The depth is a **memory budget rather than a step count**: a snapshot is a whole
canvas, 4.7 MB at 1174×502 but 15 MB at 2048 detail, so *n* steps would be a
memory cliff on exactly the canvases that can least afford one. 64 MB of history,
which is 13 steps at the default detail and 4 at the largest. Retired snapshots
are pooled rather than deleted, so a stroke does not begin with a full-canvas
texture allocation. The HUD's VRAM figure includes all of it.

Structural layer changes — add, delete, reorder — leave every snapshot pointing
at the wrong sheet, so they clear history. Deleting a layer therefore asks first.
Wiping a layer is undoable.

Five checks cover it: that the snapshot round-trips bit-identically in both
directions, and that a snapshot is a copy rather than a view of the layer it came
from — an alias would restore exactly the state it was meant to replace.

## Layers, and saving a PNG

Both live on a rail down the **right** edge, mirroring the tool rail: `png`, then
one small button per layer with the top of the stack at the top of the rail, then
`+`. Tapping the selected layer again opens its panel — opacity, hide, move up or
down, wipe that layer alone, delete.

The solver never learned about layers. `background` is now simply whichever layer
is active, so bake, soak, dry, smear and the FLIP retire all still write to one
texture and none of them changed. What the display needs is the *rest* of the
stack, and sampling eight layers per pixel per frame is wasted work when only one
of them ever changes — so everything below the active layer is flattened into one
texture and everything above into another, recomposed only when the stack itself
changes. Painting never dirties them. Display costs two extra texture reads no
matter how deep the stack is, and skips them when that half is empty.

Live fluid sits between the two halves: paint that has not baked yet belongs to
the active layer, so a layer above covers wet paint exactly as it covers dry.

Six checks cover the composite operator directly — half-coverage, full occlusion,
opacity scaling colour and coverage together, zero opacity as a no-op,
compositing onto blank paper, and that stacking order changes the result.

**PNG** renders the whole stack into an offscreen buffer at *canvas* resolution
rather than the screen's, so the file is the painting and not the phone's
viewport, and the debug overlays are forced off so a heat view never lands in the
saved image. On Android 10 and up MediaStore takes the file into
`Pictures/MaxPaint` with no permission at all; older devices fall back to the
app's own Pictures folder if the storage permission was refused, so the painting
is saved either way and only the gallery listing is lost. Compression runs on its
own thread — it is slow enough to stutter the canvas from either the GL or the UI
thread.

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
| **Flip** | particles | Flung paint: droplets and trails that carry as far as the gesture threw them, then dry where they stop. One slider goes splashy to viscous. |
| **Water** | water + pigment | Shallow-water pigment on paper: bleeds, blooms, darkens at the edges, granulates on the paper grain. |
| **Vortex** | momentum only | Swirl, push, pinch, or comb. Comb is a marbling rake. Lifts paint that has already set so it can smear it. |
| **Solvent** | lifts ink | Scales pigment down where it bites and drives the rest outward — the alcohol-drop halo, not an erase. Works on dried paint too, which is most of what it is for. |
| **Smear** | moves ink | Drags pigment already on the paper, the way a finger moves charcoal dust. Deposits nothing of its own. |
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

This is now a real FLIP step, not passive particles. The first version was
neither, and it showed:

- **No particle-to-grid transfer.** Particles read the grid but never wrote to
  it, so they never saw each other. No incompressibility, and the medium was a
  spray of independent points rather than liquid.
- **The blend was wrong.** It mixed toward the *absolute* grid velocity, which
  discards the particle's history every step — PIC with inertia, not FLIP. Real
  FLIP adds the grid's *change*: `v + (gridNew − gridOld)`.

The pipeline is now scatter → project → gather. Momentum accumulates as
fixed-point integers, since ES 3.1 has no float atomics but `atomicAdd` on `int`
is core. Cells holding no particles are air and are pinned to zero pressure,
which is what gives the liquid a free surface instead of sealing it in a box.

Two things had to be right together, and getting one without the other was
worse than neither:

- **The grid is staggered (MAC).** The x component lives on a cell's left face,
  y on its bottom face. Forward divergence paired with a backward gradient
  composes to exactly the compact five-point Laplacian the pressure solve
  inverts. The gas brush keeps centred differences, which measure better there;
  they fail for a free surface because they span two cells, so a liquid region a
  few cells thick is decoupled from its own pressure and the solve converges
  immediately to something that is not divergence-free.
- **The transfers must be staggered to match.** With MAC operators but
  cell-centred scatter and gather, the half-cell mismatch made the entire field
  drift: paint slid steadily downward with no gravity anywhere in the code
  (−0.18 over 40 frames). Sampling each component at its own point fixed it
  (−0.0016).

Measured: interior divergence 99% removed (0.044 → 0.00034), and colliding
streams now push each other aside instead of passing through — cross-axis spread
0.0149 without the solve, 0.0837 with.

### The grid was 16x too fine, so no particle ever met another

Comparing against `FLIPsphere_v001.html` in the Experiments repo turned this up,
and it is the reason the medium still did not look like FLIP after the solver
itself was made correct.

FLIP is a particle method that borrows a grid so that particles can feel each
other. The pressure solve couples a **cell** to its neighbours; a cell holding
one particle therefore couples that particle to nothing. It needs several
particles per cell — the literature says four to eight in 2D — and MaxPaint was
running the particle solver on the ink grid:

| | cells | particles in play | per cell |
|---|---:|---:|---:|
| ink grid, as shipped | 589,348 | ~3,000 | **0.005** |
| what FLIP needs | | | 4–8 |

Every particle was alone in its own cell, surrounded by cells the free-surface
condition marks as air and pins to zero pressure. So each particle was an island
with a Dirichlet boundary on all four sides, and the projection — the entire
reason for having a grid — could not transmit anything between them.

Measured directly, by firing two streams head-on and comparing the spread with
the pressure solve against without it:

| grid | particles per occupied cell | streams pushed apart |
|---:|---:|---:|
| 32² | 128 | 1.3x |
| 64² | 64 | 3.3x |
| 128² | 24 | 4.1x |
| **192²** | **13** | **7.1x** |
| 256² | 8 | 6.0x |
| 384² | 4 | 4.7x |
| 512² | 2.5 | 3.6x |
| 768² (the ink grid) | 1.2 | **2.6x** |

Coupling peaks around eight to thirteen particles per occupied cell and falls
away on both sides — too coarse and a cell is bigger than the flow, too fine and
particles stop sharing cells at all. The app was sitting at the bottom right of
that table.

The particle solver now has **its own grid**, a 192 cell budget shaped to the
canvas — 292x124 against the ink grid's 1174x502 — with a Coupling slider on the
brush. Rendering is unaffected: particles are still drawn as point sprites at
full ink resolution, so only the velocity field got coarser, not the picture.

Sweeps needed scale with grid width, so 20 sweeps on a 1174-wide grid was
drastically under-solved. On the coarse grid the same 20 removes 92.6% of
interior divergence and 40 removes 99.1% — so it now runs 40, and still costs
**8x less** than the under-solved version did (2.9M cell-sweeps a frame against
23.6M).

Three checks guard it: that the solve couples particles when a cell holds
several, that it barely couples them at one per cell (the failure this
replaced), and that the shipped `flipRes` sits inside the band that was measured
to work.

What did **not** come across from FLIPsphere, having checked: it uses a
collocated grid with centred differences and no free surface at all, because its
fluid covers the whole sphere. MaxPaint paints onto blank canvas, so it needs the
free surface, and the staggered operators were already measured to be what a thin
free surface requires. Its `uValue` counterpart clears pressure outright each
frame where MaxPaint keeps 60% — but that is a decayed warm start, not a constant,
and is fine as it stands.

### Density, not count — and it has to survive the brush-size slider

The other half of the same ratio, and the other thing FLIPsphere gets right: it
runs a lot of particles. With the coarse grid in place, a real stroke at the old
default of 32 particles per dab landed at 3.7 per occupied cell — better than
1.2, still short of the 8–13 where coupling peaks.

Worse, the count was fixed per dab while the footprint was not, so the medium
thinned as the brush widened and at a wide brush was back to one particle per
cell however coarse the grid was:

| brush size | fixed 32 per dab | derived from footprint |
|---:|---:|---:|
| 0.010 | 9.6 | 5.4 |
| 0.023 | 3.7 | 9.9 |
| 0.060 | **1.0** | 12.1 |

Emission is now a **density**: the count comes from how many grid cells the dab
actually covers, so the number that matters holds while Brush size changes only
the size of the mark. The slider reads `Density: 40 (261 particles per dab)` and
the FLIP presets carry densities instead of counts.

The defaults then went up across the board, because the medium was still too
thin in the hand, and then again to the settings that came back from an actual
session with it:

| | was | now |
|---|---:|---:|
| Detail / Ink detail | 768, 1x | **512, 2x** |
| Drag | 3.0 | **0** |
| Set speed / Hold | 10, 0 s | **3.9, 1.20 s** |
| Load | 1.0 | **3.47** |
| Particle grid | 192 | **160** (244×104) |
| Density | 15 | **120** |
| Particle pressure | 40 | **160** sweeps |
| Motion inheritance | 0.92 | **60%** |
| Cohesion | 12 | **1** |

Two of those are worth explaining because they run against what the measurements
above suggested. **Density 120** puts 42–60 particles in every occupied cell,
far past the 8–13 where the grid sweep peaked — but that sweep varied the *grid*
at a fixed particle count, so its high-density rows were all too-coarse grids. Re-measured
at a fixed good grid, coupling is flat to better from 5 up to 60 per cell
(1.99x, 2.12x, 3.80x, 3.61x at 4.9, 18, 33, 60), so 120 is not past a cliff,
only more expensive. The FLIP blend is now the **Motion inheritance** slider, 0–150%, default 60%.
It is how much of its own motion a particle keeps instead of taking the grid's:
0 is pure PIC, 100% pure FLIP, and above that it *extrapolates* —
`vel = gNew + r·(v − gOld)` — keeping more than all of it. Pure PIC reads as a
thick body, pure FLIP as a lively spray; the interesting settings are between.

Past 100% is the noisy end of an already noisy scheme, so it is measured rather
than assumed. Nothing goes non-finite anywhere on the range and peak speed stays
inside the CFL clamp, but it stops being useful before the top: paint is
markedly livelier at 120% (spread 0.216 against 0.021 at 40%), while at 150% the
extrapolation oscillates enough that velocity swings through zero, reads as
settled, and bakes — 1487 particles still live at 120%, **9** at 150%. Three
checks record exactly that, including the last one, so the useful ceiling is a
measured fact rather than folklore.

The `Wet Paint` preset carries the same values, since it is the one the panel
opens on and it would otherwise undo the defaults the first time it was picked.

A bug turned up next to it: emission scattered its particles in UV while every
other brush measures its radius in world units, so on a 2.34 canvas a drip dab
was an ellipse 2.34x wider than tall while claiming to be the same size as the
gas brush's. It scatters in world units now.

### Cohesion is what makes it clump

Removing gravity left nothing to gather the paint. A liquid clumps because
something pulls it together, and with no "down" that has to be surface tension —
so each particle is pulled up the density gradient. Neighbouring paint attracts,
droplets form, and the pressure solve stops them collapsing.

Measured: the same paint occupies **434 cells → 98**, peak density **58 → 169**,
without collapsing to a point or running away.

Two bugs surfaced getting there:

- **The force scaled with particle count.** Mass is an accumulated weight, so a
  raw gradient meant the same cohesion setting behaved completely differently at
  either end of the particle slider — and at high counts it flung paint apart
  instead of gathering it. It is normalised by local density now, and particles
  carry the same CFL clamp as the grid.
- **The density field was skewed diagonally.** Cell mass was averaged from the
  two staggered weights, one sampled half a cell down and the other half a cell
  left, so its gradient carried a constant diagonal bias. Cohesion followed that
  bias and walked the entire liquid into the top-right corner. Reconstructing a
  properly cell-centred mass from each face pair fixed it.

A third came out of the same hunt: `divergence_flip` applied the wall condition
to the wrong face. On a staggered grid the x component of cell *c* sits on its
**left** face, so the wall belongs at `c.x == 0`; it was being zeroed at the far
edge instead, which both missed the left wall and clobbered an interior face.

There is **no gravity**, and the tests assert it in the form that is actually
true: undisturbed paint does not drift at all (0.00000 over 60 frames), and a
throw stays on its axis.

### How many particles fit

Hardware-specific, and not something to reason out from a desk: the cost is
dominated by the atomic scatter in particle-to-grid and by fill rate in the
draw, and both vary by an order of magnitude across mobile GPUs. The FLIP tool
panel has a **How many particles fit?** button that ramps the count and reports
the largest that holds 60fps on the device in hand. The pool is 400,000; unused
capacity costs nothing, since only the filled span of the ring buffer is walked.

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
| GLSL, every shader | `glslangValidator` against the ES 3.1 spec | passing |
| Solver behaviour | Executed on a real GLES 3.1 driver (EGL surfaceless + Mesa llvmpipe) | 49 solver + 14 FLIP checks passing |
| Kotlin, all sources | `kotlinc` against the real Android 14 framework classes (`org.robolectric:android-all`, from Maven Central) | compiles clean |
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
