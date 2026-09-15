# MaxPaint for Windows

A desktop host for the SAME compute shaders the Android app ships:
desktop OpenGL 4.3 has the compute model GLES 3.1 was derived from, so
`android/app/src/main/assets/shaders/flip_*.comp` run verbatim -- the
loader only rewrites the `#version` line and drops standalone precision
statements. The Python harness in `../tools/` is the behavioural
reference.

A panel on the left carries five tools. **Gas** is the Eulerian medium the
Android app calls its hero, running the same passes in the same order: a
velocity field you push pigment through, made incompressible again every frame,
with vorticity, dye dissipation and a bake that moves settled paint onto the
layer. Freeze now sets everything at once and Thaw lifts it back into the air;
Stir only pushes what is already there without adding ink. **Nib** is pen and
charcoal -- the mark goes into its own field where the fluid cannot smear it,
drawn as a capsule from the previous sample so a fast stroke stays a line, and
a capillary pass creeps it into the paper and dries it onto the layer. Both
keep running after you switch brushes, because paint has to go on settling.

**Paint** is the streamed FLIP pour with motion inheritance, carrying the
Android presets (Wet Paint, Splatter, Fling, Honey, Mercury) and every slider
from the Flip panel. **Glitch**
is pixel sorting under the brush: runs of pixels in a brightness band get
sorted along each row or column, everything else holds its place. Four more
modes share that brush and its plumbing: **Channel drift** pulls red and blue
apart along the stroke axis and leaves green where it was, so edges grow the
fringes of a misregistered scan -- it shows on a grey photograph too, because
the separation is what makes the colour. **Block shuffle** reads the picture on
a grid anchored to the canvas and lets each block fetch from a displaced one;
set Block to 8 for a JPEG's own lattice and overlapping dabs keep breaking on
the same seams. **Slit-scan** extrudes the single line under the centre of the
brush across the disc, and holding still reaches further out with every stamp.
**Bit crush** quantises each channel to a few levels with a 4x4 ordered dither
deciding which way a value rounds, so flats break into crosshatch rather than
banding. `M` cycles them.

**Edge falloff** serves every mode: toward the rim of the dab the effect eases
off, so a mark thins out instead of ending on a circle, and 0 restores the hard
edge. It does this by doing less work rather than by blending against the
original -- the sort narrows its band so fewer pixels qualify, drift and
shuffle shorten their displacement, slit-scan pulls a shorter distance, crush
climbs back toward continuous tone. A dab stays made of pixels that were
already in the picture.

**Reaction** is Gray-Scott reaction-diffusion, and it is the one brush that
does not draw. It seeds a disturbance under the cursor -- substrate down,
reagent up -- and the chemistry grows coral, labyrinth or leopard spots out of
that seed while you watch, spreading until you stop it. Feed and kill decide
which pattern; the window they live in is narrow enough that the **Pattern**
presets matter more than the sliders. **Run** is the brake and **Reset
reaction** clears the field. **Image steer** lets the picture underneath shift
the feed and kill rates by its brightness, so one regime grows on lit skin and
another in shadow and the pattern finds the face on its own. The result lies
over the photograph as ink rather than replacing it -- **Ink from** and **Ink
to** set where the reagent starts reading as black -- and **View > Reaction**
shows the raw field.

**Open...** loads a picture as set paint (dropping a file onto the window
or passing its path on the command line does the same), **Save PNG**
writes the canvas next to the executable, **Clear** starts over.

**Save painting** writes the painting rather than a picture of it: every layer
at its own precision -- half floats, exactly what the textures hold, so a
reload is bit-for-bit -- with its name, opacity and visibility, plus the
reaction field if one is alive and the settings that were in play. **Open painting** brings it back, and a
`.maxpaint` file dropped on the window or passed on the command line opens the
same way. Wet particles are not kept; they are a stroke in progress.

**Undo** and **Redo** (ctrl+Z, ctrl+Y) step through whole snapshots of the set
layer. The newest stroke is committed only when something needs it to be --
the next stroke starting, or an undo -- because committing at mouse-up would
catch the paint mid-dry, the FLIP layer going on baking for seconds after the
hand stops. Depth is whatever fits a memory budget, so a big canvas gets fewer
steps rather than a gigabyte of them.

**Layers** (another fold) stacks as many as you want. Brushes paint into the
selected one, the tick hides a layer without discarding it, and each carries
its own opacity; the list reads top-down the way the picture does, which is the
reverse of the order it composites in. Undo steps remember the layer they were
taken on and go back there. The stack is flattened once a frame -- skipped
entirely while there is only one layer, which is the common case -- and that
flattened picture is what the screen shows, what Save PNG writes, and what the
property maps read, so Relief on a photo on one layer steers paint poured onto
another.

**Canvas & window** (a fold in the panel) separates the two things that
used to be one. The window is freely resizable, and the canvas keeps its
own resolution inside it -- centred, scaled to fit, on a dark surround.
Set the canvas from the preset list or type a size; **Match window**
takes the window's current pixels, **Fit to canvas** does the reverse.
Resizing rescales the set paint into the new canvas and empties the wet
pool, since particles hold positions in canvas space and cannot follow.
Up to 8192 px a side and 32 megapixels. **Save PNG** writes the canvas
at its own resolution, not at the size of the window showing it.

**Image relief** (under the Paint tool): the app derives height, normals
and ambient occlusion from the layer as if brightness were height --
switch **View** to see each map -- and the picture then drives the brushes.
**Relief** makes that height field the paint's gravity, so it runs downhill on
a photo. **Dry in shade** shortens the settle time where the occlusion map
says the picture is buried, so pigment sets in the creases while the open
planes are still wet. **Ink from height** charges each drop by the brightness
it was laid on, so a stroke loads on the lit planes and runs thin over the
dark ones. The pixel sort reads the same maps: **Edge bound** lets the
picture's own contours stop a run, so silhouettes survive while the smooth
interiors they enclose liquefy, and **Axis from surface** lets the layer's
slope choose the sort direction. True depth would need a neural model; these
are what the pixels alone can say.

Keys still work: W/S flow, E/D settle, R/F motion inheritance, T/G drag,
Q/A cohesion, [ ] brush size, 1-5 tool, M cycles the glitch mode,
ctrl+Z / ctrl+Y undo and redo, C clear.

## Building

Requires CMake 3.20+, a C++17 compiler, and an OpenGL 4.3 GPU/driver
(any desktop GPU from the last decade). GLFW and Dear ImGui are fetched
automatically.

    cd windows
    cmake -B build -DCMAKE_BUILD_TYPE=Release
    cmake --build build --config Release

CI (`.github/workflows/windows.yml`) builds on every push touching
`windows/` or the shared shaders, and uploads a zip containing
`maxpaint.exe` plus the shaders directory -- unzip anywhere and run.
