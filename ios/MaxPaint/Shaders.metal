#include <metal_stdlib>
using namespace metal;

// The FLIP particle paint solver, ported kernel-for-kernel from the Android
// GLES 3.1 compute shaders (android/app/src/main/assets/shaders/flip_*.comp),
// which are themselves a port of Matthias Muller's Ten Minute Physics 2D FLIP
// (MIT). The Python harness in tools/ is the behavioural reference both
// backends must match.
//
// Layout differences from GLES, none behavioural:
//  - velocity fields u/v and density live in plain device buffers rather than
//    r32f/rgba16f images (Metal has no image-format restrictions to dodge)
//  - uniforms arrive as one Params struct per pass via setBytes

// Particle pool: two float4 per particle.
//   [2i]   = pos.xy, vel.xy      (pos in UV, vel in world units/s)
//   [2i+1] = ink, age, state, seed   (state 0 empty, 1 live, 2 drying)

constant int ACC_STRIDE = 6;   // xMom, xW, yMom, yW, density, cellType
constant float FIXED = 4096.0; // fixed-point scale for the atomics
constant int SEP_STRIDE = 13;  // count + 12 particle ids per hash cell

float hash1(float n) { return fract(sin(n) * 43758.5453); }

// ---------------------------------------------------------------- emission

struct EmitParams {
    int   head;
    int   count;
    int   capacity;
    float2 point;
    float2 vel;
    float2 pointB;
    float2 velB;
    float radius;
    float aspect;
    float ink;
    float jitterSeed;
};

kernel void flipEmit(device float4 *particles [[buffer(0)]],
                     constant EmitParams &P [[buffer(1)]],
                     uint id [[thread_position_in_grid]]) {
    if (int(id) >= P.count) return;
    int slot = (P.head + int(id)) % P.capacity;

    float s = P.jitterSeed + float(id) * 7.13;
    float a = hash1(s) * 6.2831853;
    float rr = sqrt(hash1(s + 1.7)) * P.radius;

    // scatter in world units; a pour from a moving finger is a stream, so
    // position and motion vector are interpolated along the segment
    float2 off = float2(cos(a), sin(a)) * rr;
    float t = hash1(s + 9.3);
    float2 centre = mix(P.point, P.pointB, t);
    float2 velAt = mix(P.vel, P.velB, t);

    float2 pos = centre + float2(off.x / P.aspect, off.y);
    float2 vel = velAt * (0.7 + 0.6 * hash1(s + 3.1));

    // birth-age jitter: without it a frame's whole cohort reaches the settle
    // time in the same tick and dries as one knot
    float age0 = hash1(s + 11.7) * 0.05;

    particles[slot * 2]     = float4(pos, vel);
    particles[slot * 2 + 1] = float4(P.ink, age0, 1.0, hash1(s + 5.9));
}

// ---------------------------------------------------------------- integrate

struct IntegrateParams {
    float dt;
    int   capacity;
    float aspect;
};

kernel void flipIntegrate(device float4 *particles [[buffer(0)]],
                          constant IntegrateParams &P [[buffer(1)]],
                          uint id [[thread_position_in_grid]]) {
    if (int(id) >= P.capacity) return;
    float state = particles[id * 2 + 1].z;
    if (state < 0.5 || state > 1.5) return;

    float4 pv = particles[id * 2];
    float2 pos = pv.xy;
    float2 vel = pv.zw;

    pos += float2(vel.x / P.aspect, vel.y) * P.dt;

    if (pos.x < 0.0) { pos.x = 0.0; vel.x = 0.0; }
    if (pos.x > 1.0) { pos.x = 1.0; vel.x = 0.0; }
    if (pos.y < 0.0) { pos.y = 0.0; vel.y = 0.0; }
    if (pos.y > 1.0) { pos.y = 1.0; vel.y = 0.0; }

    particles[id * 2] = float4(pos, vel);
}

// ---------------------------------------------------------------- separation

struct SepParams {
    int    capacity;
    float  aspect;
    float  spacing;
    float  minDist;
    int2   sep;
    int    cells;
};

kernel void sepClear(device atomic_int *sepGrid [[buffer(2)]],
                     constant SepParams &P [[buffer(1)]],
                     uint id [[thread_position_in_grid]]) {
    if (int(id) >= P.cells) return;
    atomic_store_explicit(&sepGrid[id * SEP_STRIDE], 0, memory_order_relaxed);
}

kernel void sepBin(device const float4 *particles [[buffer(0)]],
                   device atomic_int *sepGrid [[buffer(2)]],
                   constant SepParams &P [[buffer(1)]],
                   uint id [[thread_position_in_grid]]) {
    if (int(id) >= P.capacity) return;
    float state = particles[id * 2 + 1].z;
    if (state < 0.5 || state > 1.5) return;

    float2 pos = particles[id * 2].xy;
    float2 world = float2(pos.x * P.aspect, pos.y);
    int2 c = clamp(int2(world / P.spacing), int2(0), P.sep - 1);
    int cell = c.y * P.sep.x + c.x;

    int slot = atomic_fetch_add_explicit(&sepGrid[cell * SEP_STRIDE], 1,
                                         memory_order_relaxed);
    if (slot < 12)
        atomic_store_explicit(&sepGrid[cell * SEP_STRIDE + 1 + slot], int(id),
                              memory_order_relaxed);
}

kernel void sepPush(device float4 *particles [[buffer(0)]],
                    device const int *sepGrid [[buffer(2)]],
                    constant SepParams &P [[buffer(1)]],
                    uint id [[thread_position_in_grid]]) {
    if (int(id) >= P.capacity) return;
    float state = particles[id * 2 + 1].z;
    if (state < 0.5 || state > 1.5) return;

    float4 pv = particles[id * 2];
    float2 world = float2(pv.x * P.aspect, pv.y);
    int2 c = clamp(int2(world / P.spacing), int2(0), P.sep - 1);

    float minDist2 = P.minDist * P.minDist;
    float2 disp = float2(0.0);

    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            int2 n = c + int2(dx, dy);
            if (n.x < 0 || n.y < 0 || n.x >= P.sep.x || n.y >= P.sep.y) continue;
            int cell = (n.y * P.sep.x + n.x) * SEP_STRIDE;
            int count = min(sepGrid[cell], 12);
            for (int s = 0; s < count; s++) {
                int j = sepGrid[cell + 1 + s];
                if (j == int(id)) continue;
                float2 ow = float2(particles[j * 2].x * P.aspect,
                                   particles[j * 2].y);
                float2 d = world - ow;
                float d2 = dot(d, d);
                if (d2 >= minDist2) continue;
                float dist = sqrt(d2);
                if (dist > 1e-6) {
                    disp += d * (0.5 * (P.minDist - dist) / dist);
                } else {
                    float a = float(int(id) - j) * 2.399963;
                    disp += 0.5 * P.minDist * float2(cos(a), sin(a));
                }
            }
        }
    }

    world += disp;
    float2 pos = clamp(float2(world.x / P.aspect, world.y),
                       float2(0.0), float2(1.0));
    particles[id * 2] = float4(pos, pv.zw);
}

// ---------------------------------------------------------------- grid

struct GridParams {
    int2  grid;
    int   capacity;
    int   cells;
};

kernel void clearGrid(device int *acc [[buffer(2)]],
                      constant GridParams &P [[buffer(1)]],
                      uint id [[thread_position_in_grid]]) {
    if (int(id) >= P.cells) return;
    for (int k = 0; k < 5; k++) acc[id * ACC_STRIDE + k] = 0;
    acc[id * ACC_STRIDE + 5] = 1;   // AIR; walls are implicit outside
}

static void addAcc(device atomic_int *acc, int2 c, int2 grid, int off, float v) {
    if (c.x < 0 || c.y < 0 || c.x >= grid.x || c.y >= grid.y) return;
    atomic_fetch_add_explicit(&acc[(c.y * grid.x + c.x) * ACC_STRIDE + off],
                              int(v * FIXED), memory_order_relaxed);
}

kernel void p2g(device const float4 *particles [[buffer(0)]],
                device atomic_int *acc [[buffer(2)]],
                constant GridParams &P [[buffer(1)]],
                uint id [[thread_position_in_grid]]) {
    if (int(id) >= P.capacity) return;
    float state = particles[id * 2 + 1].z;
    if (state < 0.5 || state > 1.5) return;

    float4 pv = particles[id * 2];
    float2 g = pv.xy * float2(P.grid);
    float2 vel = pv.zw;

    int2 cell = clamp(int2(g), int2(0), P.grid - 1);
    atomic_fetch_max_explicit(&acc[(cell.y * P.grid.x + cell.x) * ACC_STRIDE + 5],
                              2, memory_order_relaxed);   // FLUID

    // x on left faces (offset half a cell in y)
    float2 qx = float2(g.x, g.y - 0.5);
    int2 bx = int2(floor(qx));
    float2 fx = qx - float2(bx);
    for (int dy = 0; dy <= 1; dy++)
        for (int dx = 0; dx <= 1; dx++) {
            float w = (dx == 0 ? 1.0 - fx.x : fx.x) * (dy == 0 ? 1.0 - fx.y : fx.y);
            addAcc(acc, bx + int2(dx, dy), P.grid, 0, vel.x * w);
            addAcc(acc, bx + int2(dx, dy), P.grid, 1, w);
        }

    // y on bottom faces (offset half a cell in x)
    float2 qy = float2(g.x - 0.5, g.y);
    int2 by = int2(floor(qy));
    float2 fy = qy - float2(by);
    for (int dy = 0; dy <= 1; dy++)
        for (int dx = 0; dx <= 1; dx++) {
            float w = (dx == 0 ? 1.0 - fy.x : fy.x) * (dy == 0 ? 1.0 - fy.y : fy.y);
            addAcc(acc, by + int2(dx, dy), P.grid, 2, vel.y * w);
            addAcc(acc, by + int2(dx, dy), P.grid, 3, w);
        }

    // density at cell centres
    float2 qd = g - 0.5;
    int2 bd = int2(floor(qd));
    float2 fd = qd - float2(bd);
    for (int dy = 0; dy <= 1; dy++)
        for (int dx = 0; dx <= 1; dx++) {
            float w = (dx == 0 ? 1.0 - fd.x : fd.x) * (dy == 0 ? 1.0 - fd.y : fd.y);
            addAcc(acc, bd + int2(dx, dy), P.grid, 4, w);
        }
}

kernel void normalizeGrid(device const int *acc [[buffer(2)]],
                          device float *u [[buffer(3)]],
                          device float *v [[buffer(4)]],
                          device float *density [[buffer(5)]],
                          constant GridParams &P [[buffer(1)]],
                          uint id [[thread_position_in_grid]]) {
    if (int(id) >= P.cells) return;
    int x = int(id) % P.grid.x;
    int y = int(id) / P.grid.x;
    int i = int(id) * ACC_STRIDE;

    float wx = float(acc[i + 1]);
    float wy = float(acc[i + 3]);
    float uu = wx > 0.5 ? float(acc[i]) / wx : 0.0;
    float vv = wy > 0.5 ? float(acc[i + 2]) / wy : 0.0;
    if (x == 0) uu = 0.0;   // left wall face
    if (y == 0) vv = 0.0;   // bottom wall face

    u[id] = uu;
    v[id] = vv;
    density[id] = float(acc[i + 4]) / FIXED;
}

kernel void copyField(device const float *u [[buffer(3)]],
                      device float *uOld [[buffer(6)]],
                      device const float *v [[buffer(4)]],
                      device float *vOld [[buffer(7)]],
                      constant GridParams &P [[buffer(1)]],
                      uint id [[thread_position_in_grid]]) {
    if (int(id) >= P.cells) return;
    uOld[id] = u[id];
    vOld[id] = v[id];
}

// ---------------------------------------------------------------- solve

struct SolveParams {
    int2  grid;
    int   parity;
    float omega;       // 1.5 measured; the reference's 1.9 overshoots
    float rest;        // emission density, particles per cell
    float compensate;  // drift compensation strength, reference k = 1
};

static int cellTypeAt(device const int *acc, int2 c, int2 grid) {
    if (c.x < 0 || c.y < 0 || c.x >= grid.x || c.y >= grid.y) return 0; // wall
    return acc[(c.y * grid.x + c.x) * ACC_STRIDE + 5];
}

// Red-black relaxation directly on the face velocities of fluid cells; no
// pressure field at all. A cell writes only its own left/bottom faces and its
// right/top neighbours', and no two cells of one colour share a face.
kernel void solve(device float *u [[buffer(3)]],
                  device float *v [[buffer(4)]],
                  device const int *acc [[buffer(2)]],
                  constant SolveParams &P [[buffer(1)]],
                  uint2 gid [[thread_position_in_grid]]) {
    if (int(gid.y) >= P.grid.y) return;
    int x = 2 * int(gid.x) + ((int(gid.y) + P.parity) & 1);
    if (x >= P.grid.x) return;
    int2 c = int2(x, int(gid.y));

    if (cellTypeAt(acc, c, P.grid) != 2) return;

    float sx0 = cellTypeAt(acc, c + int2(-1, 0), P.grid) != 0 ? 1.0 : 0.0;
    float sx1 = cellTypeAt(acc, c + int2( 1, 0), P.grid) != 0 ? 1.0 : 0.0;
    float sy0 = cellTypeAt(acc, c + int2( 0,-1), P.grid) != 0 ? 1.0 : 0.0;
    float sy1 = cellTypeAt(acc, c + int2( 0, 1), P.grid) != 0 ? 1.0 : 0.0;
    float s = sx0 + sx1 + sy0 + sy1;
    if (s == 0.0) return;

    int idx = c.y * P.grid.x + c.x;
    int idxR = idx + 1;
    int idxT = idx + P.grid.x;
    bool hasR = c.x + 1 < P.grid.x;
    bool hasT = c.y + 1 < P.grid.y;

    float uC = u[idx];
    float vC = v[idx];
    float uR = hasR ? u[idxR] : 0.0;   // far wall faces are zero
    float vT = hasT ? v[idxT] : 0.0;

    float div = uR - uC + vT - vC;

    if (P.compensate > 0.0) {
        float dens = float(acc[idx * ACC_STRIDE + 4]) / FIXED;
        float compression = dens / max(P.rest, 1.0f) - 1.0;
        if (compression > 0.0) div -= P.compensate * compression;
    }

    float p = -div / s * P.omega;

    u[idx] = uC - sx0 * p;
    v[idx] = vC - sy0 * p;
    if (hasR && sx1 > 0.0) u[idxR] = uR + p;
    if (hasT && sy1 > 0.0) v[idxT] = vT + p;
}

// ---------------------------------------------------------------- g2p

struct G2PParams {
    float dt;
    int   capacity;
    int2  grid;
    float flipRatio;
    float drag;
    float settleTime;     // seconds of life before paint sets; <= 0 never
    float cohesionSpeed;  // world units/s the surface may creep
    float restMass;       // emission density, particles per cell
    float maxSpeed;       // CFL guard
};

static float fieldAt(device const float *f, int2 c, int2 grid) {
    c = clamp(c, int2(0), grid - 1);
    return f[c.y * grid.x + c.x];
}

static float densityBilinear(device const float *density, float2 uv, int2 grid) {
    float2 g = uv * float2(grid) - 0.5;
    int2 b = int2(floor(g));
    float2 f = g - float2(b);
    float d00 = fieldAt(density, b, grid);
    float d10 = fieldAt(density, b + int2(1, 0), grid);
    float d01 = fieldAt(density, b + int2(0, 1), grid);
    float d11 = fieldAt(density, b + int2(1, 1), grid);
    return mix(mix(d00, d10, f.x), mix(d01, d11, f.x), f.y);
}

kernel void g2p(device float4 *particles [[buffer(0)]],
                device const int *acc [[buffer(2)]],
                device const float *u [[buffer(3)]],
                device const float *v [[buffer(4)]],
                device const float *density [[buffer(5)]],
                device const float *uOld [[buffer(6)]],
                device const float *vOld [[buffer(7)]],
                constant G2PParams &P [[buffer(1)]],
                uint id [[thread_position_in_grid]]) {
    if (int(id) >= P.capacity) return;

    float4 meta = particles[id * 2 + 1];
    float state = meta.z;

    if (state > 1.5) { particles[id * 2 + 1] = float4(0.0); return; }   // retire
    if (state < 0.5) return;

    float4 pv = particles[id * 2];
    float2 pos = pv.xy;
    float2 vel = pv.zw;
    float2 g = pos * float2(P.grid);

    float ratio = clamp(P.flipRatio, 0.0f, 1.0f);
    for (int comp = 0; comp < 2; comp++) {
        int2 axis = comp == 0 ? int2(1, 0) : int2(0, 1);
        float2 q = comp == 0 ? float2(g.x, g.y - 0.5) : float2(g.x - 0.5, g.y);
        int2 b = int2(floor(q));
        float2 f = q - float2(b);

        float pic = 0.0, corr = 0.0, wsum = 0.0;
        for (int dy = 0; dy <= 1; dy++)
            for (int dx = 0; dx <= 1; dx++) {
                int2 c = b + int2(dx, dy);
                float w = (dx == 0 ? 1.0 - f.x : f.x) * (dy == 0 ? 1.0 - f.y : f.y);
                // a face is valid when either cell beside it is not air
                bool valid = cellTypeAt(acc, c, P.grid) != 1 ||
                             cellTypeAt(acc, c - axis, P.grid) != 1;
                if (!valid) continue;
                float vNew = comp == 0 ? fieldAt(u, c, P.grid) : fieldAt(v, c, P.grid);
                float vOldV = comp == 0 ? fieldAt(uOld, c, P.grid) : fieldAt(vOld, c, P.grid);
                pic  += w * vNew;
                corr += w * (vNew - vOldV);
                wsum += w;
            }
        if (wsum > 0.0) {
            float picV = pic / wsum;
            float flipV = (comp == 0 ? vel.x : vel.y) + corr / wsum;
            float blended = mix(picV, flipV, ratio);
            if (comp == 0) vel.x = blended; else vel.y = blended;
        }
    }

    // cohesion: a bounded target velocity the surface relaxes toward; with no
    // gravity this is what gathers paint into droplets. Gate closes at rest
    // density, where the solve's drift compensation starts pushing back.
    if (P.cohesionSpeed > 0.0) {
        float2 t = 1.0 / float2(P.grid);
        float mHere = densityBilinear(density, pos, P.grid);
        float mL = densityBilinear(density, pos - float2(t.x, 0.0), P.grid);
        float mR = densityBilinear(density, pos + float2(t.x, 0.0), P.grid);
        float mB = densityBilinear(density, pos - float2(0.0, t.y), P.grid);
        float mT = densityBilinear(density, pos + float2(0.0, t.y), P.grid);
        float2 grad = 0.5 * float2(mR - mL, mT - mB);
        float gmag = length(grad);

        float surface = 1.0 - smoothstep(0.7 * P.restMass, P.restMass, mHere);
        float body = mL + mR + mB + mT;

        if (surface > 0.0 && body > P.restMass && gmag > 0.02 * P.restMass) {
            float2 dirHat = grad / gmag;
            float sat = clamp(gmag / (0.4 * P.restMass), 0.0f, 1.0f);
            float target = P.cohesionSpeed * surface * sat;
            float along = dot(vel, dirHat);
            float rate = min(1.0f, 30.0f * P.dt);
            vel += dirHat * (target - along) * rate;
        }
    }

    vel *= 1.0 / (1.0 + P.drag * P.dt);

    if (P.maxSpeed > 0.0) {
        float sp = length(vel);
        if (sp > P.maxSpeed) vel *= P.maxSpeed / sp;
    }

    float age = meta.y + P.dt;
    // settling is purely a clock on the particle's life
    if (P.settleTime > 0.0 && age > P.settleTime) state = 2.0;

    particles[id * 2]     = float4(pos, vel);
    particles[id * 2 + 1] = float4(meta.x, age, state, meta.w);
}

// ---------------------------------------------------------------- rendering

struct DrawParams {
    float pointSize;
    float wantState;
};

struct PointOut {
    float4 position [[position]];
    float  ink;
    float  pointSize [[point_size]];
};

vertex PointOut particleVertex(device const float4 *particles [[buffer(0)]],
                               constant DrawParams &P [[buffer(1)]],
                               uint vid [[vertex_id]]) {
    float4 pv = particles[vid * 2];
    float4 meta = particles[vid * 2 + 1];

    PointOut out;
    // UV (0..1, y up) to clip space
    out.position = float4(pv.x * 2.0 - 1.0, pv.y * 2.0 - 1.0, 0.0, 1.0);
    out.pointSize = P.pointSize;
    bool want = abs(meta.z - P.wantState) < 0.25;
    out.ink = want ? meta.x : 0.0;
    if (!want) out.position = float4(-2.0, -2.0, 0.0, 1.0);   // clipped away
    return out;
}

fragment float4 particleFragment(PointOut in [[stage_in]],
                                 float2 pc [[point_coord]]) {
    float d = length(pc - 0.5) * 2.0;
    float soft = 1.0 - smoothstep(0.6, 1.0, d);
    return float4(in.ink * soft, 0.0, 0.0, 1.0);
}

// full-screen composite: ink accumulates additively in two R16F textures
// (settled background + live particles); paint is black on white
struct QuadOut {
    float4 position [[position]];
    float2 uv;
};

vertex QuadOut compositeVertex(uint vid [[vertex_id]]) {
    float2 v = float2((vid << 1) & 2, vid & 2);   // 0,0 2,0 0,2 tri trick
    QuadOut out;
    out.position = float4(v * 2.0 - 1.0, 0.0, 1.0);
    out.uv = v;
    return out;
}

fragment float4 compositeFragment(QuadOut in [[stage_in]],
                                  texture2d<float> background [[texture(0)]],
                                  texture2d<float> live [[texture(1)]]) {
    constexpr sampler smp(mag_filter::linear, min_filter::linear,
                          address::clamp_to_edge);
    float ink = background.sample(smp, in.uv).r + live.sample(smp, in.uv).r;
    float paper = 1.0 - clamp(ink, 0.0, 1.0);
    return float4(paper, paper, paper, 1.0);
}
