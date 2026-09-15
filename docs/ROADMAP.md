# MaxPaint roadmap

Where the project stands and where it goes next. The PRD
([PRD.md](PRD.md)) says what the app is; this says what gets built, in
what order, and why. It is a living document: items move as device
testing changes the priorities, which it does constantly.

## Principles that survived contact with the device

- **Measure before fixing.** Every solver change in the history came with a
  number; every fix became a harness check that reads the shipped
  constants from source. `tools/verify_solver.py` and
  `tools/verify_flip.py` are the behavioural spec — any backend that
  claims to be MaxPaint has to pass them.
- **One shader source.** The GLES 3.1 compute shaders in
  `android/app/src/main/assets/shaders/` are the truth. Windows runs them
  verbatim (version header rewritten at load); iOS mirrors them in Metal
  kernel for kernel. New brush passes are written once, there.
- **The stroke is a path, the pour is a clock.** Marks are distance-sampled
  or time-sampled, never event-sampled; the digitiser's report rate must
  never show in the paint.
- **Nothing injects energy.** With no input, kinetic energy falls. Any
  force is a bounded relaxation toward a target, never an accumulation.
- **A canvas has no up** — unless an image lends it one (see Relief).

## Where we are (September 2026)

| Area | State |
|---|---|
| Gas brush (Eulerian) | Shipped; the hero medium, with bake lifecycle, presets, heat view |
| FLIP brush (particles) | Rewritten from the reference 2D FLIP; separation, drift compensation, streamed pour with motion inheritance, age-based settling |
| Nib, watercolor, vortex, solvent, smear, freeze/thaw | Shipped on Android |
| Glitch brush | Pixel sorting on Android and Windows; channel drift, block shuffle, slit-scan and bit crush on Windows, sharing the same back-buffer plumbing. Every mode has an edge falloff |
| Reaction-diffusion brush | Gray-Scott on Windows: presets, image-steered feed/kill, ink thresholds, raw-field view |
| Layers, undo/redo, PNG export | Android |
| Image import | Android (system picker) and Windows (dialog / drag-and-drop) |
| Image properties | Height, normals, ambient occlusion derived from the layer; Relief flow (Windows) |
| Platforms | Android APK, Windows exe, iOS simulator app — all built by CI from one repo |

## Near term — the next few builds

These are the items most likely to be picked next; they are small and each
one has a place to land already.

1. **Relief and properties on Android.** The props pass and the Relief
   slider exist only in the Windows host; the shader is shared, so this is
   host wiring.
2. **Glitch modes on Android.** The four modes added on Windows -- channel
   drift, block shuffle, slit-scan, bit crush -- are shared shaders already;
   the Kotlin host needs a mode selector and the new uniforms. It also still
   needs `uRadius` on the rect copy, without which every dab leaves a white
   square where the bounding box overran the disc.
3. **Property-driven glitch**: pixel sort with runs bounded by edges rather
   than a brightness band (keeps silhouettes, liquefies interiors); sort
   direction along the normal.
4. **Property-driven paint**: settling masked by occlusion (paint dries in
   the shade first); emission density from height; Relief for the gas
   brush as a force field, not just for particles.
5. **Windows parity**: undo/redo, layers, the gas and nib brushes. The
   desktop build is the fastest tuning loop and should not lag the phone.
6. **Save/load the painting itself**, not just a PNG: the layer stack with
   its live state, so a session can be resumed.

## Medium term

- **Datamosh**: freeze a region's motion vectors from the fluid velocity
  and keep re-applying them to the layer — codec corruption without the
  codec.
- **Photo as liquid**: thaw the imported picture into the gas solver with
  its colour, so a face flows when stirred (thaw exists for ink today).
- **Marbling comb**: N stir points spaced along a stroke; suminagashi
  chevrons from parameters on the existing force brush.
- **Spray, crackle and drip modes** for FLIP: cone scatter with high drag;
  particles that only settle where the layer is already dark; a per-brush
  gravity toggle so poured paint runs down the screen.
- **Erosion**: brightness as terrain, a hydraulic-erosion step along the
  stroke carves channels through the photo. The height map already
  exists.
- **True depth** on Windows via ONNX Runtime and a small monocular depth
  model (Depth Anything class, ~25 MB), written into the same property
  texture so every property-driven brush gets depth for free. Not
  on-shader, not on the phone in the first pass.
- **Colour.** Everything is black ink today. Per-brush colour, and the
  glitch/property pipeline is already colour-aware.

## Long term

- **iOS beyond the simulator**: presets and the full panel in SwiftUI, PNG
  export, signing and TestFlight once there is an Apple developer
  account. The Metal solver is done; the app around it is not.
- **Pressure and tilt as first-class inputs** on the phone and pen tablets:
  the plumbing carries them; few brushes use them yet.
- **Mac build** from the iOS project (Catalyst or a native target — the
  Metal code is shared).
- **Performance budget per device**: the resolution sweep and particle
  benchmark exist; turn them into an automatic first-run calibration.
- **Recording and replay**: the sim is deterministic (PRD FR-20, verified);
  a stroke log is a painting you can re-render at any resolution or hand
  to someone else.

## Explicitly deferred

- Contact-area / fingerprint brushes: the device reported no contact
  geometry (size, major/minor all absent, pressure constant). Dropped
  until a device that reports it is on the bench. Lesson kept: presence of
  an API is not usefulness — check whether the number moves.
- Colour management, printing, cloud sync: not before the mediums are
  right.

## How things get in

A feature lands when it has a shader (shared), a host on at least one
platform, and a harness check where the behaviour is measurable. Device
screenshots decide tuning; a diagnosis that does not match the screenshot
is wrong, whatever the theory says.
