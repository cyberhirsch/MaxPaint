#!/usr/bin/env bash
# One-command verification of the M0 spike, with no Android SDK required.
#
#   1. every GLSL shader against the ES 3.1 spec (glslangValidator)
#   2. the solver itself, on a real GLES 3.1 driver via EGL + Mesa llvmpipe
#
# Kotlin compilation is checked separately; see README.md > Verification.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== GLSL =="
fail=0
for f in app/src/main/assets/shaders/*.comp; do
    glslangValidator -S comp "$f" > /dev/null || { echo "  FAIL $f"; fail=1; }
done
glslangValidator -S vert app/src/main/assets/shaders/display.vert > /dev/null || fail=1
glslangValidator -S frag app/src/main/assets/shaders/display.frag > /dev/null || fail=1
[ $fail -eq 0 ] && echo "  all shaders compile against the ES 3.1 spec"
[ $fail -eq 0 ] || exit 1

echo
echo "== Solver =="
EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 python3 tools/verify_solver.py "$@"
