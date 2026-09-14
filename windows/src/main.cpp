// MaxPaint for Windows: a desktop host for the SAME compute shaders the
// Android app ships. Desktop OpenGL 4.3 has the compute model GLES 3.1 was
// derived from, so the flip_*.comp files run verbatim -- the loader only
// rewrites the version header and drops standalone precision statements.
// The Python harness in ../tools/ remains the behavioural reference.
//
// Tools: 1 paint (the streamed pour with motion inheritance), 2 glitch
// (pixel sorting under the brush). Drop an image file onto the window, or
// pass its path on the command line, to load it as set paint.
//   W/S flow    E/D settle    R/F motion inheritance   T/G drag
//   Q/A cohesion   [ ] brush size   C clears the canvas
//   glitch: H/J band low   K/L band high   V direction   B order
// Current values live in the window title.

#define GLFW_INCLUDE_NONE
#include <GLFW/glfw3.h>
#include "gl43.h"

#define STB_IMAGE_IMPLEMENTATION
#define STBI_NO_HDR
#define STBI_NO_LINEAR
#include "stb_image.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#define MAXPAINT_GL_DEFINE(ret, name, args) ret (APIENTRY *name) args = nullptr;
MAXPAINT_GL_FUNCS(MAXPAINT_GL_DEFINE)
#undef MAXPAINT_GL_DEFINE

bool loadGL(GLProcAny (*getProc)(const char *)) {
    bool ok = true;
#define MAXPAINT_GL_LOAD(ret, fn, args) \
    fn = reinterpret_cast<decltype(fn)>(getProc(#fn)); \
    if (!fn) { std::fprintf(stderr, "missing GL function %s\n", #fn); ok = false; }
    MAXPAINT_GL_FUNCS(MAXPAINT_GL_LOAD)
#undef MAXPAINT_GL_LOAD
    return ok;
}

// ------------------------------------------------------------ shader loading

static std::string shaderDir = "shaders";

// GLES 3.1 source to desktop 4.3: swap the version line, drop standalone
// precision statements (inline highp/mediump qualifiers are legal no-ops in
// desktop GLSL and stay).
static std::string adaptSource(const std::string &src) {
    std::istringstream in(src);
    std::ostringstream out;
    std::string line;
    while (std::getline(in, line)) {
        if (line.rfind("#version", 0) == 0) { out << "#version 430\n"; continue; }
        std::string trimmed = line;
        trimmed.erase(0, trimmed.find_first_not_of(" \t"));
        if (trimmed.rfind("precision ", 0) == 0) continue;
        out << line << "\n";
    }
    return out.str();
}

static std::string readFile(const std::string &path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) { std::fprintf(stderr, "cannot open %s\n", path.c_str()); std::exit(1); }
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

static GLuint compile(GLenum kind, const std::string &source, const char *name) {
    GLuint s = glCreateShader(kind);
    std::string adapted = adaptSource(source);
    const char *src = adapted.c_str();
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[4096];
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        std::fprintf(stderr, "%s failed to compile:\n%s\n", name, log);
        std::exit(1);
    }
    return s;
}

static GLuint linkProgram(std::vector<GLuint> shaders, const char *name) {
    GLuint p = glCreateProgram();
    for (GLuint s : shaders) glAttachShader(p, s);
    glLinkProgram(p);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[4096];
        glGetProgramInfoLog(p, sizeof log, nullptr, log);
        std::fprintf(stderr, "%s failed to link:\n%s\n", name, log);
        std::exit(1);
    }
    for (GLuint s : shaders) glDeleteShader(s);
    return p;
}

static GLuint computeProgram(const char *file) {
    return linkProgram({compile(GL_COMPUTE_SHADER,
                                readFile(shaderDir + "/" + file), file)}, file);
}

struct Prog {
    GLuint id = 0;
    void use() const { glUseProgram(id); }
    void set(const char *n, int v) const { glUniform1i(glGetUniformLocation(id, n), v); }
    void set(const char *n, float v) const { glUniform1f(glGetUniformLocation(id, n), v); }
    void set(const char *n, float a, float b) const { glUniform2f(glGetUniformLocation(id, n), a, b); }
    void set2i(const char *n, int a, int b) const { glUniform2i(glGetUniformLocation(id, n), a, b); }
};

// ------------------------------------------------------------ small helpers

static GLuint makeTexture(int w, int h, GLenum fmt, GLint filter) {
    GLuint t = 0;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexStorage2D(GL_TEXTURE_2D, 1, fmt, w, h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);
    return t;
}

static GLuint makeBuffer(size_t bytes, GLenum usage) {
    GLuint b = 0;
    glGenBuffers(1, &b);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, b);
    std::vector<char> zeros(bytes, 0);
    glBufferData(GL_SHADER_STORAGE_BUFFER, (GLsizeiptr)bytes, zeros.data(), usage);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    return b;
}

// ------------------------------------------------------------ the solver

// Port of android FlipSystem + the FluidSim particle path; the pipeline and
// every constant mirror the Kotlin host.
struct Flip {
    // tunables (Wet Paint defaults)
    float flipRatio = 0.6f;
    float particleDrag = 0.25f;
    float flowRate = 40.0f;
    float compensate = 1.0f;
    float maxSpeed = 4.0f;
    float cohesion = 30.0f;
    float settleTime = 2.0f;
    float pointSize = 3.0f;
    float inkPerParticle = 0.14f;
    float inkScale = 3.47f;
    float particlesPerCell = 120.0f;
    float brushRadius = 0.02f;
    int separationIters = 2;
    int solveIters = 30;
    float omega = 1.5f;

    static const int capacity = 400000;
    int gridRes = 160, gridW = 1, gridH = 1;
    float aspect = 1.0f;

    GLuint particles = 0, vao = 0, accum = 0, sepGrid = 0;
    GLuint texU = 0, texV = 0, texUOld = 0, texVOld = 0, texDensity = 0;
    int sepW = 0, sepH = 0;
    int head = 0;
    long emitted = 0;
    float seed = 1.0f;

    Prog pEmit, pIntegrate, pSepClear, pSepBin, pSepPush,
         pClearGrid, pP2G, pNormalize, pCopy, pSolve, pG2P;
    GLuint drawProgram = 0;

    void init() {
        pEmit.id = computeProgram("flip_emit.comp");
        pIntegrate.id = computeProgram("flip_integrate.comp");
        pSepClear.id = computeProgram("flip_sep_clear.comp");
        pSepBin.id = computeProgram("flip_sep_bin.comp");
        pSepPush.id = computeProgram("flip_sep_push.comp");
        pClearGrid.id = computeProgram("flip_clear_grid.comp");
        pP2G.id = computeProgram("flip_p2g.comp");
        pNormalize.id = computeProgram("flip_normalize.comp");
        pCopy.id = computeProgram("flip_copy.comp");
        pSolve.id = computeProgram("flip_solve.comp");
        pG2P.id = computeProgram("flip_g2p.comp");
        drawProgram = linkProgram(
            {compile(GL_VERTEX_SHADER, readFile(shaderDir + "/particle.vert"), "particle.vert"),
             compile(GL_FRAGMENT_SHADER, readFile(shaderDir + "/particle.frag"), "particle.frag")},
            "particle draw");

        particles = makeBuffer((size_t)capacity * 32, GL_DYNAMIC_DRAW);

        glGenVertexArrays(1, &vao);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, particles);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 32, (const void *)0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, 32, (const void *)16);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    void resize(float asp) {
        aspect = std::min(5.0f, std::max(0.2f, asp));
        float root = std::sqrt(aspect);
        gridW = std::max(8, (int)(gridRes * root) & ~1);
        gridH = std::max(8, (int)(gridRes / root) & ~1);
        int cells = gridW * gridH;
        accum = makeBuffer((size_t)cells * 6 * 4, GL_DYNAMIC_COPY);
        texU = makeTexture(gridW, gridH, GL_R32F, GL_NEAREST);
        texV = makeTexture(gridW, gridH, GL_R32F, GL_NEAREST);
        texUOld = makeTexture(gridW, gridH, GL_R32F, GL_NEAREST);
        texVOld = makeTexture(gridW, gridH, GL_R32F, GL_NEAREST);
        texDensity = makeTexture(gridW, gridH, GL_RGBA16F, GL_LINEAR);
    }

    int liveSpan() const { return emitted >= capacity ? capacity : std::max(head, 1); }

    int countFor(float radius) const {
        float cell = std::sqrt(aspect) / std::max(gridRes, 1);
        float footprint = std::max(3.14159265f * radius * radius / (cell * cell), 1.0f);
        return std::min(std::max((int)(particlesPerCell * footprint), 4), 2048);
    }

    static void dispatch1D(int n) { glDispatchCompute((n + 63) / 64, 1, 1); }
    static void barrier() { glMemoryBarrier(GL_ALL_BARRIER_BITS); }

    void emit(float ax, float ay, float bx, float by,
              float vax, float vay, float vbx, float vby, int count) {
        if (count <= 0) return;
        pEmit.use();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, particles);
        pEmit.set("uHead", head);
        pEmit.set("uCount", count);
        pEmit.set("uCapacity", capacity);
        pEmit.set("uPoint", ax, ay);
        pEmit.set("uVel", vax, vay);
        pEmit.set("uPointB", bx, by);
        pEmit.set("uVelB", vbx, vby);
        pEmit.set("uRadius", brushRadius);
        pEmit.set("uAspect", aspect);
        pEmit.set("uAxis", 1.0f, 0.0f);
        pEmit.set("uMinor", 1.0f);
        pEmit.set("uInk", inkPerParticle * inkScale);
        pEmit.set("uJitterSeed", seed);
        dispatch1D(count);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        head = (head + count) % capacity;
        emitted += count;
        seed = std::fmod(seed + 13.37f, 1000.0f);
    }

    void pushApart() {
        if (separationIters <= 0) return;
        float cell = std::sqrt(aspect) / std::max(gridRes, 1);
        float minDist = 0.75f * cell / std::sqrt(std::max(particlesPerCell, 1.0f));
        float spacing = 2.0f * minDist;
        const float budget = 600000.0f;
        if (aspect / (spacing * spacing) > budget) spacing = std::sqrt(aspect / budget);
        minDist = std::min(minDist, 0.95f * spacing);

        int w = std::max(8, (int)std::ceil(aspect / spacing));
        int h = std::max(8, (int)std::ceil(1.0f / spacing));
        if (!sepGrid || w != sepW || h != sepH) {
            sepW = w; sepH = h;
            sepGrid = makeBuffer((size_t)w * h * 13 * 4, GL_DYNAMIC_COPY);
        }

        int span = liveSpan();
        int cells = sepW * sepH;
        for (int i = 0; i < separationIters; i++) {
            pSepClear.use();
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, sepGrid);
            pSepClear.set("uCells", cells);
            dispatch1D(cells);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

            pSepBin.use();
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, particles);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, sepGrid);
            pSepBin.set("uCapacity", span);
            pSepBin.set("uAspect", aspect);
            pSepBin.set("uSpacing", spacing);
            pSepBin.set2i("uSep", sepW, sepH);
            dispatch1D(span);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

            pSepPush.use();
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, particles);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, sepGrid);
            pSepPush.set("uCapacity", span);
            pSepPush.set("uAspect", aspect);
            pSepPush.set("uSpacing", spacing);
            pSepPush.set("uMinDist", minDist);
            pSepPush.set2i("uSep", sepW, sepH);
            dispatch1D(span);
            barrier();
        }
    }

    void step(float dt) {
        int span = liveSpan();
        int cells = gridW * gridH;

        pIntegrate.use();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, particles);
        pIntegrate.set("uDt", dt);
        pIntegrate.set("uCapacity", span);
        pIntegrate.set("uAspect", aspect);
        dispatch1D(span);
        barrier();

        pushApart();

        pClearGrid.use();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, accum);
        pClearGrid.set("uCells", cells);
        dispatch1D(cells);
        barrier();

        pP2G.use();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, particles);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, accum);
        pP2G.set("uCapacity", span);
        pP2G.set2i("uGrid", gridW, gridH);
        dispatch1D(span);
        barrier();

        pNormalize.use();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, accum);
        pNormalize.set2i("uGrid", gridW, gridH);
        glBindImageTexture(0, texU, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);
        glBindImageTexture(2, texV, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);
        glBindImageTexture(3, texDensity, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glDispatchCompute((gridW + 7) / 8, (gridH + 7) / 8, 1);
        barrier();

        pCopy.use();
        glBindImageTexture(0, texU, 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32F);
        glBindImageTexture(1, texUOld, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);
        glBindImageTexture(2, texV, 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32F);
        glBindImageTexture(3, texVOld, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);
        pCopy.set2i("uGrid", gridW, gridH);
        glDispatchCompute((gridW + 7) / 8, (gridH + 7) / 8, 1);
        barrier();

        pSolve.use();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, accum);
        pSolve.set2i("uGrid", gridW, gridH);
        pSolve.set("uOmega", omega);
        pSolve.set("uRest", particlesPerCell);
        pSolve.set("uCompensate", compensate);
        glBindImageTexture(0, texU, 0, GL_FALSE, 0, GL_READ_WRITE, GL_R32F);
        glBindImageTexture(2, texV, 0, GL_FALSE, 0, GL_READ_WRITE, GL_R32F);
        int half = (gridW + 1) / 2;
        for (int i = 0; i < solveIters; i++) {
            for (int parity = 0; parity <= 1; parity++) {
                pSolve.set("uParity", parity);
                glDispatchCompute((half + 7) / 8, (gridH + 7) / 8, 1);
                glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            }
        }
        barrier();

        pG2P.use();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, particles);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, accum);
        pG2P.set("uDt", dt);
        pG2P.set("uCapacity", span);
        pG2P.set2i("uGrid", gridW, gridH);
        pG2P.set("uFlipRatio", flipRatio);
        pG2P.set("uDrag", particleDrag);
        pG2P.set("uSettleTime", settleTime);
        pG2P.set("uCohesionSpeed", cohesion * 0.0025f);
        pG2P.set("uRestMass", particlesPerCell);
        pG2P.set("uMaxSpeed", maxSpeed);
        pG2P.set("uTexel", 1.0f / gridW, 1.0f / gridH);
        pG2P.set("uUNew", 0);
        pG2P.set("uVNew", 1);
        pG2P.set("uUOld", 2);
        pG2P.set("uVOld", 3);
        pG2P.set("uDensity", 4);
        glActiveTexture(GL_TEXTURE0 + 0); glBindTexture(GL_TEXTURE_2D, texU);
        glActiveTexture(GL_TEXTURE0 + 1); glBindTexture(GL_TEXTURE_2D, texV);
        glActiveTexture(GL_TEXTURE0 + 2); glBindTexture(GL_TEXTURE_2D, texUOld);
        glActiveTexture(GL_TEXTURE0 + 3); glBindTexture(GL_TEXTURE_2D, texVOld);
        glActiveTexture(GL_TEXTURE0 + 4); glBindTexture(GL_TEXTURE_2D, texDensity);
        glActiveTexture(GL_TEXTURE0);
        dispatch1D(span);
        barrier();
    }

    void draw(float state, GLuint fbo, int w, int h, bool clear) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, w, h);
        if (clear) { glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT); }
        if (emitted > 0) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_ONE, GL_ONE);
            glUseProgram(drawProgram);
            glUniform1f(glGetUniformLocation(drawProgram, "uPointSize"), pointSize);
            glUniform1f(glGetUniformLocation(drawProgram, "uWantState"), state);
            glBindVertexArray(vao);
            glDrawArrays(GL_POINTS, 0, liveSpan());
            glBindVertexArray(0);
            glDisable(GL_BLEND);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    void clearPool() {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, particles);
        std::vector<char> zeros((size_t)capacity * 32, 0);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (GLsizeiptr)zeros.size(),
                     zeros.data(), GL_DYNAMIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        head = 0;
        emitted = 0;
    }
};

// ------------------------------------------------------------ composite

static const char *COMPOSITE_VERT = R"(#version 430
out vec2 vUv;
void main() {
    vec2 v = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vUv = v;
    gl_Position = vec4(v * 2.0 - 1.0, 0.0, 1.0);
})";

static const char *COMPOSITE_FRAG = R"(#version 430
in vec2 vUv;
out vec4 fragColor;
layout(binding = 0) uniform sampler2D uBackground;
layout(binding = 1) uniform sampler2D uLive;
void main() {
    // everything is premultiplied: the set layer over white paper, then the
    // live particles (black ink, alpha only) over that
    vec4 bg = texture(uBackground, vUv);
    vec3 paper = bg.rgb + vec3(1.0 - clamp(bg.a, 0.0, 1.0));
    vec4 live = texture(uLive, vUv);
    vec3 col = live.rgb + paper * (1.0 - clamp(live.a, 0.0, 1.0));
    fragColor = vec4(col, 1.0);
})";

// ------------------------------------------------------------ app

struct App {
    Flip flip;
    GLuint background = 0, backgroundB = 0, live = 0;
    GLuint fboBackground = 0, fboLive = 0;
    GLuint compositeProgram = 0, emptyVao = 0;
    int fbW = 0, fbH = 0;

    // tools: 0 paint, 1 glitch (pixel sort)
    int tool = 0;
    float glitchLo = 0.25f, glitchHi = 0.85f;
    bool glitchVertical = false, glitchDescending = false;
    Prog pPixelSort, pCopyRect;
    bool haveGlitchLast = false;
    float glitchLastX = 0, glitchLastY = 0, glitchCarry = 0;

    bool mouseDown = false;
    double mouseX = 0, mouseY = 0;
    bool havePourLast = false;
    float pourLastX = 0, pourLastY = 0, pourLastVx = 0, pourLastVy = 0;
    float pourDebt = 0;

    void allocate(int w, int h) {
        fbW = w; fbH = h;
        background = makeTexture(w, h, GL_RGBA16F, GL_LINEAR);
        backgroundB = makeTexture(w, h, GL_RGBA16F, GL_LINEAR);
        live = makeTexture(w, h, GL_RGBA16F, GL_LINEAR);
        pPixelSort.id = computeProgram("pixelsort.comp");
        pCopyRect.id = computeProgram("copy_rect.comp");
        glGenFramebuffers(1, &fboBackground);
        glBindFramebuffer(GL_FRAMEBUFFER, fboBackground);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, background, 0);
        glGenFramebuffers(1, &fboLive);
        glBindFramebuffer(GL_FRAMEBUFFER, fboLive);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, live, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, fboBackground);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        flip.resize((float)w / (float)h);
    }

    // the particle medium's one emitter, streamed along the cursor's path
    void pour(float dt) {
        if (!mouseDown || flip.flowRate <= 0 || fbW == 0) {
            havePourLast = false;
            pourDebt = 0;
            return;
        }
        float cx = (float)(mouseX / fbW);
        float cy = 1.0f - (float)(mouseY / fbH);
        float vx = 0, vy = 0;
        if (havePourLast && dt > 0) {
            float scale = 12.0f / (60.0f * dt);
            vx = (cx - pourLastX) * scale;
            vy = (cy - pourLastY) * scale;
        }
        float fromX = havePourLast ? pourLastX : cx;
        float fromY = havePourLast ? pourLastY : cy;
        float velAx = pourLastVx, velAy = pourLastVy;
        pourLastX = cx; pourLastY = cy;
        pourLastVx = vx; pourLastVy = vy;
        havePourLast = true;

        pourDebt += flip.flowRate * dt * (float)flip.countFor(flip.brushRadius);
        int n = (int)pourDebt;
        if (n <= 0) return;
        pourDebt -= (float)n;

        float inh = std::min(1.0f, std::max(0.0f, flip.flipRatio));
        flip.emit(fromX, fromY, cx, cy,
                  velAx * inh, velAy * inh, vx * inh, vy * inh,
                  std::min(n, 8192));
    }

    // One glitch dab: sort every line crossing the disc into the back
    // buffer, then copy just the bounding box forward -- same two passes as
    // the Android host, same shaders.
    void glitchDab(float u, float v) {
        int cx = (int)(u * fbW), cy = (int)(v * fbH);
        int r = std::min(127, std::max(2, (int)(flip.brushRadius * fbH)));

        pPixelSort.use();
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, background);
        pPixelSort.set("uSrc", 0);
        glBindImageTexture(0, backgroundB, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        pPixelSort.set2i("uCentre", cx, cy);
        pPixelSort.set("uRadius", r);
        pPixelSort.set("uVertical", glitchVertical ? 1 : 0);
        pPixelSort.set("uLo", glitchLo);
        pPixelSort.set("uHi", glitchHi);
        pPixelSort.set("uDescending", glitchDescending ? 1 : 0);
        glDispatchCompute(2 * r + 1, 1, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        pCopyRect.use();
        glBindTexture(GL_TEXTURE_2D, backgroundB);
        pCopyRect.set("uSrc", 0);
        glBindImageTexture(0, background, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        pCopyRect.set2i("uOrigin", cx - r, cy - r);
        pCopyRect.set2i("uSize", 2 * r + 1, 2 * r + 1);
        glDispatchCompute((2 * r + 8) / 8, (2 * r + 8) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    // dabs strung along the mouse path half a radius apart, as on Android
    void glitchStroke() {
        if (!mouseDown || fbW == 0) { haveGlitchLast = false; return; }
        float cx = (float)(mouseX / fbW);
        float cy = 1.0f - (float)(mouseY / fbH);
        if (!haveGlitchLast) {
            glitchDab(cx, cy);
            haveGlitchLast = true;
            glitchLastX = cx; glitchLastY = cy; glitchCarry = 0;
            return;
        }
        float dx = (cx - glitchLastX) * flip.aspect, dy = cy - glitchLastY;
        float dist = std::sqrt(dx * dx + dy * dy);
        float spacing = std::max(flip.brushRadius * 0.5f, 0.002f);
        float next = spacing - glitchCarry;
        int stamps = 0;
        while (next <= dist && stamps < 64) {
            float t = next / dist;
            glitchDab(glitchLastX + (cx - glitchLastX) * t, glitchLastY + (cy - glitchLastY) * t);
            next += spacing;
            stamps++;
        }
        glitchCarry = stamps < 64 ? dist - (next - spacing) : 0;
        glitchLastX = cx; glitchLastY = cy;
    }

    // Loads a picture as set paint: centre-cropped to the canvas, resampled
    // to its resolution, premultiplied, rows flipped for GL.
    bool importImage(const char *path) {
        int w = 0, h = 0, n = 0;
        unsigned char *px = stbi_load(path, &w, &h, &n, 4);
        if (!px) { std::fprintf(stderr, "could not read %s\n", path); return false; }
        float canvasAspect = (float)fbW / (float)fbH;
        float srcAspect = (float)w / (float)h;
        int cropW = w, cropH = h;
        if (srcAspect > canvasAspect) cropW = std::max(1, (int)(h * canvasAspect));
        else cropH = std::max(1, (int)(w / canvasAspect));
        int ox = (w - cropW) / 2, oy = (h - cropH) / 2;

        std::vector<float> buf((size_t)fbW * fbH * 4);
        for (int y = 0; y < fbH; y++) {
            // GL row 0 is the bottom; image row 0 is the top
            int sy = oy + (int)((float)(fbH - 1 - y) * cropH / fbH);
            for (int x = 0; x < fbW; x++) {
                int sx = ox + (int)((float)x * cropW / fbW);
                const unsigned char *c = px + ((size_t)sy * w + sx) * 4;
                float a = c[3] / 255.0f;
                float *o = &buf[((size_t)y * fbW + x) * 4];
                o[0] = c[0] / 255.0f * a;
                o[1] = c[1] / 255.0f * a;
                o[2] = c[2] / 255.0f * a;
                o[3] = a;
            }
        }
        stbi_image_free(px);
        glBindTexture(GL_TEXTURE_2D, background);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, fbW, fbH, GL_RGBA, GL_FLOAT, buf.data());
        glBindTexture(GL_TEXTURE_2D, 0);
        std::printf("loaded %s (%dx%d)\n", path, w, h);
        return true;
    }

    void frame(float dt) {
        if (tool == 1) glitchStroke();
        else pour(dt);
        if (flip.emitted > 0) flip.step(dt);

        // freshly dried particles land in the background, permanently
        flip.draw(2.0f, fboBackground, fbW, fbH, false);
        // live particles are redrawn from scratch each frame
        flip.draw(1.0f, fboLive, fbW, fbH, true);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, fbW, fbH);
        glUseProgram(compositeProgram);
        glActiveTexture(GL_TEXTURE0 + 0); glBindTexture(GL_TEXTURE_2D, background);
        glActiveTexture(GL_TEXTURE0 + 1); glBindTexture(GL_TEXTURE_2D, live);
        glActiveTexture(GL_TEXTURE0);
        glBindVertexArray(emptyVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
    }
};

static App app;

static void onMouseButton(GLFWwindow *, int button, int action, int) {
    if (button == GLFW_MOUSE_BUTTON_LEFT) app.mouseDown = action == GLFW_PRESS;
}
static void onCursor(GLFWwindow *, double x, double y) {
    app.mouseX = x;
    app.mouseY = y;
}
static void onDrop(GLFWwindow *, int count, const char **paths) {
    if (count > 0) app.importImage(paths[0]);
}
static void onKey(GLFWwindow *, int key, int, int action, int) {
    if (action != GLFW_PRESS && action != GLFW_REPEAT) return;
    Flip &f = app.flip;
    switch (key) {
        case GLFW_KEY_W: f.flowRate = std::min(40.0f, f.flowRate + 2); break;
        case GLFW_KEY_S: f.flowRate = std::max(0.0f, f.flowRate - 2); break;
        case GLFW_KEY_E: f.settleTime = std::min(10.0f, f.settleTime + 0.5f); break;
        case GLFW_KEY_D: f.settleTime = std::max(0.0f, f.settleTime - 0.5f); break;
        case GLFW_KEY_R: f.flipRatio = std::min(1.0f, f.flipRatio + 0.05f); break;
        case GLFW_KEY_F: f.flipRatio = std::max(0.0f, f.flipRatio - 0.05f); break;
        case GLFW_KEY_T: f.particleDrag = std::min(4.6f, f.particleDrag + 0.1f); break;
        case GLFW_KEY_G: f.particleDrag = std::max(0.0f, f.particleDrag - 0.1f); break;
        case GLFW_KEY_Q: f.cohesion = std::min(200.0f, f.cohesion + 10); break;
        case GLFW_KEY_A: f.cohesion = std::max(0.0f, f.cohesion - 10); break;
        case GLFW_KEY_1: app.tool = 0; break;
        case GLFW_KEY_2: app.tool = 1; break;
        case GLFW_KEY_LEFT_BRACKET: f.brushRadius = std::max(0.004f, f.brushRadius - 0.004f); break;
        case GLFW_KEY_RIGHT_BRACKET: f.brushRadius = std::min(0.17f, f.brushRadius + 0.004f); break;
        case GLFW_KEY_H: app.glitchLo = std::max(0.0f, app.glitchLo - 0.05f); break;
        case GLFW_KEY_J: app.glitchLo = std::min(app.glitchHi, app.glitchLo + 0.05f); break;
        case GLFW_KEY_K: app.glitchHi = std::max(app.glitchLo, app.glitchHi - 0.05f); break;
        case GLFW_KEY_L: app.glitchHi = std::min(1.0f, app.glitchHi + 0.05f); break;
        case GLFW_KEY_V: app.glitchVertical = !app.glitchVertical; break;
        case GLFW_KEY_B: app.glitchDescending = !app.glitchDescending; break;
        case GLFW_KEY_C:
            f.clearPool();
            glBindFramebuffer(GL_FRAMEBUFFER, app.fboBackground);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            break;
    }
}

int main(int argc, char **argv) {
    if (!glfwInit()) { std::fprintf(stderr, "glfwInit failed\n"); return 1; }
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
    glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);   // a painting keeps its shape

    GLFWwindow *win = glfwCreateWindow(1280, 720, "MaxPaint", nullptr, nullptr);
    if (!win) {
        std::fprintf(stderr, "window creation failed (OpenGL 4.3 required)\n");
        return 1;
    }
    glfwMakeContextCurrent(win);
    glfwSwapInterval(1);
    if (!loadGL(glfwGetProcAddress)) return 1;

    glEnable(GL_PROGRAM_POINT_SIZE);

    app.flip.init();
    app.compositeProgram = linkProgram(
        {compile(GL_VERTEX_SHADER, COMPOSITE_VERT, "composite.vert"),
         compile(GL_FRAGMENT_SHADER, COMPOSITE_FRAG, "composite.frag")},
        "composite");
    glGenVertexArrays(1, &app.emptyVao);

    int fw = 0, fh = 0;
    glfwGetFramebufferSize(win, &fw, &fh);
    app.allocate(fw, fh);

    glfwSetMouseButtonCallback(win, onMouseButton);
    glfwSetCursorPosCallback(win, onCursor);
    glfwSetKeyCallback(win, onKey);
    glfwSetDropCallback(win, onDrop);
    if (argc > 1) app.importImage(argv[1]);

    double last = glfwGetTime();
    double titleAt = 0;
    while (!glfwWindowShouldClose(win)) {
        double now = glfwGetTime();
        float dt = (float)std::min(std::max(now - last, 1.0 / 120.0), 1.0 / 20.0);
        last = now;

        app.frame(dt);
        glfwSwapBuffers(win);
        glfwPollEvents();

        if (now - titleAt > 0.25) {
            titleAt = now;
            char title[256];
            if (app.tool == 0)
                std::snprintf(title, sizeof title,
                    "MaxPaint  paint [1]  |  flow %.0f/s [W/S]  settle %.1fs [E/D]  "
                    "inherit %.0f%% [R/F]  drag %.0f%%/s [T/G]  cohesion %.0f [Q/A]  "
                    "size %.3f [ ]  clear [C]  |  drop an image to load it",
                    app.flip.flowRate, app.flip.settleTime, app.flip.flipRatio * 100,
                    (1.0 - std::exp(-app.flip.particleDrag)) * 100, app.flip.cohesion,
                    app.flip.brushRadius);
            else
                std::snprintf(title, sizeof title,
                    "MaxPaint  glitch: pixel sort [2]  |  band %.2f-%.2f [H/J K/L]  %s [V]  %s [B]  "
                    "size %.3f [ ]  clear [C]  |  drop an image to load it",
                    app.glitchLo, app.glitchHi,
                    app.glitchVertical ? "vertical" : "horizontal",
                    app.glitchDescending ? "light to dark" : "dark to light",
                    app.flip.brushRadius);
            glfwSetWindowTitle(win, title);
        }
    }
    glfwTerminate();
    return 0;
}
