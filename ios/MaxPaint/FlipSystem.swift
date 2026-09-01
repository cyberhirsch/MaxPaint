import Metal
import simd

/// The FLIP particle paint solver: Metal port of the Android FlipSystem +
/// FluidSim particle path. One particle pool, one accumulator grid, the
/// reference pipeline per frame: integrate, separate, particles-to-grid,
/// snapshot, incompressibility solve with drift compensation, grid-to-
/// particles. The Python harness in tools/ defines the behaviour.
final class FlipSystem {

    // tunables, mirroring the Android defaults (Wet Paint preset)
    var flipRatio: Float = 0.6        // motion inheritance
    var particleDrag: Float = 0.25
    var flowRate: Float = 40          // dabs/s poured while the finger is down
    var compensate: Float = 1         // drift compensation k
    var maxSpeed: Float = 4
    var cohesion: Float = 30          // slider units; speed = cohesion * 0.0025
    var settleTime: Float = 2         // seconds wet, then sets
    var pointSize: Float = 3
    var inkPerParticle: Float = 0.14
    var particlesPerCell: Float = 120
    var separationIters = 2
    var solveIters = 30
    var omega: Float = 1.5
    var brushRadius: Float = 0.02     // world units, the dab radius
    var inkScale: Float = 3.47

    let capacity = 400_000
    private(set) var gridRes = 160
    private(set) var gridW = 1
    private(set) var gridH = 1
    private(set) var aspect: Float = 1

    private let device: MTLDevice
    private var particles: MTLBuffer!
    private var accum: MTLBuffer!
    private var u: MTLBuffer!
    private var v: MTLBuffer!
    private var uOld: MTLBuffer!
    private var vOld: MTLBuffer!
    private var density: MTLBuffer!
    private var sepGrid: MTLBuffer!
    private var sepW = 0
    private var sepH = 0

    private var pEmit: MTLComputePipelineState!
    private var pIntegrate: MTLComputePipelineState!
    private var pSepClear: MTLComputePipelineState!
    private var pSepBin: MTLComputePipelineState!
    private var pSepPush: MTLComputePipelineState!
    private var pClearGrid: MTLComputePipelineState!
    private var pP2G: MTLComputePipelineState!
    private var pNormalize: MTLComputePipelineState!
    private var pCopy: MTLComputePipelineState!
    private var pSolve: MTLComputePipelineState!
    private var pG2P: MTLComputePipelineState!

    private var head = 0
    private var seed: Float = 1
    private(set) var emitted = 0

    // matching the Metal structs field for field
    struct EmitParams {
        var head: Int32; var count: Int32; var capacity: Int32; var pad0: Int32 = 0
        var point: SIMD2<Float>; var vel: SIMD2<Float>
        var pointB: SIMD2<Float>; var velB: SIMD2<Float>
        var radius: Float; var aspect: Float; var ink: Float; var jitterSeed: Float
    }
    struct IntegrateParams { var dt: Float; var capacity: Int32; var aspect: Float }
    struct SepParams {
        var capacity: Int32; var aspect: Float; var spacing: Float; var minDist: Float
        var sep: SIMD2<Int32>; var cells: Int32; var pad: Int32 = 0
    }
    struct GridParams { var grid: SIMD2<Int32>; var capacity: Int32; var cells: Int32 }
    struct SolveParams {
        var grid: SIMD2<Int32>; var parity: Int32
        var omega: Float; var rest: Float; var compensate: Float
    }
    struct G2PParams {
        var dt: Float; var capacity: Int32
        var grid: SIMD2<Int32>   // 8-byte aligned in both MSL and Swift
        var flipRatio: Float; var drag: Float; var settleTime: Float
        var cohesionSpeed: Float; var restMass: Float; var maxSpeed: Float
    }

    init(device: MTLDevice, library: MTLLibrary) throws {
        self.device = device
        func pipe(_ name: String) throws -> MTLComputePipelineState {
            guard let fn = library.makeFunction(name: name) else {
                fatalError("missing kernel \(name)")
            }
            return try device.makeComputePipelineState(function: fn)
        }
        pEmit = try pipe("flipEmit")
        pIntegrate = try pipe("flipIntegrate")
        pSepClear = try pipe("sepClear")
        pSepBin = try pipe("sepBin")
        pSepPush = try pipe("sepPush")
        pClearGrid = try pipe("clearGrid")
        pP2G = try pipe("p2g")
        pNormalize = try pipe("normalizeGrid")
        pCopy = try pipe("copyField")
        pSolve = try pipe("solve")
        pG2P = try pipe("g2p")

        particles = device.makeBuffer(length: capacity * 32,
                                      options: .storageModePrivate)
    }

    var particleBuffer: MTLBuffer { particles }
    var liveSpan: Int { emitted >= capacity ? capacity : max(head, 1) }

    /// Shapes the grid so cells stay square in world space (aspect x 1).
    func resize(aspect: Float) {
        self.aspect = max(0.2, min(5, aspect))
        let root = sqrt(self.aspect)
        gridW = max(8, Int(Float(gridRes) * root) & ~1)
        gridH = max(8, Int(Float(gridRes) / root) & ~1)
        let cells = gridW * gridH
        accum = device.makeBuffer(length: cells * 6 * 4, options: .storageModePrivate)
        u = device.makeBuffer(length: cells * 4, options: .storageModePrivate)
        v = device.makeBuffer(length: cells * 4, options: .storageModePrivate)
        uOld = device.makeBuffer(length: cells * 4, options: .storageModePrivate)
        vOld = device.makeBuffer(length: cells * 4, options: .storageModePrivate)
        density = device.makeBuffer(length: cells * 4, options: .storageModePrivate)
    }

    /// How many particles a dab of this radius emits at the target density.
    func countFor(radius: Float) -> Int {
        let cell = sqrt(aspect) / Float(max(gridRes, 1))
        let footprint = max(Float.pi * radius * radius / (cell * cell), 1)
        return min(max(Int(particlesPerCell * footprint), 4), 2048)
    }

    private func dispatch1D(_ enc: MTLComputeCommandEncoder,
                            _ pipe: MTLComputePipelineState, _ n: Int) {
        enc.setComputePipelineState(pipe)
        let w = min(pipe.maxTotalThreadsPerThreadgroup, 64)
        enc.dispatchThreadgroups(MTLSize(width: (n + w - 1) / w, height: 1, depth: 1),
                                 threadsPerThreadgroup: MTLSize(width: w, height: 1, depth: 1))
    }

    func emit(_ enc: MTLComputeCommandEncoder,
              from a: SIMD2<Float>, to b: SIMD2<Float>,
              velA: SIMD2<Float>, velB: SIMD2<Float>, count: Int) {
        guard count > 0 else { return }
        var p = EmitParams(head: Int32(head), count: Int32(count),
                           capacity: Int32(capacity),
                           point: a, vel: velA, pointB: b, velB: velB,
                           radius: brushRadius, aspect: aspect,
                           ink: inkPerParticle * inkScale, jitterSeed: seed)
        enc.setBuffer(particles, offset: 0, index: 0)
        enc.setBytes(&p, length: MemoryLayout<EmitParams>.stride, index: 1)
        dispatch1D(enc, pEmit, count)
        head = (head + count) % capacity
        emitted += count
        seed = (seed + 13.37).truncatingRemainder(dividingBy: 1000)
    }

    /// One full simulation step, encoded into the given encoder.
    func step(_ enc: MTLComputeCommandEncoder, dt: Float) {
        let span = liveSpan
        let cells = gridW * gridH

        var ip = IntegrateParams(dt: dt, capacity: Int32(span), aspect: aspect)
        enc.setBuffer(particles, offset: 0, index: 0)
        enc.setBytes(&ip, length: MemoryLayout<IntegrateParams>.stride, index: 1)
        dispatch1D(enc, pIntegrate, span)

        pushApart(enc, span: span)

        var gp = GridParams(grid: SIMD2(Int32(gridW), Int32(gridH)),
                            capacity: Int32(span), cells: Int32(cells))
        enc.setBuffer(particles, offset: 0, index: 0)
        enc.setBytes(&gp, length: MemoryLayout<GridParams>.stride, index: 1)
        enc.setBuffer(accum, offset: 0, index: 2)
        dispatch1D(enc, pClearGrid, cells)
        dispatch1D(enc, pP2G, span)
        enc.setBuffer(u, offset: 0, index: 3)
        enc.setBuffer(v, offset: 0, index: 4)
        enc.setBuffer(density, offset: 0, index: 5)
        dispatch1D(enc, pNormalize, cells)
        enc.setBuffer(uOld, offset: 0, index: 6)
        enc.setBuffer(vOld, offset: 0, index: 7)
        dispatch1D(enc, pCopy, cells)

        // red-black sweeps, two parities per iteration
        enc.setComputePipelineState(pSolve)
        let half = (gridW + 1) / 2
        let tg = MTLSize(width: 8, height: 8, depth: 1)
        let groups = MTLSize(width: (half + 7) / 8, height: (gridH + 7) / 8, depth: 1)
        for _ in 0..<solveIters {
            for parity: Int32 in 0...1 {
                var sp = SolveParams(grid: SIMD2(Int32(gridW), Int32(gridH)),
                                     parity: parity, omega: omega,
                                     rest: particlesPerCell, compensate: compensate)
                enc.setBytes(&sp, length: MemoryLayout<SolveParams>.stride, index: 1)
                enc.dispatchThreadgroups(groups, threadsPerThreadgroup: tg)
            }
        }

        var g2 = G2PParams(dt: dt, capacity: Int32(span),
                           grid: SIMD2(Int32(gridW), Int32(gridH)),
                           flipRatio: flipRatio, drag: particleDrag,
                           settleTime: settleTime,
                           cohesionSpeed: cohesion * 0.0025,
                           restMass: particlesPerCell, maxSpeed: maxSpeed)
        enc.setBuffer(particles, offset: 0, index: 0)
        enc.setBytes(&g2, length: MemoryLayout<G2PParams>.stride, index: 1)
        dispatch1D(enc, pG2P, span)
    }

    private func pushApart(_ enc: MTLComputeCommandEncoder, span: Int) {
        guard separationIters > 0 else { return }
        // hash cells 2x the rest packing distance, capped by a memory budget;
        // minDist 0.7x rest so separation never unpacks a condensed bead
        let cell = sqrt(aspect) / Float(max(gridRes, 1))
        var minDist = 0.75 * cell / sqrt(max(particlesPerCell, 1))
        var spacing = 2 * minDist
        let budget: Float = 600_000
        if aspect / (spacing * spacing) > budget { spacing = sqrt(aspect / budget) }
        minDist = min(minDist, 0.95 * spacing)

        let w = max(8, Int(ceil(aspect / spacing)))
        let h = max(8, Int(ceil(1 / spacing)))
        if sepGrid == nil || w != sepW || h != sepH {
            sepW = w; sepH = h
            sepGrid = device.makeBuffer(length: w * h * 13 * 4,
                                        options: .storageModePrivate)
        }

        var sp = SepParams(capacity: Int32(span), aspect: aspect,
                           spacing: spacing, minDist: minDist,
                           sep: SIMD2(Int32(sepW), Int32(sepH)),
                           cells: Int32(sepW * sepH))
        enc.setBuffer(particles, offset: 0, index: 0)
        enc.setBuffer(sepGrid, offset: 0, index: 2)
        for _ in 0..<separationIters {
            enc.setBytes(&sp, length: MemoryLayout<SepParams>.stride, index: 1)
            dispatch1D(enc, pSepClear, sepW * sepH)
            dispatch1D(enc, pSepBin, span)
            dispatch1D(enc, pSepPush, span)
        }
    }
}
