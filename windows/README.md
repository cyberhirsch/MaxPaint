# MaxPaint for Windows

A desktop host for the SAME compute shaders the Android app ships:
desktop OpenGL 4.3 has the compute model GLES 3.1 was derived from, so
`android/app/src/main/assets/shaders/flip_*.comp` run verbatim -- the
loader only rewrites the `#version` line and drops standalone precision
statements. The Python harness in `../tools/` is the behavioural
reference.

Mouse paints (the streamed pour with motion inheritance). Keys tune the
medium and the window title shows the current values:

    W/S flow    E/D settle    R/F motion inheritance
    T/G drag    Q/A cohesion  C clears the canvas

## Building

Requires CMake 3.20+, a C++17 compiler, and an OpenGL 4.3 GPU/driver
(any desktop GPU from the last decade). GLFW is fetched automatically.

    cd windows
    cmake -B build -DCMAKE_BUILD_TYPE=Release
    cmake --build build --config Release

CI (`.github/workflows/windows.yml`) builds on every push touching
`windows/` or the shared shaders, and uploads a zip containing
`maxpaint.exe` plus the shaders directory -- unzip anywhere and run.
