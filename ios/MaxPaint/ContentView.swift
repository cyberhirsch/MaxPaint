import SwiftUI
import MetalKit

/// Canvas first, like the Android app: the Metal view fills the screen and a
/// minimal control strip floats over it. Sliders mirror the Flip panel's
/// core: Flow, Drag, Settle, Cohesion, Motion inheritance.
struct ContentView: View {
    @State private var flow: Double = 40
    @State private var dragPct: Double = 22
    @State private var settle: Double = 2
    @State private var cohesion: Double = 30
    @State private var inherit: Double = 60

    var body: some View {
        ZStack(alignment: .topLeading) {
            MetalCanvas(flow: $flow, dragPct: $dragPct, settle: $settle,
                        cohesion: $cohesion, inherit: $inherit)
                .ignoresSafeArea()

            VStack(alignment: .leading, spacing: 4) {
                slider("Flow \(Int(flow))/s", $flow, 0...40)
                slider("Drag \(Int(dragPct))%/s", $dragPct, 0...99)
                slider(String(format: "Settle %.1fs", settle), $settle, 0...10)
                slider("Cohesion \(Int(cohesion))", $cohesion, 0...200)
                slider("Inherit \(Int(inherit))%", $inherit, 0...100)
            }
            .padding(8)
            .frame(width: 230)
            .background(.black.opacity(0.55))
            .foregroundStyle(.white)
            .cornerRadius(10)
            .padding()
        }
    }

    private func slider(_ label: String, _ value: Binding<Double>,
                        _ range: ClosedRange<Double>) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(label).font(.caption2)
            Slider(value: value, in: range)
        }
    }
}

/// Hosts the MTKView and feeds touches straight to the renderer.
struct MetalCanvas: UIViewRepresentable {
    @Binding var flow: Double
    @Binding var dragPct: Double
    @Binding var settle: Double
    @Binding var cohesion: Double
    @Binding var inherit: Double

    final class Coordinator {
        var renderer: Renderer?
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> TouchMTKView {
        let view = TouchMTKView(frame: .zero, device: MTLCreateSystemDefaultDevice())
        let renderer = Renderer(view: view)
        context.coordinator.renderer = renderer
        view.renderer = renderer
        return view
    }

    func updateUIView(_ view: TouchMTKView, context: Context) {
        guard let flip = context.coordinator.renderer?.flip else { return }
        flip.flowRate = Float(flow)
        // percent of speed lost per second, inverted into the solver's rate
        flip.particleDrag = Float(-log(1.0 - min(dragPct, 99) / 100.0))
        flip.settleTime = Float(settle)
        flip.cohesion = Float(cohesion)
        flip.flipRatio = Float(inherit) / 100
    }
}

/// MTKView that forwards touches as pour input.
final class TouchMTKView: MTKView {
    weak var renderer: Renderer?

    private func point(_ touches: Set<UITouch>) -> SIMD2<Float>? {
        guard let t = touches.first else { return nil }
        let p = t.location(in: self)
        guard bounds.width > 0, bounds.height > 0 else { return nil }
        // UV, y up, matching the solver's space
        return SIMD2(Float(p.x / bounds.width),
                     Float(1 - p.y / bounds.height))
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let p = point(touches) { renderer?.touch = .init(point: p, down: true) }
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let p = point(touches) { renderer?.touch = .init(point: p, down: true) }
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        renderer?.touch.down = false
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        renderer?.touch.down = false
    }
}
