import MetalKit
import simd

/// Drives the solver and draws it: live particles into a transient ink
/// texture each frame, freshly dried ones once into a persistent background,
/// then a composite pass puts black paint on white paper. Mirrors the Android
/// FluidRenderer's structure.
final class Renderer: NSObject, MTKViewDelegate {

    let device: MTLDevice
    let queue: MTLCommandQueue
    let flip: FlipSystem

    private var drawPipeline: MTLRenderPipelineState!
    private var compositePipeline: MTLRenderPipelineState!
    private var background: MTLTexture?
    private var live: MTLTexture?

    // touch state, written by the UI thread
    struct Touch { var point: SIMD2<Float>; var down: Bool }
    var touch = Touch(point: .zero, down: false)

    private var pourLast: SIMD2<Float>? = nil
    private var pourLastVel = SIMD2<Float>(0, 0)
    private var pourDebt: Float = 0
    private var lastTime: CFTimeInterval = CACurrentMediaTime()

    struct DrawParams { var pointSize: Float; var wantState: Float }

    init(view: MTKView) {
        guard let dev = MTLCreateSystemDefaultDevice(),
              let q = dev.makeCommandQueue(),
              let lib = dev.makeDefaultLibrary() else {
            fatalError("Metal unavailable")
        }
        device = dev
        queue = q
        flip = try! FlipSystem(device: dev, library: lib)

        super.init()
        view.device = dev
        view.colorPixelFormat = .bgra8Unorm
        view.preferredFramesPerSecond = 60
        view.delegate = self

        let ink = MTLRenderPipelineDescriptor()
        ink.vertexFunction = lib.makeFunction(name: "particleVertex")
        ink.fragmentFunction = lib.makeFunction(name: "particleFragment")
        ink.colorAttachments[0].pixelFormat = .r16Float
        ink.colorAttachments[0].isBlendingEnabled = true
        ink.colorAttachments[0].rgbBlendOperation = .add
        ink.colorAttachments[0].sourceRGBBlendFactor = .one
        ink.colorAttachments[0].destinationRGBBlendFactor = .one
        drawPipeline = try! dev.makeRenderPipelineState(descriptor: ink)

        let comp = MTLRenderPipelineDescriptor()
        comp.vertexFunction = lib.makeFunction(name: "compositeVertex")
        comp.fragmentFunction = lib.makeFunction(name: "compositeFragment")
        comp.colorAttachments[0].pixelFormat = view.colorPixelFormat
        compositePipeline = try! dev.makeRenderPipelineState(descriptor: comp)
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {
        guard size.width > 0, size.height > 0 else { return }
        let desc = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .r16Float,
            width: Int(size.width), height: Int(size.height), mipmapped: false)
        desc.usage = [.renderTarget, .shaderRead]
        background = device.makeTexture(descriptor: desc)
        live = device.makeTexture(descriptor: desc)
        flip.resize(aspect: Float(size.width / size.height))
    }

    func draw(in view: MTKView) {
        guard let drawable = view.currentDrawable,
              let background, let live,
              let cmd = queue.makeCommandBuffer() else { return }

        let now = CACurrentMediaTime()
        let dt = Float(min(max(now - lastTime, 1.0 / 120.0), 1.0 / 20.0))
        lastTime = now

        if let enc = cmd.makeComputeCommandEncoder() {
            pour(enc, dt: dt)
            if flip.emitted > 0 { flip.step(enc, dt: dt) }
            enc.endEncoding()
        }

        // freshly dried particles land in the background, permanently
        drawParticles(cmd, into: background, state: 2, clear: false)
        // live particles are redrawn from scratch each frame
        drawParticles(cmd, into: live, state: 1, clear: true)

        if let rpd = view.currentRenderPassDescriptor,
           let enc = cmd.makeRenderCommandEncoder(descriptor: rpd) {
            enc.setRenderPipelineState(compositePipeline)
            enc.setFragmentTexture(background, index: 0)
            enc.setFragmentTexture(live, index: 1)
            enc.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
            enc.endEncoding()
        }

        cmd.present(drawable)
        cmd.commit()
    }

    /// The particle medium's one emitter: paint pours the whole time the
    /// finger is down, streamed along its path, carrying the finger's motion
    /// scaled by motion inheritance.
    private func pour(_ enc: MTLComputeCommandEncoder, dt: Float) {
        guard touch.down, flip.flowRate > 0 else {
            pourLast = nil
            pourDebt = 0
            return
        }
        let cur = touch.point
        let vel: SIMD2<Float>
        if let last = pourLast, dt > 0 {
            // same convention as the stroke dabs on Android: delta x 12 per
            // 60Hz event, frame-rate independent
            let scale = 12.0 / (60.0 * dt)
            vel = (cur - last) * scale
        } else {
            vel = .zero
        }
        let from = pourLast ?? cur
        let velA = pourLastVel
        pourLast = cur
        pourLastVel = vel

        pourDebt += flip.flowRate * dt * Float(flip.countFor(radius: flip.brushRadius))
        let n = Int(pourDebt)
        guard n > 0 else { return }
        pourDebt -= Float(n)

        let inherit = max(0, min(1, flip.flipRatio))
        flip.emit(enc, from: from, to: cur,
                  velA: velA * inherit, velB: vel * inherit,
                  count: min(n, 8192))
    }

    private func drawParticles(_ cmd: MTLCommandBuffer, into target: MTLTexture,
                               state: Float, clear: Bool) {
        guard flip.emitted > 0 else {
            if clear { clearTexture(cmd, target) }
            return
        }
        let rpd = MTLRenderPassDescriptor()
        rpd.colorAttachments[0].texture = target
        rpd.colorAttachments[0].loadAction = clear ? .clear : .load
        rpd.colorAttachments[0].storeAction = .store
        rpd.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        guard let enc = cmd.makeRenderCommandEncoder(descriptor: rpd) else { return }
        enc.setRenderPipelineState(drawPipeline)
        var p = DrawParams(pointSize: flip.pointSize, wantState: state)
        enc.setVertexBuffer(flip.particleBuffer, offset: 0, index: 0)
        enc.setVertexBytes(&p, length: MemoryLayout<DrawParams>.stride, index: 1)
        enc.drawPrimitives(type: .point, vertexStart: 0, vertexCount: flip.liveSpan)
        enc.endEncoding()
    }

    private func clearTexture(_ cmd: MTLCommandBuffer, _ target: MTLTexture) {
        let rpd = MTLRenderPassDescriptor()
        rpd.colorAttachments[0].texture = target
        rpd.colorAttachments[0].loadAction = .clear
        rpd.colorAttachments[0].storeAction = .store
        cmd.makeRenderCommandEncoder(descriptor: rpd)?.endEncoding()
    }

    func clearCanvas() {
        // dropping the pool forgets the paint; the background clears next resize
        // (a fuller clear pipeline comes with the UI pass)
    }
}
