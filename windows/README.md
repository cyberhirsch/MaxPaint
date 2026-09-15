# MaxPaint for Windows

A desktop host for the SAME compute shaders the Android app ships:
desktop OpenGL 4.3 has the compute model GLES 3.1 was derived from, so
`android/app/src/main/assets/shaders/flip_*.comp` run verbatim -- the
loader only rewrites the `#version` line and drops standalone precision
statements. The Python harness in `../tools/` is the behavioural
reference.

A panel on the left carries the three tools. **Paint** is the streamed pour
with motion inheritance, with the Android presets (Wet Paint, Splatter,
Fling, Honey, Mercury) and every slider from the Flip panel. **Glitch**
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
switch **View** to see each map -- and the Relief slider makes that
height field the paint's gravity, so it runs downhill on a photo.
True depth would need a neural model; these are what the pixels alone
can say.

Keys still work: W/S flow, E/D settle, R/F motion inheritance, T/G drag,
Q/A cohesion, [ ] brush size, 1/2/3 tool, M cycles the glitch mode, C clear.

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
