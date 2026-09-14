# MaxPaint for Windows

A desktop host for the SAME compute shaders the Android app ships:
desktop OpenGL 4.3 has the compute model GLES 3.1 was derived from, so
`android/app/src/main/assets/shaders/flip_*.comp` run verbatim -- the
loader only rewrites the `#version` line and drops standalone precision
statements. The Python harness in `../tools/` is the behavioural
reference.

A panel on the left carries the tools. **Paint** is the streamed pour
with motion inheritance, with the Android presets (Wet Paint, Splatter,
Fling, Honey, Mercury) and every slider from the Flip panel. **Glitch**
is pixel sorting under the brush: runs of pixels in a brightness band get
sorted along each row or column, everything else holds its place.
**Open...** loads a picture as set paint (dropping a file onto the window
or passing its path on the command line does the same), **Save PNG**
writes the canvas next to the executable, **Clear** starts over.

**Image relief** (under the Paint tool): the app derives height, normals
and ambient occlusion from the layer as if brightness were height --
switch **View** to see each map -- and the Relief slider makes that
height field the paint's gravity, so it runs downhill on a photo.
True depth would need a neural model; these are what the pixels alone
can say.

Keys still work: W/S flow, E/D settle, R/F motion inheritance, T/G drag,
Q/A cohesion, [ ] brush size, 1/2 tool, C clear.

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
