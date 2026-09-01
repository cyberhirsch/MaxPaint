# MaxPaint iOS (Metal port)

First milestone of the iOS/Mac port. The FLIP solver is ported
kernel-for-kernel from the Android GLES compute shaders
(`android/app/src/main/assets/shaders/flip_*.comp`) into
`MaxPaint/Shaders.metal`; the Python harness in `../tools/` remains the
behavioural reference both backends must match.

What works in this milestone: the full reference pipeline (integrate,
particle separation, particle-to-grid, incompressibility solve with drift
compensation at omega 1.5, grid-to-particle with the FLIP delta blend),
the streamed pour with motion inheritance, age-based settling with bake
to a background layer, and a minimal slider strip (Flow, Drag, Settle,
Cohesion, Motion inheritance).

Not yet ported: the gas/nib/watercolor brushes, presets, layers,
undo/redo, PNG export.

## Building

The Xcode project is generated, not committed:

    brew install xcodegen
    cd ios
    xcodegen generate
    open MaxPaint.xcodeproj

CI (`.github/workflows/ios.yml`) builds an unsigned simulator app on a
macOS runner whenever `ios/` changes. Installing on a real device
requires opening the project in Xcode with a development team set.
