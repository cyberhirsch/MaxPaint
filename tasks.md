# Tasks

Improvements found in a full read of the repo on 2026-09-05, sorted by how hard
each one is for a model to land unattended. "Difficulty" is about the work, not
the value: how much of the codebase has to be understood, whether the result
can be verified headlessly, and how much judgement the change needs.

Every task lists where it lives and what "done" looks like, so any one of them
can be picked up on its own. Tasks inside a tier are ordered easiest first.

Verification: `tools/verify.sh --res 128` and `python3 tools/verify_flip.py`
are the referees for anything that touches a shader or the solver. Kotlin only
compiles in CI. Anything marked **device** can only be confirmed on a phone.

---

## Tier 1 — trivial

One file, a few lines, verifiable by reading.

1. **Fix the stale docs that contradict the code.**
   Where: `README.md:3`, `README.md:7`, `README.md:61` (the "not built" table
   lists undo, redo and PNG export, all built), `README.md:975` and
   `android/.../FluidSim.kt:17` (both say velocity is in UV units; it is in
   world units), `FluidSim.kt:91` (says 40 particle sweeps; the value is 160).
   Done when: no sentence in the README or a docstring states something the
   code does not do.

2. **Report the allocated grid in the sweep, not the requested budget.**
   Where: `Benchmark.kt` `Result` and `report()`; the clamp is in
   `FluidSim.allocate` at `FluidSim.kt:384`.
   Why: on a 4096 max-texture device at 2x ink detail on a 2.34 screen, the
   1536 and 2048 rows both allocate 2048 x 874 and print as different rows.
   Done when: each row prints `simW x simH`, and a row whose grid equals the
   previous row's is skipped.

3. **Make the HUD report slow frames.**
   Where: `FluidRenderer.kt:159` clamps `dt` before `updateStats(dt)`.
   Done when: stats use the unclamped frame time and only the simulation
   step gets the clamped one.

4. **Stop the Log button's toast from claiming it just wrote the file.**
   Where: `MainActivity.showReport`. The file is written before the dialog;
   the toast on "Log" says "Written to ...".
   Done when: the toast reflects what the button did, or the file is written
   on the button and timestamped instead of overwritten.

5. **Use the display refresh rate in the sweep's frame budget.**
   Where: `Benchmark.Result.fastEnough` and `ParticleBenchmark.Row.holds60`
   hard-code 16.6 ms / 20 ms.
   Done when: the budget comes from `Display.refreshRate` and the report
   header says which rate it judged against.

6. **Confirm before the global clear.**
   Where: `MainActivity.buildRail`, the `clr` button. Wiping a layer is
   undoable and deleting one asks; clearing everything does neither.
   Done when: `clr` asks first, the same way Sweep and Delete do.

7. **Stop the drip panel re-quantising Drag on open.**
   Where: `MainActivity.slider` calls `onChange(initial, label)` to set the
   label, which writes the value back through the percent mapping
   (0.25 becomes 0.2485; Honey's 1.6 becomes 1.56).
   Done when: the slider has separate format and apply callbacks and opening
   a panel changes no value.

8. **Gate the dormant texture reads in the display shader.**
   Where: `display.frag` samples `uFlip` and `uNib` unconditionally; only
   water is gated by `uShowWater`.
   Done when: both reads are behind uniforms the renderer sets from
   `flip.inUse` and `nibActive`.

---

## Tier 2 — easy

One or two files, contained logic, verifiable in the harness or by reading.

9. **Replace the sine hash in emission with an integer hash.**
   Where: `flip_emit.comp` `hash()` and `Shaders.metal` `hash1()`. Arguments
   reach ~60k, where mobile GPUs lose sine precision. ES 3.1 has full
   integer ops.
   Done when: both backends use the same integer hash, the emission checks in
   `verify_flip.py` pass, and the Android/Metal jitter streams match.

10. **Round instead of truncate in the fixed-point scatter.**
    Where: `flip_p2g.comp` (`int(v * w * SCALE)`) and `Shaders.metal`
    `addAcc`. Truncation toward zero under-counts slow motion, which acts as
    extra drag on settling paint.
    Done when: both use round-to-nearest and the FLIP checks still pass.

11. **Check for float render targets at startup.**
    Where: `FluidRenderer.hasComputeSupport`. Clears, particle draws and
    readbacks render into 16F/32F textures, which need
    `GL_EXT_color_buffer_float` on ES 3.1. Also query
    `GL_ALIASED_POINT_SIZE_RANGE` and clamp Drop size to it.
    Done when: a device without the extension shows a message instead of a
    black canvas, and the Drop size slider's maximum comes from the query.

12. **Apply the active layer's opacity to wet paint.**
    Where: `display.frag:34` scales only `uBackground` by `uActiveAlpha`.
    Live dye, drops, nib and water ignore it, so wet paint on a half-opacity
    layer pops when it sets and a hidden layer still shows wet paint.
    Done when: every term that belongs to the active layer is scaled, and a
    harness check composites a live+baked pixel at 50% and gets one value.

13. **Allocate the per-medium fields lazily.**
    Where: `FluidSim.allocate` creates `water` (RGBA32F, two copies), `age`,
    `nibInkField` and `flipInk` unconditionally; about 75 MB at the default
    canvas on a 2.34 screen. The compute passes are already lazy.
    Done when: each is created on first use, `vramBytes()` and `clear()`
    handle the absent case, and the HUD VRAM figure drops on a fresh canvas.
    Also: `age` and `flipDensity` use RGBA16F for one channel; R16F halves
    them if the driver accepts it for image store.

14. **Pin the sweep's inputs.**
    Where: `Benchmark.run`. The scripted stroke reads the selected brush's
    size, `inkPerStroke`, `velocityGain`, `vorticity`, `dyeDissipation`,
    `velocityDrag`, `bakeRate`, `settleMinAge`, `settleSpeed`, so two phones
    with different sliders get different verdicts.
    Done when: the run saves those, sets fixed values, restores them in a
    `finally`, and the report header prints the values it ran with.

15. **Add a zero-particle baseline to the particle benchmark.**
    Where: `ParticleBenchmark`. Its times include the gas solve at the
    current resolution.
    Done when: the first row is 0 particles and the report shows cost above
    baseline per count.

16. **Do not paint with the second finger of a two-finger tap.**
    Where: `MainActivity.handleTouch`. Both pointers stroke during the tap
    window, so a freeze leaves two dots and bakes them.
    Done when: samples from a second pointer are held for the tap window and
    dropped if the gesture turns out to be a tap. **device**

---

## Tier 3 — moderate

Several files or a new mechanism, but the design is settled and the harness
can check it.

17. **Measure ink conservation in the sweep the way the harness does.**
    Where: `Benchmark.run` and `scriptedFrame` at `Benchmark.kt:91`. The
    stroke injects every frame while the bake drains the live field, so the
    ink column is injection minus bake minus dissipation and the verdict
    judges the bake sliders. Reference: `mass_drift` at
    `tools/verify_solver.py:743`.
    Done when: the stroke runs during warm-up only, dissipation is zero and
    the bake is off for the measured frames (or live plus baked ink is
    counted), and the ink column at 120 sweeps is within a few percent.

18. **Report steady-state convergence alongside the cold solve.**
    Where: `FluidSim.measureConvergence`, `Benchmark`. The app warm-starts, so
    the cold number understates what the artist sees.
    Done when: a second column gives divergence after a normal step over
    divergence before projection, averaged over the measured frames.

19. **Run the sweep across frames with progress and cancel.**
    Where: `Benchmark.run` is blocking inside `onDrawFrame`; the screen
    freezes for seconds. Same for `ParticleBenchmark`.
    Done when: the benchmark is a state machine advanced once per frame
    callback (still `glFinish`-timed), the HUD shows "sweep 3/8", a Cancel
    button works, and a row more than twice the budget ends the run early.

20. **Make the sweep non-destructive.**
    Where: `FluidRenderer.runBenchmark` reallocates and clears. Read each
    layer back to a CPU buffer before the run, restore after, drop the
    confirm dialog's warning.
    Done when: a painting with three layers survives a sweep pixel for pixel
    (undo history may be dropped and said so).

21. **Pass effective brush values as parameters instead of mutating state.**
    Where: `FluidSim.stroke` at `FluidSim.kt:498` overwrites `splatRadius`
    and `inkPerStroke` and restores in `finally`. A slider moved mid-stroke is
    reverted; a tool switched mid-stroke writes the old brush's size into the
    new brush's slot.
    Done when: `strokeInner` and the per-brush methods take radius and ink as
    arguments and no field is written during a sample.

22. **Publish an immutable layer summary for the UI thread.**
    Where: `MainActivity.pollRenderer` and `refreshLayerRail` copy
    `sim.layers` while the GL thread inserts and removes.
    Done when: the GL thread writes a `@Volatile` list of (name, visible,
    opacity, active) and the UI only reads that.

23. **Letterbox the canvas when the view aspect differs.**
    Where: `display.vert` is a full-screen triangle; `MainActivity.strokeTo`
    maps view pixels straight to UV. Multi-window and foldables stretch the
    painting.
    Done when: the display pass fits the canvas inside the view with bars, and
    touch maps through the same rectangle. **device**

24. **Give the drip stroke an effect on poured paint.**
    Where: `FluidSim.strokeInner` at `FluidSim.kt:557` pushes momentum into
    the gas grid, which particles never read; with Flow at 0 the brush does
    nothing. Decide: impulse on the particle grid after P2G, or per-particle.
    Done when: dragging through a resting puddle with Flow 0 moves it, a
    harness check pins that, and the energy checks still pass.

25. **Fix the iOS/Android drift in the particle port.**
    Where: `ios/MaxPaint/FlipSystem.swift:23` (30 sweeps vs 160),
    `Renderer.pour` (radius 0.02 vs Android's `splatRadius * 0.5`, so four
    times the count per dab).
    Done when: both backends run the same numbers, and one place defines them.

26. **Restructure the README.**
    Where: `README.md` is a 1,000-line lab notebook that starts "for Android"
    and "M0, M1 done".
    Done when: the README is a short current-state page (what it is, what is
    built, how to build, how to verify), and the measured findings move to
    `docs/findings.md` with the same headings.

---

## Tier 4 — hard

Cross-cutting refactors or new infrastructure. The harness catches
regressions but the design needs judgement.

27. **Move stroke stamping out of the activity.**
    Where: `MainActivity.strokeTo` holds dab spacing, carry, impulse share and
    the calibration. PRD 7.9 says paint behaviour must not live there, and
    the iOS port needs it next.
    Done when: a platform-neutral `StrokeStamper` in the sim package takes
    (prev, cur, pressure) and returns dabs, has JVM unit tests for the
    path-length and event-rate invariants that `verify_solver.py` currently
    mirrors, and the activity only feeds it touch samples.

28. **Split FluidSim by medium.**
    Where: `FluidSim.kt` is 1,380 lines holding gas, bake, watercolor, nib,
    smear, particle orchestration, layers, undo and measurement.
    Done when: gas solver, watercolor, nib, `LayerStack` and `UndoHistory`
    are separate classes with the same public surface, behind one facade, and
    the harness and CI build are unchanged.

29. **Share constants across Kotlin, GLSL, Metal and Python.**
    Where: the fixed-point scale (4096), separation stride (13), cohesion
    slider mapping (0.0025), separation budget (600 000), the preset table in
    `verify_flip.py`, and the regex scraping of `DAB_CALIBRATION`, `flipRes`
    and `particlesPerCell` from source.
    Done when: one JSON (or generated header) is the source, every backend and
    the harness read it, and the regexes are gone.

30. **Batch a frame's dabs into one pass per field.**
    Where: `FluidSim.splat`, `force`, `liftSetPaint`, `solvent`. Every dab is
    a full-canvas ping-pong pass: three for gas, two for a force brush with
    pickup; a fast swipe stamps dozens per event.
    Done when: dabs queue into an SSBO and one dispatch per field applies
    them all; a full-canvas swipe costs the same as one dab, and the stroke
    interpolation checks pass bit-identically or within a stated tolerance.

31. **Suspend the solver when idle.**
    Where: `FluidSim.isIdle` at `FluidSim.kt:892` returns false;
    `FluidRenderer` renders continuously. PRD 7.5.
    Done when: with no input and no live fluid (max velocity and live ink
    below thresholds from the stats pass, watercolor dry, no live particles,
    nib dry) the view switches to render-when-dirty, wakes on touch, and a
    resting canvas draws no power beyond the display. **device** for the
    power number; the state machine can be unit-tested.

32. **Add a differential test for the Metal port.**
    Where: `ios/`, `.github/workflows/ios.yml`. The Python harness cannot
    reach Metal.
    Done when: a command-line Swift target runs the harness's pour and
    energy scenarios and dumps particle state, CI compares against a
    checked-in dump from the GLES backend, and the drift in task 25 would
    have failed it.

---

## Tier 5 — research

Correct answer unknown in advance; needs measurement on real GPUs and may
not pay off.

33. **Cut the dispatch count of the particle solve.**
    Where: `FlipSystem.solve` issues 320 half-sweep dispatches with a barrier
    each on a 244 x 104 grid; 334 passes per frame with particles active
    against 69 for the gas. Likely submission-bound.
    Options: several red-black sweeps per dispatch in shared-memory tiles with
    halos; the whole grid in one workgroup with `barrier()` between sweeps;
    or fewer sweeps if 40 measures the same. Must stay bit-compatible with
    the reference order or re-pin the cohesion baselines.
    Done when: the particle benchmark shows the change on two GPU vendors and
    every FLIP check passes. **device**

34. **Multigrid for the gas pressure solve.**
    Where: `pressure_rb.comp`, `FluidSim.solvePressure`. The README's own
    measurement: sweeps scale with the square of grid width, so equal quality
    costs 16x per doubling. Required above ~1024 per the PRD.
    Done when: a V-cycle removes 90% of divergence in a fixed cost at 256,
    512 and 1024 in `compare_solvers.py`, the ink-drift figure at the default
    sweep budget drops accordingly, and the sweep verdicts move. **device**

35. **Conservative dye advection.**
    Where: `advect.comp`; README "Under-solving manufactures ink". Sustained
    stirring inflates ink ~1.5x; MacCormack measured worse. Candidates:
    flux-based or mass-renormalised advection.
    Done when: the vortex check's ink inflation bound can drop from 1.6x to
    near 1.0 without the sharpness loss showing on device. **device**
