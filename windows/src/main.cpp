// MaxPaint for Windows: a desktop host for the SAME compute shaders the
// Android app ships. Desktop OpenGL 4.3 has the compute model GLES 3.1 was
// derived from, so the flip_*.comp files run verbatim -- the loader only
// rewrites the version header and drops standalone precision statements.
// The Python harness in ../tools/ remains the behavioural reference.
//
// A Dear ImGui panel carries the tools: fluid (the streamed FLIP pour
// with motion inheritance) with the Android presets and sliders, and
// glitch (pixel sorting under the brush). Open loads a picture as set
// paint --
// dropping a file on the window or passing its path on the command line
// does the same -- and Save PNG writes the canvas next to the executable,
// at the canvas's own resolution. The window and the canvas are separate:
// the window resizes freely, the canvas holds whatever size it was given
// and sits centred inside it.
// The keys from the first build still work: W/S flow, E/D settle, R/F
// motion inheritance, T/G drag, Q/A cohesion, [ ] size, 1-6 tool, M glitch
// mode, C clear.

#ifdef _WIN32
#  define WIN32_LEAN_AND_MEAN
#  define NOMINMAX
#  include <windows.h>
#  include <commdlg.h>
#endif

#define GLFW_INCLUDE_NONE
#include <GLFW/glfw3.h>
#include "gl43.h"

#include "imgui.h"
#include "imgui_impl_glfw.h"
#include "imgui_impl_opengl3.h"

#define STB_IMAGE_IMPLEMENTATION
#define STBI_NO_HDR
#define STBI_NO_LINEAR
#include "stb_image.h"
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"
#include <ctime>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cctype>
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
    void set4f(const char *n, float a, float b, float c, float d) const {
        glUniform4f(glGetUniformLocation(id, n), a, b, c, d);
    }
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
    float relief = 0.0f;          // downhill pull from the image, 0 off
    float shadeDry = 0.0f;        // paint sets sooner where the picture is buried
    float heightInk = 0.0f;       // bright planes take more pigment
    GLuint propsTex = 0;          // owned by the app; sampled in G2P and emit

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
        int w = std::max(8, (int)(gridRes * root) & ~1);
        int h = std::max(8, (int)(gridRes / root) & ~1);
        if (accum && w == gridW && h == gridH) return;
        gridW = w; gridH = h;
        // the canvas can be reshaped now, so the old grid has to go back
        if (accum) glDeleteBuffers(1, &accum);
        for (GLuint *t : {&texU, &texV, &texUOld, &texVOld, &texDensity})
            if (*t) { glDeleteTextures(1, t); *t = 0; }
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
        // the picture charges each drop: bright planes load, dark ones run thin
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, propsTex);
        pEmit.set("uProps", 0);
        pEmit.set("uHeightInk", propsTex ? heightInk : 0.0f);
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
            if (sepGrid) glDeleteBuffers(1, &sepGrid);
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
        pG2P.set("uProps", 5);
        pG2P.set("uRelief", propsTex ? relief : 0.0f);
        pG2P.set("uShadeDry", propsTex ? shadeDry : 0.0f);
        glActiveTexture(GL_TEXTURE0 + 0); glBindTexture(GL_TEXTURE_2D, texU);
        glActiveTexture(GL_TEXTURE0 + 1); glBindTexture(GL_TEXTURE_2D, texV);
        glActiveTexture(GL_TEXTURE0 + 2); glBindTexture(GL_TEXTURE_2D, texUOld);
        glActiveTexture(GL_TEXTURE0 + 3); glBindTexture(GL_TEXTURE_2D, texVOld);
        glActiveTexture(GL_TEXTURE0 + 4); glBindTexture(GL_TEXTURE_2D, texDensity);
        glActiveTexture(GL_TEXTURE0 + 5); glBindTexture(GL_TEXTURE_2D, propsTex);
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

// ------------------------------------------------------------ the gas

// A pair of textures read one way and written the other. Every Eulerian pass
// reads the whole field and writes the whole field, so none of them can work
// in place; the pressure solve is the exception and says so.
struct Duo {
    GLuint read = 0, write = 0;
    void make(int w, int h, GLenum fmt, GLint filter) {
        drop();
        read = makeTexture(w, h, fmt, filter);
        write = makeTexture(w, h, fmt, filter);
    }
    void drop() {
        if (read) glDeleteTextures(1, &read);
        if (write) glDeleteTextures(1, &write);
        read = write = 0;
    }
    void flip() { GLuint t = read; read = write; write = t; }
};

// The Eulerian medium the Android app calls its hero: a velocity field the
// brush pushes around, dye carried along by it, and a bake that moves settled
// dye out of the simulation and onto the layer. Same shaders as the phone,
// same order, same constants -- this host just drives them.
struct Gas {
    // tunables (the Smoke defaults from the Android host)
    int pressureIters = 30;
    float vorticity = 22.0f;
    float velocityDrag = 0.0f;
    float dyeDissipation = 0.05f;
    float settleSpeed = 0.6f;
    float bakeRate = 3.9f;
    float settleMinAge = 1.2f;
    float inkPerStroke = 3.47f;
    float velocityGain = 1.0f;
    float forceStrength = 1.0f;
    float combFrequency = 14.0f;
    float maxSpeed = 8.0f;
    float brushRadius = 0.05f;
    int forceMode = 0;            // 0 swirl, 1 push, 2 pinch, 3 comb

    int simW = 0, simH = 0;       // the velocity/pressure grid
    int dyeW = 0, dyeH = 0;       // dye and age, at canvas resolution
    float aspect = 1.0f;
    bool inUse = false;           // dormant until the brush is first used

    Duo velocity, dye, age;
    GLuint pressure = 0, curl = 0, divergence = 0;
    Prog pAdvect, pCurl, pVorticity, pDivergence, pPressureRB,
         pClearP, pGradSub, pBake, pSplat, pForce;

    static void dispatch(int w, int h) {
        glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
    }


    void init() {
        if (pAdvect.id) return;
        pAdvect.id = computeProgram("advect.comp");
        pCurl.id = computeProgram("curl.comp");
        pVorticity.id = computeProgram("vorticity.comp");
        pDivergence.id = computeProgram("divergence.comp");
        pPressureRB.id = computeProgram("pressure_rb.comp");
        pClearP.id = computeProgram("clearp.comp");
        pGradSub.id = computeProgram("gradsub.comp");
        pBake.id = computeProgram("bake.comp");
        pSplat.id = computeProgram("splat.comp");
        pForce.id = computeProgram("force.comp");
    }

    // The sim grid is coarser than the canvas -- pressure is the expensive
    // part and it does not need the resolution the dye does.
    void resize(int canvasW, int canvasH) {
        dyeW = canvasW; dyeH = canvasH;
        aspect = (float)canvasW / (float)canvasH;
        simW = std::max(32, ((canvasW / 2) + 1) & ~1);
        simH = std::max(32, ((canvasH / 2) + 1) & ~1);
        velocity.make(simW, simH, GL_RGBA16F, GL_LINEAR);
        dye.make(dyeW, dyeH, GL_RGBA16F, GL_LINEAR);
        age.make(dyeW, dyeH, GL_RGBA16F, GL_NEAREST);
        if (pressure) glDeleteTextures(1, &pressure);
        if (curl) glDeleteTextures(1, &curl);
        if (divergence) glDeleteTextures(1, &divergence);
        pressure = makeTexture(simW, simH, GL_R32F, GL_NEAREST);
        curl = makeTexture(simW, simH, GL_R32F, GL_NEAREST);
        divergence = makeTexture(simW, simH, GL_R32F, GL_NEAREST);
        inUse = false;
    }

    void setContact(const Prog &p) const {
        p.set("uAxis", 1.0f, 0.0f);
        p.set("uMinor", 1.0f);
    }

    // Momentum and pigment in one gesture, exactly as the phone does it:
    // velocity on the coarse grid, dye a little tighter so the mark reads
    // crisp, then age reset by lerp so Hold applies to a stroke drawn over
    // an old one rather than inheriting its clock.
    void splat(float u, float v, float du, float dv) {
        inUse = true;
        pSplat.use();
        pSplat.set("uPoint", u, v);
        pSplat.set("uAspect", aspect);
        setContact(pSplat);
        pSplat.set("uMode", 0);

        pSplat.set("uRadius", brushRadius);
        pSplat.set4f("uValue", du * velocityGain * aspect, dv * velocityGain, 0.0f, 0.0f);
        glBindImageTexture(0, velocity.read, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA16F);
        glBindImageTexture(1, velocity.write, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        dispatch(simW, simH);
        velocity.flip();

        pSplat.set("uRadius", brushRadius * 0.6f);
        // black ink, premultiplied by its own coverage
        pSplat.set4f("uValue", 0.0f, 0.0f, 0.0f, inkPerStroke);
        glBindImageTexture(0, dye.read, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA16F);
        glBindImageTexture(1, dye.write, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        dispatch(dyeW, dyeH);
        dye.flip();

        pSplat.set("uMode", 1);
        pSplat.set4f("uValue", 0.0f, 0.0f, 0.0f, 0.0f);
        glBindImageTexture(0, age.read, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA16F);
        glBindImageTexture(1, age.write, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        dispatch(dyeW, dyeH);
        age.flip();
    }

    /** Momentum with no pigment: stir, shove, pinch or comb what is there. */
    void force(float u, float v, float du, float dv, int mode, float strength) {
        inUse = true;
        pForce.use();
        pForce.set("uPoint", u, v);
        pForce.set("uDir", du * aspect, dv);
        pForce.set("uRadius", brushRadius * 1.5f);
        pForce.set("uAspect", aspect);
        pForce.set("uStrength", strength);
        pForce.set("uMode", mode);
        pForce.set("uCombFreq", combFrequency);
        pForce.set("uDt", 1.0f / 60.0f);
        glBindImageTexture(0, velocity.read, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA16F);
        glBindImageTexture(1, velocity.write, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        dispatch(simW, simH);
        velocity.flip();
    }

    void advect(GLuint srcTex, GLuint dstTex, int w, int h, float dt, float dissipation) {
        pAdvect.set("uDt", dt);
        pAdvect.set("uAspect", aspect);
        pAdvect.set("uDstTexel", 1.0f / w, 1.0f / h);
        pAdvect.set("uDissipation", dissipation);
        glActiveTexture(GL_TEXTURE0 + 0); glBindTexture(GL_TEXTURE_2D, srcTex);
        glActiveTexture(GL_TEXTURE0 + 1); glBindTexture(GL_TEXTURE_2D, velocity.read);
        pAdvect.set("uSrc", 0);
        pAdvect.set("uVel", 1);
        glBindImageTexture(0, dstTex, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        dispatch(w, h);
    }

    // One tick of the medium, in the order the phone runs it.
    void step(float dt, GLuint &bgRead, GLuint &bgWrite, bool freeze, bool thaw) {
        pCurl.use();
        glBindImageTexture(0, velocity.read, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA16F);
        glBindImageTexture(1, curl, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);
        dispatch(simW, simH);

        if (vorticity > 0.0f) {
            pVorticity.use();
            pVorticity.set("uStrength", vorticity);
            pVorticity.set("uDt", dt);
            glBindImageTexture(0, velocity.read, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA16F);
            glBindImageTexture(1, velocity.write, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
            glBindImageTexture(2, curl, 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32F);
            dispatch(simW, simH);
            velocity.flip();
        }

        pDivergence.use();
        glBindImageTexture(0, velocity.read, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA16F);
        glBindImageTexture(1, divergence, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);
        dispatch(simW, simH);

        // a warm start from last frame's pressure beats a cold clear
        pClearP.use();
        pClearP.set("uValue", 0.8f);
        glBindImageTexture(0, pressure, 0, GL_FALSE, 0, GL_READ_WRITE, GL_R32F);
        dispatch(simW, simH);

        // red-black Gauss-Seidel, in place: r32f is the one format an image
        // may be read and written at once, and each sweep is two half-width
        // passes so the thread count matches a single Jacobi iteration
        pPressureRB.use();
        glBindImageTexture(2, divergence, 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32F);
        int halfWidth = (simW + 1) / 2;
        for (int i = 0; i < pressureIters; i++) {
            for (int parity = 0; parity < 2; parity++) {
                glBindImageTexture(0, pressure, 0, GL_FALSE, 0, GL_READ_WRITE, GL_R32F);
                pPressureRB.set("uParity", parity);
                dispatch(halfWidth, simH);
            }
        }

        pGradSub.use();
        pGradSub.set("uDrag", velocityDrag);
        pGradSub.set("uDt", dt);
        pGradSub.set("uMaxSpeed", maxSpeed);
        glBindImageTexture(0, velocity.read, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA16F);
        glBindImageTexture(1, velocity.write, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glBindImageTexture(2, pressure, 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32F);
        dispatch(simW, simH);
        velocity.flip();

        pAdvect.use();
        advect(velocity.read, velocity.write, simW, simH, dt, 0.0f);
        velocity.flip();
        advect(dye.read, dye.write, dyeW, dyeH, dt, dyeDissipation);
        dye.flip();
        advect(age.read, age.write, dyeW, dyeH, dt, 0.0f);
        age.flip();

        bake(dt, bgRead, bgWrite, freeze, thaw);
    }

    // Settled dye leaves the simulation and lands on the layer. Thaw runs it
    // backwards and lifts baked paint back into the fluid.
    void bake(float dt, GLuint &bgRead, GLuint &bgWrite, bool freeze, bool thaw) {
        pBake.use();
        pBake.set("uDt", dt);
        pBake.set("uSettleSpeed", settleSpeed);
        pBake.set("uBakeRate", thaw ? bakeRate * 3.0f : bakeRate);
        pBake.set("uSettleMinAge", settleMinAge);
        pBake.set("uForce", freeze ? 1 : 0);
        pBake.set("uThaw", thaw ? 1 : 0);
        pBake.set("uAspect", aspect);
        pBake.set("uMaskPoint", 0.5f, 0.5f);
        pBake.set("uMaskRadius", -1.0f);

        glActiveTexture(GL_TEXTURE0 + 0); glBindTexture(GL_TEXTURE_2D, velocity.read);
        glActiveTexture(GL_TEXTURE0 + 1); glBindTexture(GL_TEXTURE_2D, dye.read);
        glActiveTexture(GL_TEXTURE0 + 2); glBindTexture(GL_TEXTURE_2D, bgRead);
        glActiveTexture(GL_TEXTURE0 + 3); glBindTexture(GL_TEXTURE_2D, age.read);
        glActiveTexture(GL_TEXTURE0);
        pBake.set("uVel", 0);
        pBake.set("uDyeSrc", 1);
        pBake.set("uBgSrc", 2);
        pBake.set("uAgeSrc", 3);
        glBindImageTexture(0, dye.write, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glBindImageTexture(1, bgWrite, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glBindImageTexture(2, age.write, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        dispatch(dyeW, dyeH);
        dye.flip();
        age.flip();
        GLuint t = bgRead; bgRead = bgWrite; bgWrite = t;   // the caller re-points its FBO
    }
};

// ------------------------------------------------------------ the nib

// Pen and charcoal. The mark goes into its own field rather than the fluid,
// so nothing can smear it, and a capillary pass creeps it into the paper and
// dries it onto the layer. Drawn as a capsule from the previous sample: a
// fast stroke stays a line instead of becoming a row of dots.
struct Nib {
    float radius = 0.006f;
    float hardness = 0.9f;
    float ink = 1.0f;
    float load = 1.0f;          // the desktop's stand-in for pen pressure
    float soak = 0.9f;
    float dry = 0.7f;
    float grain = 0.6f;
    float paperScale = 0.25f;
    float threshold = 0.02f;

    int w = 0, h = 0;
    float aspect = 1.0f;
    bool active = false;
    Duo field;
    Prog pNib, pSoak;

    void init() {
        if (pNib.id) return;
        pNib.id = computeProgram("nib.comp");
        pSoak.id = computeProgram("soak.comp");
    }

    void resize(int cw, int ch) {
        w = cw; h = ch;
        aspect = (float)cw / (float)ch;
        field.make(cw, ch, GL_RGBA16F, GL_LINEAR);
        active = false;
    }

    void mark(float u, float v, float pu, float pv) {
        active = true;
        pNib.use();
        pNib.set("uPoint", u, v);
        pNib.set("uPrev", pu, pv);
        pNib.set("uRadius", radius * std::max(load, 0.25f));
        pNib.set("uAspect", aspect);
        pNib.set("uInk", ink * load);
        pNib.set("uHardness", hardness);
        glBindImageTexture(0, field.read, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA16F);
        glBindImageTexture(1, field.write, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        Gas::dispatch(w, h);
        field.flip();
    }

    // capillary creep, then drying into the layer
    void step(float dt, GLuint &bgRead, GLuint &bgWrite) {
        pSoak.use();
        pSoak.set("uDt", dt);
        pSoak.set("uSoak", soak);
        pSoak.set("uDry", dry);
        pSoak.set("uGrain", grain);
        pSoak.set("uPaperScale", paperScale);
        pSoak.set("uThreshold", threshold);
        glActiveTexture(GL_TEXTURE0 + 0); glBindTexture(GL_TEXTURE_2D, field.read);
        glActiveTexture(GL_TEXTURE0 + 1); glBindTexture(GL_TEXTURE_2D, bgRead);
        glActiveTexture(GL_TEXTURE0);
        pSoak.set("uInkSrc", 0);
        pSoak.set("uBgSrc", 1);
        glBindImageTexture(0, field.write, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        glBindImageTexture(1, bgWrite, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        Gas::dispatch(w, h);
        field.flip();
        GLuint t = bgRead; bgRead = bgWrite; bgWrite = t;
    }
};

// ------------------------------------------------------------ presets

// The Android Flip presets (android/.../Presets.kt), value for value.
struct FlipPreset {
    const char *name;
    float flow, inherit, drag, settle, cohesion, pointSize, density;
};
static const FlipPreset FLIP_PRESETS[] = {
    {"Wet Paint", 40, 0.60f, 0.25f, 2.0f, 30, 3, 120},
    {"Splatter",  12, 0.99f, 0.02f, 1.5f,  6, 3,  51},
    {"Fling",     24, 0.97f, 0.05f, 1.5f,  8, 4,  29},
    {"Honey",     10, 0.45f, 1.60f, 2.5f, 26, 6,  51},
    {"Mercury",   24, 0.97f, 0.04f, 2.5f, 38, 5,  61},
};
// Gray-Scott lives in a narrow window of feed and kill; a hundredth on either
// is the difference between a pattern and a field that dies. These are the
// classic pairs, so the presets matter more here than the sliders do.
struct RdPreset { const char *name; float feed, kill; };
static const RdPreset RD_PRESETS[] = {
    {"Coral",         0.0545f, 0.0620f},
    {"Labyrinth",     0.0290f, 0.0570f},
    {"Leopard spots", 0.0367f, 0.0649f},
    {"Worms",         0.0580f, 0.0650f},
    {"Flower",        0.0250f, 0.0600f},
};

struct GlitchPreset { const char *name; float lo, hi; bool vertical; };
static const GlitchPreset GLITCH_PRESETS[] = {
    {"Midtones",   0.25f, 0.85f, false},
    {"Shadows",    0.00f, 0.50f, false},
    {"Highlights", 0.50f, 1.00f, false},
    {"Rain",       0.20f, 0.90f, true},
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
layout(binding = 2) uniform sampler2D uProps;
layout(binding = 3) uniform sampler2D uRd;    // rg = the reaction's A, B
layout(binding = 4) uniform sampler2D uDye;   // the gas still in the air
layout(binding = 5) uniform sampler2D uNib;   // ink the paper has not dried yet
uniform int uView;   // 0 paint, 1 height, 2 normals, 3 occlusion, 4 reaction
uniform float uRdInk;      // 0 hides the reaction
uniform float uRdLo;
uniform float uRdHi;
void main() {
    if (uView == 4) {
        fragColor = vec4(vec3(texture(uRd, vUv).g * 2.5), 1.0);
        return;
    }
    if (uView != 0) {
        vec4 pr = texture(uProps, vUv);
        vec3 c = uView == 1 ? vec3(pr.r)
               : uView == 2 ? vec3(pr.g * 0.5 + 0.5, pr.b * 0.5 + 0.5, 1.0)
               : vec3(pr.a);
        fragColor = vec4(c, 1.0);
        return;
    }
    // everything is premultiplied: the set layer over white paper, then the
    // live particles (black ink, alpha only) over that
    vec4 bg = texture(uBackground, vUv);
    vec3 paper = bg.rgb + vec3(1.0 - clamp(bg.a, 0.0, 1.0));
    // media still in play sit over the layer, under the particles
    vec4 wet = texture(uNib, vUv);
    paper = wet.rgb + paper * (1.0 - clamp(wet.a, 0.0, 1.0));
    vec4 gas = texture(uDye, vUv);
    paper = gas.rgb + paper * (1.0 - clamp(gas.a, 0.0, 1.0));
    vec4 live = texture(uLive, vUv);
    vec3 col = live.rgb + paper * (1.0 - clamp(live.a, 0.0, 1.0));
    // the reaction lies over the picture as ink rather than replacing it, so
    // the photograph stays legible under whatever grows on it
    if (uRdInk > 0.0) {
        float b = texture(uRd, vUv).g;
        col = mix(col, vec3(0.0), smoothstep(uRdLo, uRdHi, b) * uRdInk);
    }
    fragColor = vec4(col, 1.0);
})";

// ------------------------------------------------------------ app

struct App {
    Flip flip;
    Gas gas;
    Nib nib;
    bool haveNibLast = false;
    float nibLastX = 0, nibLastY = 0;
    bool haveGasLast = false;
    float gasLastX = 0, gasLastY = 0;
    bool gasFreeze = false, gasThaw = false;
    bool gasForceOnly = false;   // stir what is there instead of adding pigment
    GLuint background = 0, backgroundB = 0, live = 0;
    GLuint fboBackground = 0, fboLive = 0;
    GLuint compositeProgram = 0, emptyVao = 0;

    // The canvas is the painting, in texels; the window is the glass we show
    // it through. They started out the same texture size and no longer are:
    // the canvas keeps its own resolution and sits letterboxed in the window.
    int canvasW = 0, canvasH = 0;
    int winW = 0, winH = 0;              // framebuffer, in pixels
    float pixelScaleX = 1, pixelScaleY = 1;   // framebuffer px per window px
    // A side limit from the driver, and an area limit from memory: the canvas
    // carries four RGBA16F layers, 32 bytes a pixel, so 32 MP is about a
    // gigabyte of it and far enough for anything a screen will show.
    int maxCanvas = 8192;                // GL_MAX_TEXTURE_SIZE, read at startup
    static constexpr int maxCanvasPixels = 32 << 20;
    int pendingCanvasW = 0, pendingCanvasH = 0;   // applied at the top of frame()
    int canvasInput[2] = {1280, 720};
    int windowInput[2] = {1280, 720};
    bool windowInputActive = false;
    GLFWwindow *window = nullptr;

    // The layer stack. The active layer's texture IS `background`, so every
    // brush keeps painting into the same place it always did and only the
    // compositor has to know there is more than one. Switching layers hands
    // `background` over to the new one.
    struct CanvasLayer {
        GLuint tex = 0;
        bool visible = true;
        float opacity = 1.0f;
        char name[24] = "Layer";
    };
    std::vector<CanvasLayer> layers;
    int activeLayer = 0;
    // The layer the brushes LOOK at, as against the one they paint into.
    // -1 means the flattened stack, which is what they always used. Point it
    // at a photograph and you can scatter that photograph onto an empty layer
    // above it, or let its relief steer paint poured somewhere else.
    int referenceLayer = -1;
    GLuint flatA = 0, flatB = 0, fboScratch = 0, flatResult = 0;
    Prog pLayerOver;

    // Undo history. A step is a whole snapshot of the set layer, which is the
    // only thing that lasts -- wet particles and the reaction field are live
    // state and are not restored. Depth is whatever fits a memory budget: a
    // canvas-sized RGBA16F is 8 bytes a pixel, so a big canvas gets fewer
    // steps rather than a gigabyte of them.
    static constexpr size_t undoBudget = 512u << 20;
    std::vector<GLuint> history;
    std::vector<int> historyLayer;
    int historyLen = 0, historyCur = 0;
    bool canvasDirty = false;

    struct Rect { int x, y, w, h; };

    // The canvas, centred and scaled to fit the window without distorting it.
    // Viewport y counts from the bottom, but centring is symmetric, so the
    // same margin serves either way up.
    Rect canvasRect() const {
        if (canvasW <= 0 || canvasH <= 0 || winW <= 0 || winH <= 0)
            return {0, 0, winW, winH};
        float canvasAspect = (float)canvasW / (float)canvasH;
        int w, h;
        if ((float)winW / (float)winH > canvasAspect) {
            h = winH; w = std::max(1, (int)(winH * canvasAspect + 0.5f));
        } else {
            w = winW; h = std::max(1, (int)(winW / canvasAspect + 0.5f));
        }
        return {(winW - w) / 2, (winH - h) / 2, w, h};
    }

    // tools: 0 paint, 1 glitch (pixel sort)
    int tool = 0;
    float glitchLo = 0.25f, glitchHi = 0.85f;
    float glitchFalloff = 0.5f;   // fraction of the radius the edge fades over
    bool glitchVertical = false, glitchDescending = false;
    // glitch modes: 0 pixel sort, 1 channel drift, 2 block shuffle,
    // 3 slit-scan, 4 bit crush -- all on the same back-buffer plumbing
    int glitchMode = 0;
    float driftAmount = 12.0f;    // legible on a grey photo without being silly
    int blockSize = 8;            // a JPEG's own grid
    float blockAmount = 14.0f;
    float slitStretch = 0.6f;
    int crushLevels = 5;
    float glitchSeed = 1.0f;      // moves each dab, so holding still reshuffles
    float glitchEdgeBound = 0.0f; // 0 off: runs stop at the picture's edges
    bool glitchAutoDir = false;   // the surface picks the sort axis
    // scatter (tool 5): boxes turned and stretched by the picture under them
    int scatterCount = 24;
    float scatterSize = 14.0f;
    float scatterStretch = 2.5f;
    float scatterAlign = 1.0f;
    float scatterOpacity = 1.0f;
    float scatterJitter = 0.45f;
    int scatterColour = 0;        // 0 under the box, 1 in the disc, 2 anywhere
    float scatterSeed = 1.0f;
    bool haveScatterLast = false;
    float scatterLastX = 0, scatterLastY = 0, scatterCarry = 0;
    Prog pScatter;

    Prog pPixelSort, pCopyRect, pProps;
    Prog pDrift, pBlocks, pSlit, pCrush;

    // reaction-diffusion (tool 2): two state textures, ping-ponged, holding
    // the substrate in red and the reagent in green
    GLuint rdTex[2] = {0, 0};
    int rdCur = 0;
    bool rdSeeded = false, rdRun = true;
    int rdIters = 6, rdPreset = 0;   // fast enough to watch, slow enough to stop
    float rdFeed = 0.0545f, rdKill = 0.0620f;
    float rdDa = 1.0f, rdDb = 0.5f, rdDt = 1.0f;
    float rdCouple = 0.0f;          // how hard the picture steers the chemistry
    float rdInk = 1.0f, rdLo = 0.10f, rdHi = 0.30f;
    bool haveRdLast = false;
    float rdLastX = 0, rdLastY = 0, rdCarry = 0;
    Prog pRdClear, pRdSeed, pRdStep;
    GLuint props = 0;
    int view = 0;                 // 0 paint, 1 height, 2 normals, 3 occlusion
    bool reliefInvert = false;    // dark is high
    float aoRadius = 24.0f, slope = 40.0f;
    int propsFrame = 0;
    bool haveGlitchLast = false;
    float glitchLastX = 0, glitchLastY = 0, glitchCarry = 0;

    bool mouseDown = false;      // the button, as GLFW reports it
    bool painting = false;       // the button, and not over the panel
    double mouseX = 0, mouseY = 0;
    bool savePending = false;
    int flipPreset = 0, glitchPreset = 0;
    char status[256] = "";
    bool havePourLast = false;
    float pourLastX = 0, pourLastY = 0, pourLastVx = 0, pourLastVy = 0;
    float pourDebt = 0;

    // Builds the canvas at w x h. Called again whenever the canvas is
    // resized, so everything it made last time has to go back first.
    void allocate(int w, int h) {
        canvasW = w; canvasH = h;
        for (GLuint *t : {&background, &backgroundB, &live, &props})
            if (*t) { glDeleteTextures(1, t); *t = 0; }
        for (GLuint *f : {&fboBackground, &fboLive})
            if (*f) { glDeleteFramebuffers(1, f); *f = 0; }
        background = makeTexture(w, h, GL_RGBA16F, GL_LINEAR);
        backgroundB = makeTexture(w, h, GL_RGBA16F, GL_LINEAR);
        live = makeTexture(w, h, GL_RGBA16F, GL_LINEAR);
        if (!pPixelSort.id) pPixelSort.id = computeProgram("pixelsort.comp");
        if (!pCopyRect.id) pCopyRect.id = computeProgram("copy_rect.comp");
        if (!pProps.id) pProps.id = computeProgram("props.comp");
        if (!pScatter.id) pScatter.id = computeProgram("scatter.comp");
        if (!pDrift.id) pDrift.id = computeProgram("glitch_drift.comp");
        if (!pBlocks.id) pBlocks.id = computeProgram("glitch_blocks.comp");
        if (!pSlit.id) pSlit.id = computeProgram("glitch_slit.comp");
        if (!pCrush.id) pCrush.id = computeProgram("glitch_crush.comp");
        if (!pLayerOver.id) pLayerOver.id = computeProgram("composite.comp");
        if (!pRdClear.id) pRdClear.id = computeProgram("rd_clear.comp");
        if (!pRdSeed.id) pRdSeed.id = computeProgram("rd_seed.comp");
        if (!pRdStep.id) pRdStep.id = computeProgram("rd_step.comp");
        for (int i = 0; i < 2; i++) {
            if (rdTex[i]) glDeleteTextures(1, &rdTex[i]);
            rdTex[i] = makeTexture(w, h, GL_RGBA16F, GL_LINEAR);
        }
        props = makeTexture(w, h, GL_RGBA16F, GL_LINEAR);
        flip.propsTex = props;
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
        // the flatten buffers, and the stack itself
        for (GLuint *t : {&flatA, &flatB}) if (*t) { glDeleteTextures(1, t); *t = 0; }
        flatA = makeTexture(w, h, GL_RGBA16F, GL_LINEAR);
        flatB = makeTexture(w, h, GL_RGBA16F, GL_LINEAR);
        if (!fboScratch) glGenFramebuffers(1, &fboScratch);
        if (layers.empty()) {
            CanvasLayer first;
            first.tex = background;
            std::snprintf(first.name, sizeof first.name, "Layer 1");
            layers.push_back(first);
            activeLayer = 0;
        } else {
            activeLayer = std::min(activeLayer, (int)layers.size() - 1);
            for (int i = 0; i < (int)layers.size(); i++) {
                if (i == activeLayer) { layers[i].tex = background; continue; }
                if (layers[i].tex) glDeleteTextures(1, &layers[i].tex);
                layers[i].tex = makeTexture(w, h, GL_RGBA16F, GL_LINEAR);
                clearLayer(layers[i].tex);
            }
        }
        flatResult = background;

        flip.resize((float)w / (float)h);
        gas.init();
        gas.resize(w, h);
        nib.init();
        nib.resize(w, h);
        rdClear();
        allocHistory(w, h);
    }

    void clearLayer(GLuint tex) {
        glBindFramebuffer(GL_FRAMEBUFFER, fboScratch);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    // ------------------------------------------------------------ layers

    GLuint layerTex(int i) const {
        return i == activeLayer ? background : layers[i].tex;
    }

    GLuint referenceTex() const {
        if (referenceLayer >= 0 && referenceLayer < (int)layers.size())
            return layerTex(referenceLayer);
        return flatResult ? flatResult : background;
    }

    // The raw switch, with no history side effects: an undo restoring a step
    // taken on another layer has to go there without committing anything.
    void switchLayerRaw(int i) {
        if (i < 0 || i >= (int)layers.size() || i == activeLayer) return;
        layers[activeLayer].tex = background;   // hand it back
        activeLayer = i;
        background = layers[i].tex;
        rebindBackgroundFbo();
        propsFrame = 0;
    }

    void setActiveLayer(int i) {
        if (i < 0 || i >= (int)layers.size() || i == activeLayer) return;
        commitIfDirty();
        switchLayerRaw(i);
    }

    void addLayer() {
        commitIfDirty();
        layers[activeLayer].tex = background;
        CanvasLayer l;
        l.tex = makeTexture(canvasW, canvasH, GL_RGBA16F, GL_LINEAR);
        clearLayer(l.tex);
        std::snprintf(l.name, sizeof l.name, "Layer %d", (int)layers.size() + 1);
        if (referenceLayer > activeLayer) referenceLayer++;
        layers.insert(layers.begin() + activeLayer + 1, l);
        activeLayer++;
        background = layers[activeLayer].tex;
        rebindBackgroundFbo();
        historyLen = 0; historyCur = 0; canvasDirty = false;
        pushHistory();
    }

    void deleteLayer() {
        if (layers.size() <= 1) return;
        commitIfDirty();
        glDeleteTextures(1, &background);
        layers.erase(layers.begin() + activeLayer);
        if (referenceLayer == activeLayer) referenceLayer = -1;
        else if (referenceLayer > activeLayer) referenceLayer--;
        activeLayer = std::min(activeLayer, (int)layers.size() - 1);
        background = layers[activeLayer].tex;
        rebindBackgroundFbo();
        historyLen = 0; historyCur = 0; canvasDirty = false;
        pushHistory();
    }

    void moveLayer(int delta) {
        int to = activeLayer + delta;
        if (to < 0 || to >= (int)layers.size()) return;
        layers[activeLayer].tex = background;
        if (referenceLayer == activeLayer) referenceLayer = to;
        else if (referenceLayer == to) referenceLayer = activeLayer;
        std::swap(layers[activeLayer], layers[to]);
        activeLayer = to;
        background = layers[activeLayer].tex;
        rebindBackgroundFbo();
    }

    // The stack as one picture: what the screen shows, what a PNG saves, and
    // what the property maps read. One pass a layer, skipped entirely while
    // there is only the one -- which is the common case and the old path.
    GLuint flatten() {
        if (layers.size() == 1 && layers[0].visible && layers[0].opacity >= 1.0f)
            return background;
        clearLayer(flatA);
        GLuint accum = flatA, dst = flatB;
        for (int i = 0; i < (int)layers.size(); i++) {
            if (!layers[i].visible || layers[i].opacity <= 0.0f) continue;
            pLayerOver.use();
            glActiveTexture(GL_TEXTURE0 + 0); glBindTexture(GL_TEXTURE_2D, accum);
            glActiveTexture(GL_TEXTURE0 + 1); glBindTexture(GL_TEXTURE_2D, layerTex(i));
            glActiveTexture(GL_TEXTURE0);
            pLayerOver.set("uAccum", 0);
            pLayerOver.set("uLayer", 1);
            pLayerOver.set("uOpacity", layers[i].opacity);
            glBindImageTexture(0, dst, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
            Gas::dispatch(canvasW, canvasH);
            GLuint t = accum; accum = dst; dst = t;
        }
        return accum;
    }

    // The bake swaps the layer's front and back buffers, so the framebuffer
    // the FLIP medium draws dried particles into has to follow.
    void rebindBackgroundFbo() {
        glBindFramebuffer(GL_FRAMEBUFFER, fboBackground);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_2D, background, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    // The nib: a capsule from the last sample to this one, so the line holds
    // together however fast the hand moves.
    void nibStroke() {
        float cx = 0, cy = 0;
        if (!painting || canvasW == 0 || !canvasUV(cx, cy)) { haveNibLast = false; return; }
        if (!haveNibLast) { nibLastX = cx; nibLastY = cy; haveNibLast = true; }
        nib.mark(cx, cy, nibLastX, nibLastY);
        nibLastX = cx; nibLastY = cy;
        canvasDirty = true;
    }

    // The gas brush: pigment and momentum along the cursor's path, or bare
    // momentum when Stir is on -- a finger dragged through paint already down.
    void gasStroke(float dt) {
        float cx = 0, cy = 0;
        if (!painting || canvasW == 0 || !canvasUV(cx, cy)) { haveGasLast = false; return; }
        float du = 0, dv = 0;
        if (haveGasLast && dt > 0) {
            du = (cx - gasLastX) / std::max(dt, 1e-3f);
            dv = (cy - gasLastY) / std::max(dt, 1e-3f);
        }
        gasLastX = cx; gasLastY = cy;
        haveGasLast = true;
        if (gasForceOnly) gas.force(cx, cy, du, dv, gas.forceMode, gas.forceStrength);
        else gas.splat(cx, cy, du, dv);
        canvasDirty = true;
    }

    // ------------------------------------------------------------ undo

    void allocHistory(int w, int h) {
        for (GLuint t : history) if (t) glDeleteTextures(1, &t);
        history.clear();
        size_t perStep = (size_t)w * h * 8;   // RGBA16F
        int steps = (int)std::min<size_t>(16, std::max<size_t>(2, undoBudget / perStep));
        history.resize(steps, 0);
        historyLayer.assign(steps, 0);
        for (GLuint &t : history) t = makeTexture(w, h, GL_RGBA16F, GL_NEAREST);
        historyLen = 0;
        historyCur = 0;
        canvasDirty = false;
        pushHistory();        // the canvas as it starts is a state you can return to
    }

    void copyLayer(GLuint from, GLuint to) {
        glCopyImageSubData(from, GL_TEXTURE_2D, 0, 0, 0, 0,
                           to, GL_TEXTURE_2D, 0, 0, 0, 0, canvasW, canvasH, 1);
    }

    // Appends the canvas as it stands, dropping any redo beyond the cursor.
    // When the ring is full the oldest step falls off the front.
    void pushHistory() {
        if (history.empty() || canvasW <= 0) return;
        int cap = (int)history.size();
        if (historyLen > 0 && historyCur < historyLen - 1) historyLen = historyCur + 1;
        if (historyLen == cap) {
            GLuint oldest = history[0];
            history.erase(history.begin());
            history.push_back(oldest);
            int firstLayer = historyLayer[0];
            historyLayer.erase(historyLayer.begin());
            historyLayer.push_back(firstLayer);
            historyLen--;
            historyCur--;
        }
        copyLayer(background, history[historyLen]);
        historyLayer[historyLen] = activeLayer;
        historyCur = historyLen;
        historyLen++;
        canvasDirty = false;
    }

    // The newest stroke is only committed when something needs it to be --
    // starting the next stroke, or asking to undo this one. Committing at
    // mouse-up would catch the paint mid-dry, since the FLIP layer keeps
    // baking for seconds after the hand stops.
    void commitIfDirty() { if (canvasDirty) pushHistory(); }

    bool canUndo() const { return canvasDirty || historyCur > 0; }
    bool canRedo() const { return !canvasDirty && historyCur + 1 < historyLen; }

    void undo() {
        commitIfDirty();
        if (historyCur <= 0) return;
        historyCur--;
        restoreHistory();
        std::snprintf(status, sizeof status, "undo %d/%d", historyCur + 1, historyLen);
    }

    void redo() {
        if (historyCur + 1 >= historyLen) return;
        historyCur++;
        restoreHistory();
        std::snprintf(status, sizeof status, "redo %d/%d", historyCur + 1, historyLen);
    }

    void restoreHistory() {
        // a step belongs to the layer it was taken on; go back there first
        if (historyLayer[historyCur] != activeLayer)
            switchLayerRaw(historyLayer[historyCur]);
        copyLayer(history[historyCur], background);
        flip.clearPool();      // wet paint belongs to the stroke being undone
        havePourLast = false;
        haveGlitchLast = false;
        canvasDirty = false;
        propsFrame = 0;        // the maps describe a layer that just changed
    }

    // The reaction's resting state: substrate everywhere, no reagent. Both
    // buffers, so a step reads a sane field whichever way the ping-pong sits.
    void rdClear() {
        if (!pRdClear.id || canvasW <= 0) return;
        pRdClear.use();
        pRdClear.set2i("uSize", canvasW, canvasH);
        for (int i = 0; i < 2; i++) {
            glBindImageTexture(0, rdTex[i], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
            glDispatchCompute((canvasW + 7) / 8, (canvasH + 7) / 8, 1);
        }
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        rdCur = 0;
        rdSeeded = false;
        haveRdLast = false;
    }

    // Light the reaction under the brush. In place on the live state, so it
    // costs the dab's footprint rather than a pass over the canvas.
    void rdSeedDab(float u, float v) {
        int cx = (int)(u * canvasW), cy = (int)(v * canvasH);
        int r = std::min(127, std::max(2, (int)(flip.brushRadius * canvasH)));
        pRdSeed.use();
        glBindImageTexture(0, rdTex[rdCur], 0, GL_FALSE, 0, GL_READ_WRITE, GL_RGBA16F);
        pRdSeed.set2i("uCentre", cx, cy);
        pRdSeed.set("uRadius", r);
        pRdSeed.set("uFalloff", glitchFalloff);
        pRdSeed.set2i("uSize", canvasW, canvasH);
        glDispatchCompute((2 * r + 8) / 8, (2 * r + 8) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
        rdSeeded = true;
    }

    // Seeds along the cursor's path at the usual dab spacing, so a quick
    // stroke lights a continuous fuse rather than a row of dots.
    void rdStroke() {
        float cx = 0, cy = 0;
        if (!painting || canvasW == 0 || !canvasUV(cx, cy)) { haveRdLast = false; return; }
        if (!haveRdLast) {
            rdSeedDab(cx, cy);
            haveRdLast = true;
            rdLastX = cx; rdLastY = cy; rdCarry = 0;
            return;
        }
        float dx = (cx - rdLastX) * flip.aspect, dy = cy - rdLastY;
        float dist = std::sqrt(dx * dx + dy * dy);
        float spacing = std::max(flip.brushRadius * 0.5f, 0.002f);
        float next = spacing - rdCarry;
        int stamps = 0;
        while (next <= dist && stamps < 64) {
            float t = next / dist;
            rdSeedDab(rdLastX + (cx - rdLastX) * t, rdLastY + (cy - rdLastY) * t);
            next += spacing;
            stamps++;
        }
        rdCarry = stamps < 64 ? dist - (next - spacing) : 0;
        rdLastX = cx; rdLastY = cy;
    }

    // Several steps a frame: one step of Gray-Scott moves almost nothing, and
    // the pattern wants to be watched growing rather than waited for.
    void rdStep() {
        pRdStep.use();
        pRdStep.set2i("uSize", canvasW, canvasH);
        pRdStep.set("uDa", rdDa);
        pRdStep.set("uDb", rdDb);
        pRdStep.set("uFeed", rdFeed);
        pRdStep.set("uKill", rdKill);
        pRdStep.set("uDt", rdDt);
        pRdStep.set("uCouple", rdCouple);
        glActiveTexture(GL_TEXTURE0 + 1); glBindTexture(GL_TEXTURE_2D, background);
        pRdStep.set("uLayer", 1);
        for (int i = 0; i < rdIters; i++) {
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, rdTex[rdCur]);
            pRdStep.set("uSrc", 0);
            glBindImageTexture(0, rdTex[1 - rdCur], 0, GL_FALSE, 0,
                               GL_WRITE_ONLY, GL_RGBA16F);
            glDispatchCompute((canvasW + 7) / 8, (canvasH + 7) / 8, 1);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT |
                            GL_TEXTURE_FETCH_BARRIER_BIT);
            rdCur = 1 - rdCur;
        }
        glActiveTexture(GL_TEXTURE0);
    }

    // A new canvas size, with the set paint carried across: the dry layer is
    // rescaled into the new one. Wet particles hold positions in canvas space
    // and cannot follow, so the pool is emptied.
    void resizeCanvas(int w, int h) {
        w = std::min(maxCanvas, std::max(64, w));
        h = std::min(maxCanvas, std::max(64, h));
        // too many pixels: keep the shape, lose the scale
        if ((double)w * h > maxCanvasPixels) {
            double k = std::sqrt(maxCanvasPixels / ((double)w * h));
            w = std::max(64, (int)(w * k));
            h = std::max(64, (int)(h * k));
        }
        if (w == canvasW && h == canvasH) return;

        // Every layer is carried across, not just the one being painted on:
        // the old textures are kept aside, the stack is rebuilt at the new
        // size, then each layer's paint is rescaled into its successor.
        int oldW = canvasW, oldH = canvasH;
        std::vector<GLuint> oldTex;
        for (int i = 0; i < (int)layers.size(); i++) oldTex.push_back(layerTex(i));
        GLuint oldFbo = fboBackground;
        background = 0; fboBackground = 0;   // keep allocate() from freeing them
        for (CanvasLayer &l : layers) l.tex = 0;
        allocate(w, h);

        if (oldW > 0 && oldH > 0 && !oldTex.empty()) {
            GLuint readFbo = 0, drawFbo = 0;
            glGenFramebuffers(1, &readFbo);
            glGenFramebuffers(1, &drawFbo);
            for (int i = 0; i < (int)layers.size() && i < (int)oldTex.size(); i++) {
                if (!oldTex[i]) continue;
                glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
                glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                                       GL_TEXTURE_2D, oldTex[i], 0);
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo);
                glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                                       GL_TEXTURE_2D, layerTex(i), 0);
                glBlitFramebuffer(0, 0, oldW, oldH, 0, 0, w, h,
                                  GL_COLOR_BUFFER_BIT, GL_LINEAR);
            }
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            glDeleteFramebuffers(1, &readFbo);
            glDeleteFramebuffers(1, &drawFbo);
        }
        if (oldFbo) glDeleteFramebuffers(1, &oldFbo);
        for (GLuint t : oldTex) if (t) glDeleteTextures(1, &t);

        // allocate() opened the history on an empty canvas; the paint arrived
        // after it, so the first state has to be taken again
        historyLen = 0;
        historyCur = 0;
        canvasDirty = false;
        pushHistory();

        flip.clearPool();
        havePourLast = false;
        haveGlitchLast = false;
        canvasInput[0] = w; canvasInput[1] = h;
        std::snprintf(status, sizeof status, "canvas %d x %d", w, h);
    }

    // The cursor, in canvas coordinates: 0..1 across, 0..1 up from the
    // bottom. False when it is out on the surround rather than on the paint.
    bool canvasUV(float &u, float &v) const {
        Rect r = canvasRect();
        if (r.w <= 0 || r.h <= 0) return false;
        float x = (float)mouseX * pixelScaleX - (float)r.x;
        float y = (float)mouseY * pixelScaleY - (float)r.y;
        u = x / (float)r.w;
        v = 1.0f - y / (float)r.h;
        return u >= 0.0f && u <= 1.0f && v >= 0.0f && v <= 1.0f;
    }

    // the particle medium's one emitter, streamed along the cursor's path
    void pour(float dt) {
        float cx = 0, cy = 0;
        if (!painting || flip.flowRate <= 0 || canvasW == 0 || !canvasUV(cx, cy)) {
            havePourLast = false;
            pourDebt = 0;
            return;
        }
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
        canvasDirty = true;
    }

    // One glitch dab, whichever mode is chosen: work the disc into the back
    // buffer, then bring just that disc forward -- two passes, so a dab costs
    // its own footprint. Same plumbing and same shaders as the Android host.
    void glitchDab(float u, float v) {
        int cx = (int)(u * canvasW), cy = (int)(v * canvasH);
        int r = std::min(127, std::max(2, (int)(flip.brushRadius * canvasH)));

        // every mode reads the layer and writes its dab into the back buffer
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, background);
        glBindImageTexture(0, backgroundB, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);

        const Prog &mode = glitchMode == 1 ? pDrift
                         : glitchMode == 2 ? pBlocks
                         : glitchMode == 3 ? pSlit
                         : glitchMode == 4 ? pCrush : pPixelSort;
        mode.use();
        mode.set("uSrc", 0);
        mode.set2i("uCentre", cx, cy);
        mode.set("uRadius", r);
        mode.set("uFalloff", glitchFalloff);

        switch (glitchMode) {
            case 1:
                mode.set("uVertical", glitchVertical ? 1 : 0);
                mode.set("uAmount", driftAmount);
                break;
            case 2:
                mode.set("uBlock", blockSize);
                mode.set("uAmount", blockAmount);
                mode.set("uSeed", glitchSeed);
                glitchSeed += 1.0f;
                break;
            case 3:
                mode.set("uVertical", glitchVertical ? 1 : 0);
                mode.set("uStretch", slitStretch);
                break;
            case 4:
                mode.set("uLevels", crushLevels);
                break;
            default:
                mode.set("uVertical", glitchVertical ? 1 : 0);
                mode.set("uLo", glitchLo);
                mode.set("uHi", glitchHi);
                mode.set("uDescending", glitchDescending ? 1 : 0);
                // the property map, for edge-bounded runs and the auto axis
                glActiveTexture(GL_TEXTURE0 + 1); glBindTexture(GL_TEXTURE_2D, props);
                glActiveTexture(GL_TEXTURE0);
                mode.set("uProps", 1);
                mode.set("uEdgeBound", glitchEdgeBound);
                mode.set("uAutoDir", glitchAutoDir ? 1 : 0);
                break;
        }
        // the sort needs one workgroup per line of the disc for its shared
        // memory; the per-pixel modes just cover the bounding box
        if (glitchMode == 0) glDispatchCompute(2 * r + 1, 1, 1);
        else glDispatchCompute((2 * r + 8) / 8, (2 * r + 8) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        pCopyRect.use();
        glBindTexture(GL_TEXTURE_2D, backgroundB);
        pCopyRect.set("uSrc", 0);
        glBindImageTexture(0, background, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        pCopyRect.set2i("uOrigin", cx - r, cy - r);
        pCopyRect.set2i("uSize", 2 * r + 1, 2 * r + 1);
        pCopyRect.set("uRadius", r);   // the disc only; the corners are not ours
        glDispatchCompute((2 * r + 8) / 8, (2 * r + 8) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
        canvasDirty = true;
    }

    // One scatter dab: boxes into the back buffer, then the disc forward --
    // the same two passes the glitch brush uses, and for the same reason.
    void scatterDab(float u, float v) {
        int cx = (int)(u * canvasW), cy = (int)(v * canvasH);
        int r = std::min(127, std::max(2, (int)(flip.brushRadius * canvasH)));
        // A box centred on the rim still lands whole, so the pass has to reach
        // out by the largest a box can grow: full jitter, full stretch.
        int margin = (int)std::ceil(scatterSize * (1.0f + scatterJitter) *
                                    (1.0f + scatterStretch)) + 2;
        int reach = std::min(511, r + margin);

        pScatter.use();
        glActiveTexture(GL_TEXTURE0 + 0); glBindTexture(GL_TEXTURE_2D, background);
        glActiveTexture(GL_TEXTURE0 + 1); glBindTexture(GL_TEXTURE_2D, props);
        glActiveTexture(GL_TEXTURE0 + 2); glBindTexture(GL_TEXTURE_2D, referenceTex());
        glActiveTexture(GL_TEXTURE0);
        pScatter.set("uSrc", 0);
        pScatter.set("uProps", 1);
        pScatter.set("uRef", 2);
        glBindImageTexture(0, backgroundB, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        pScatter.set2i("uCentre", cx, cy);
        pScatter.set("uRadius", r);
        pScatter.set("uReach", reach);
        pScatter.set("uFalloff", glitchFalloff);
        pScatter.set2i("uSize", canvasW, canvasH);
        pScatter.set("uCount", scatterCount);
        pScatter.set("uBoxSize", scatterSize);
        pScatter.set("uStretch", scatterStretch);
        pScatter.set("uAlign", scatterAlign);
        pScatter.set("uOpacity", scatterOpacity);
        pScatter.set("uColourFrom", scatterColour);
        pScatter.set("uJitter", scatterJitter);
        pScatter.set("uSeed", scatterSeed);
        scatterSeed += 3.7f;
        glDispatchCompute((2 * reach + 8) / 8, (2 * reach + 8) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        // The whole square comes forward, not a disc: the pass seeded every
        // texel from the layer, so the ones no box reached copy back unchanged
        // and a box hanging over the rim survives intact.
        pCopyRect.use();
        glBindTexture(GL_TEXTURE_2D, backgroundB);
        pCopyRect.set("uSrc", 0);
        glBindImageTexture(0, background, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        pCopyRect.set2i("uOrigin", cx - reach, cy - reach);
        pCopyRect.set2i("uSize", 2 * reach + 1, 2 * reach + 1);
        pCopyRect.set("uRadius", 0);
        glDispatchCompute((2 * reach + 8) / 8, (2 * reach + 8) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
        canvasDirty = true;
    }

    void scatterStroke() {
        float cx = 0, cy = 0;
        if (!painting || canvasW == 0 || !canvasUV(cx, cy)) { haveScatterLast = false; return; }
        if (!haveScatterLast) {
            scatterDab(cx, cy);
            haveScatterLast = true;
            scatterLastX = cx; scatterLastY = cy; scatterCarry = 0;
            return;
        }
        float dx = (cx - scatterLastX) * flip.aspect, dy = cy - scatterLastY;
        float dist = std::sqrt(dx * dx + dy * dy);
        float spacing = std::max(flip.brushRadius * 0.5f, 0.002f);
        float next = spacing - scatterCarry;
        int stamps = 0;
        while (next <= dist && stamps < 64) {
            float t = next / dist;
            scatterDab(scatterLastX + (cx - scatterLastX) * t,
                       scatterLastY + (cy - scatterLastY) * t);
            next += spacing;
            stamps++;
        }
        scatterCarry = stamps < 64 ? dist - (next - spacing) : 0;
        scatterLastX = cx; scatterLastY = cy;
    }

    // dabs strung along the mouse path half a radius apart, as on Android
    void glitchStroke() {
        float cx = 0, cy = 0;
        if (!painting || canvasW == 0 || !canvasUV(cx, cy)) { haveGlitchLast = false; return; }
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
        float canvasAspect = (float)canvasW / (float)canvasH;
        float srcAspect = (float)w / (float)h;
        int cropW = w, cropH = h;
        if (srcAspect > canvasAspect) cropW = std::max(1, (int)(h * canvasAspect));
        else cropH = std::max(1, (int)(w / canvasAspect));
        int ox = (w - cropW) / 2, oy = (h - cropH) / 2;

        std::vector<float> buf((size_t)canvasW * canvasH * 4);
        for (int y = 0; y < canvasH; y++) {
            // GL row 0 is the bottom; image row 0 is the top
            int sy = oy + (int)((float)(canvasH - 1 - y) * cropH / canvasH);
            for (int x = 0; x < canvasW; x++) {
                int sx = ox + (int)((float)x * cropW / canvasW);
                const unsigned char *c = px + ((size_t)sy * w + sx) * 4;
                float a = c[3] / 255.0f;
                float *o = &buf[((size_t)y * canvasW + x) * 4];
                o[0] = c[0] / 255.0f * a;
                o[1] = c[1] / 255.0f * a;
                o[2] = c[2] / 255.0f * a;
                o[3] = a;
            }
        }
        stbi_image_free(px);
        glBindTexture(GL_TEXTURE_2D, background);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, canvasW, canvasH, GL_RGBA, GL_FLOAT, buf.data());
        glBindTexture(GL_TEXTURE_2D, 0);
        std::printf("loaded %s (%dx%d)\n", path, w, h);
        canvasDirty = true;
        return true;
    }

    void clearCanvas() {
        flip.clearPool();
        rdClear();
        for (GLuint fbo : {fboBackground, fboLive}) {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        canvasDirty = true;
    }

    void applyFlipPreset(int i) {
        const FlipPreset &p = FLIP_PRESETS[i];
        flip.flowRate = p.flow; flip.flipRatio = p.inherit; flip.particleDrag = p.drag;
        flip.settleTime = p.settle; flip.cohesion = p.cohesion;
        flip.pointSize = p.pointSize; flip.particlesPerCell = p.density;
        flip.compensate = 1.0f;
    }

    void applyGlitchPreset(int i) {
        const GlitchPreset &p = GLITCH_PRESETS[i];
        glitchLo = p.lo; glitchHi = p.hi; glitchVertical = p.vertical;
    }

    // Writes the canvas as a PNG next to the exe, at the canvas's own
    // resolution: it is composited offscreen rather than read back off the
    // window, so what gets saved is the painting, not the view of it.
    void savePng() {
        if (canvasW <= 0 || canvasH <= 0) return;
        GLuint tex = makeTexture(canvasW, canvasH, GL_RGBA8, GL_NEAREST);
        GLuint fbo = 0;
        glGenFramebuffers(1, &fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        glViewport(0, 0, canvasW, canvasH);
        compositeDraw();

        std::vector<unsigned char> px((size_t)canvasW * canvasH * 4);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, canvasW, canvasH, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(1, &fbo);
        glDeleteTextures(1, &tex);
        // GL rows are bottom-up
        std::vector<unsigned char> flipped((size_t)canvasW * canvasH * 4);
        for (int y = 0; y < canvasH; y++)
            std::memcpy(&flipped[(size_t)y * canvasW * 4],
                        &px[(size_t)(canvasH - 1 - y) * canvasW * 4], (size_t)canvasW * 4);
        char name[64];
        std::time_t now = std::time(nullptr);
        std::strftime(name, sizeof name, "maxpaint-%Y%m%d-%H%M%S.png", std::localtime(&now));
        if (stbi_write_png(name, canvasW, canvasH, 4, flipped.data(), canvasW * 4))
            std::snprintf(status, sizeof status, "saved %s", name);
        else
            std::snprintf(status, sizeof status, "could not write %s", name);
    }

    // ------------------------------------------------------------ the painting
    //
    // A PNG is a picture of the painting; this is the painting. The set layer
    // goes down at its own precision -- half floats, exactly what the texture
    // holds, so a reload is bit-for-bit and not a re-quantised copy -- along
    // with the reaction field if one is alive and the settings that were in
    // play. Wet particles are not kept: they are a stroke in progress, and a
    // file is something you come back to.

    static const uint32_t FILE_MAGIC = 0x504D584Du;   // 'MXPM'
    static const uint32_t FILE_VERSION = 3;   // 2 added the stack, 3 the reference

    template <class T> static void put(std::ofstream &f, const T &v) {
        f.write(reinterpret_cast<const char *>(&v), sizeof v);
    }
    template <class T> static void get(std::ifstream &f, T &v) {
        f.read(reinterpret_cast<char *>(&v), sizeof v);
    }

    void writeSettings(std::ofstream &f) const {
        put(f, tool); put(f, view); put(f, glitchMode);
        put(f, flip.brushRadius); put(f, flip.flowRate); put(f, flip.compensate);
        put(f, flip.particleDrag); put(f, flip.particlesPerCell);
        put(f, flip.separationIters); put(f, flip.solveIters);
        put(f, flip.settleTime); put(f, flip.cohesion); put(f, flip.flipRatio);
        put(f, flip.pointSize); put(f, flip.relief); put(f, flip.shadeDry);
        put(f, flip.heightInk);
        put(f, reliefInvert); put(f, aoRadius); put(f, slope);
        put(f, glitchLo); put(f, glitchHi); put(f, glitchFalloff);
        put(f, glitchVertical); put(f, glitchDescending);
        put(f, glitchEdgeBound); put(f, glitchAutoDir);
        put(f, driftAmount); put(f, blockSize); put(f, blockAmount);
        put(f, slitStretch); put(f, crushLevels);
        put(f, rdFeed); put(f, rdKill); put(f, rdCouple); put(f, rdIters);
        put(f, rdInk); put(f, rdLo); put(f, rdHi); put(f, rdRun);
        put(f, referenceLayer);
        put(f, scatterCount); put(f, scatterSize); put(f, scatterStretch);
        put(f, scatterAlign); put(f, scatterOpacity); put(f, scatterJitter);
        put(f, scatterColour);
    }

    void readSettings(std::ifstream &f) {
        get(f, tool); get(f, view); get(f, glitchMode);
        get(f, flip.brushRadius); get(f, flip.flowRate); get(f, flip.compensate);
        get(f, flip.particleDrag); get(f, flip.particlesPerCell);
        get(f, flip.separationIters); get(f, flip.solveIters);
        get(f, flip.settleTime); get(f, flip.cohesion); get(f, flip.flipRatio);
        get(f, flip.pointSize); get(f, flip.relief); get(f, flip.shadeDry);
        get(f, flip.heightInk);
        get(f, reliefInvert); get(f, aoRadius); get(f, slope);
        get(f, glitchLo); get(f, glitchHi); get(f, glitchFalloff);
        get(f, glitchVertical); get(f, glitchDescending);
        get(f, glitchEdgeBound); get(f, glitchAutoDir);
        get(f, driftAmount); get(f, blockSize); get(f, blockAmount);
        get(f, slitStretch); get(f, crushLevels);
        get(f, rdFeed); get(f, rdKill); get(f, rdCouple); get(f, rdIters);
        get(f, rdInk); get(f, rdLo); get(f, rdHi); get(f, rdRun);
        get(f, referenceLayer);
        get(f, scatterCount); get(f, scatterSize); get(f, scatterStretch);
        get(f, scatterAlign); get(f, scatterOpacity); get(f, scatterJitter);
        get(f, scatterColour);
    }

    std::vector<unsigned short> readLayer(GLuint tex) const {
        std::vector<unsigned short> px((size_t)canvasW * canvasH * 4);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glBindTexture(GL_TEXTURE_2D, tex);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_HALF_FLOAT, px.data());
        glBindTexture(GL_TEXTURE_2D, 0);
        return px;
    }

    void uploadLayer(GLuint tex, const std::vector<unsigned short> &px) const {
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, canvasW, canvasH,
                        GL_RGBA, GL_HALF_FLOAT, px.data());
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    bool savePainting(const char *path) {
        std::ofstream f(path, std::ios::binary);
        if (!f) { std::snprintf(status, sizeof status, "could not write %s", path); return false; }
        uint32_t w = (uint32_t)canvasW, h = (uint32_t)canvasH;
        uint32_t flags = rdSeeded ? 1u : 0u;
        put(f, FILE_MAGIC); put(f, FILE_VERSION);
        put(f, w); put(f, h); put(f, flags);
        uint32_t count = (uint32_t)layers.size(), active = (uint32_t)activeLayer;
        put(f, count); put(f, active);
        writeSettings(f);
        for (int i = 0; i < (int)layers.size(); i++) {
            f.write(layers[i].name, sizeof layers[i].name);
            put(f, layers[i].visible);
            put(f, layers[i].opacity);
            std::vector<unsigned short> px = readLayer(layerTex(i));
            f.write(reinterpret_cast<const char *>(px.data()),
                    (std::streamsize)(px.size() * sizeof(unsigned short)));
        }
        if (rdSeeded) {
            std::vector<unsigned short> rd = readLayer(rdTex[rdCur]);
            f.write(reinterpret_cast<const char *>(rd.data()),
                    (std::streamsize)(rd.size() * sizeof(unsigned short)));
        }
        bool ok = (bool)f;
        std::snprintf(status, sizeof status, ok ? "saved %s" : "could not write %s", path);
        return ok;
    }

    bool loadPainting(const char *path) {
        std::ifstream f(path, std::ios::binary);
        if (!f) { std::snprintf(status, sizeof status, "could not read %s", path); return false; }
        uint32_t magic = 0, version = 0, w = 0, h = 0, flags = 0;
        get(f, magic); get(f, version); get(f, w); get(f, h); get(f, flags);
        if (magic != FILE_MAGIC || version != FILE_VERSION || w == 0 || h == 0) {
            std::snprintf(status, sizeof status, "%s is not a MaxPaint painting", path);
            return false;
        }
        uint32_t count = 1, active = 0;
        get(f, count); get(f, active);
        if (count == 0 || count > 64) {
            std::snprintf(status, sizeof status, "%s has an impossible stack", path);
            return false;
        }
        readSettings(f);

        // rebuild the stack at the file's size, then fill it
        for (int i = 0; i < (int)layers.size(); i++)
            if (i != activeLayer && layers[i].tex) glDeleteTextures(1, &layers[i].tex);
        layers.resize(1);
        layers[0].tex = background;
        activeLayer = 0;
        if ((int)w != canvasW || (int)h != canvasH) allocate((int)w, (int)h);
        while (layers.size() < count) {
            CanvasLayer l;
            l.tex = makeTexture((int)w, (int)h, GL_RGBA16F, GL_LINEAR);
            clearLayer(l.tex);
            layers.push_back(l);
        }

        std::vector<unsigned short> px((size_t)w * h * 4);
        for (int i = 0; i < (int)count; i++) {
            f.read(layers[i].name, sizeof layers[i].name);
            layers[i].name[sizeof layers[i].name - 1] = '\0';
            get(f, layers[i].visible);
            get(f, layers[i].opacity);
            f.read(reinterpret_cast<char *>(px.data()),
                   (std::streamsize)(px.size() * sizeof(unsigned short)));
            if (!f) { std::snprintf(status, sizeof status, "%s ends early", path); return false; }
            uploadLayer(layerTex(i), px);
        }
        switchLayerRaw(std::min((int)active, (int)layers.size() - 1));

        rdClear();
        if (flags & 1u) {
            std::vector<unsigned short> rd((size_t)w * h * 4);
            f.read(reinterpret_cast<char *>(rd.data()),
                   (std::streamsize)(rd.size() * sizeof(unsigned short)));
            if (f) {
                uploadLayer(rdTex[0], rd);
                uploadLayer(rdTex[1], rd);
                rdCur = 0;
                rdSeeded = true;
            }
        }
        flip.clearPool();
        havePourLast = haveGlitchLast = haveRdLast = false;
        canvasInput[0] = (int)w; canvasInput[1] = (int)h;
        historyLen = 0; historyCur = 0; canvasDirty = false;
        pushHistory();
        std::snprintf(status, sizeof status, "opened %s (%u x %u)", path, w, h);
        return true;
    }

#ifdef _WIN32
    // one dialog, two directions
    bool paintingDialog(char *path, size_t n, bool saving) {
        path[0] = '\0';
        OPENFILENAMEA ofn = {};
        ofn.lStructSize = sizeof ofn;
        ofn.lpstrFilter = "MaxPaint painting\0*.maxpaint\0All files\0*.*\0";
        ofn.lpstrDefExt = "maxpaint";
        ofn.lpstrFile = path;
        ofn.nMaxFile = (DWORD)n;
        ofn.Flags = OFN_NOCHANGEDIR | (saving ? OFN_OVERWRITEPROMPT : OFN_FILEMUSTEXIST);
        return saving ? GetSaveFileNameA(&ofn) != 0 : GetOpenFileNameA(&ofn) != 0;
    }
#endif

    // A path from the command line or a drop: a painting reopens, anything
    // else is read as a picture to paint on.
    bool openPath(const char *path) {
        std::string s(path);
        size_t dot = s.find_last_of('.');
        std::string ext = dot == std::string::npos ? "" : s.substr(dot);
        for (char &c : ext) c = (char)std::tolower((unsigned char)c);
        if (ext == ".maxpaint") return loadPainting(path);
        return importImage(path);
    }

    void openDialog() {
#ifdef _WIN32
        char path[MAX_PATH] = "";
        OPENFILENAMEA ofn = {};
        ofn.lStructSize = sizeof ofn;
        ofn.lpstrFilter = "Images\0*.png;*.jpg;*.jpeg;*.bmp;*.tga;*.gif;*.psd\0All files\0*.*\0";
        ofn.lpstrFile = path;
        ofn.nMaxFile = sizeof path;
        ofn.Flags = OFN_FILEMUSTEXIST | OFN_NOCHANGEDIR;
        if (GetOpenFileNameA(&ofn)) {
            if (importImage(path)) std::snprintf(status, sizeof status, "loaded %s", path);
            else std::snprintf(status, sizeof status, "could not read %s", path);
        }
#else
        std::snprintf(status, sizeof status, "drop an image onto the window");
#endif
    }

    // The stack, top of the list being the top of the picture, which is the
    // way every other paint program shows it and the reverse of the order it
    // is composited in.
    void layerPanel() {
        if (!ImGui::CollapsingHeader("Layers")) return;
        if (ImGui::Button("Add")) addLayer();
        ImGui::SameLine();
        ImGui::BeginDisabled(layers.size() <= 1);
        if (ImGui::Button("Delete")) deleteLayer();
        ImGui::EndDisabled();
        ImGui::SameLine();
        ImGui::BeginDisabled(activeLayer + 1 >= (int)layers.size());
        if (ImGui::Button("Raise")) moveLayer(1);
        ImGui::EndDisabled();
        ImGui::SameLine();
        ImGui::BeginDisabled(activeLayer <= 0);
        if (ImGui::Button("Lower")) moveLayer(-1);
        ImGui::EndDisabled();

        for (int i = (int)layers.size() - 1; i >= 0; i--) {
            ImGui::PushID(i);
            bool vis = layers[i].visible;
            if (ImGui::Checkbox("##vis", &vis)) layers[i].visible = vis;
            ImGui::SameLine();
            if (ImGui::RadioButton(layers[i].name, activeLayer == i)) setActiveLayer(i);
            ImGui::SameLine();
            ImGui::SetNextItemWidth(90);
            ImGui::SliderFloat("##op", &layers[i].opacity, 0, 1, "%.2f");
            ImGui::PopID();
        }
        ImGui::Separator();
        const char *refName = referenceLayer >= 0 && referenceLayer < (int)layers.size()
                            ? layers[referenceLayer].name : "The whole stack";
        int wasRef = referenceLayer;
        if (ImGui::BeginCombo("Reference", refName)) {
            if (ImGui::Selectable("The whole stack", referenceLayer < 0))
                referenceLayer = -1;
            for (int i = (int)layers.size() - 1; i >= 0; i--) {
                ImGui::PushID(i);
                if (ImGui::Selectable(layers[i].name, referenceLayer == i))
                    referenceLayer = i;
                ImGui::PopID();
            }
            ImGui::EndCombo();
        }
        // the maps describe a different picture now; rebuild them next frame
        if (referenceLayer != wasRef) propsFrame = 0;

        ImGui::TextWrapped("Brushes paint into the selected layer; the tick hides "
                           "one without discarding it. Undo steps remember the "
                           "layer they were taken on and go back there.\n\n"
                           "Reference is the layer the brushes LOOK at, as against "
                           "the one they paint into: where Scatter takes its "
                           "colours and what the height, normal and occlusion maps "
                           "are derived from. Point it at a photograph and you can "
                           "scatter that photograph onto an empty layer above it, "
                           "or let its relief steer paint poured somewhere else. "
                           "The whole stack is the default and is what they always "
                           "used.");
    }

    // Canvas size and window size, which are no longer the same question.
    void canvasPanel() {
        if (!ImGui::CollapsingHeader("Canvas & window")) return;

        ImGui::Text("Canvas %d x %d", canvasW, canvasH);
        struct Size { const char *name; int w, h; };
        static const Size SIZES[] = {
            {"1280 x 720", 1280, 720},   {"1920 x 1080", 1920, 1080},
            {"2560 x 1440", 2560, 1440}, {"3840 x 2160", 3840, 2160},
            {"1024 x 1024", 1024, 1024}, {"2048 x 2048", 2048, 2048},
            {"1080 x 1350", 1080, 1350},
        };
        if (ImGui::BeginCombo("Preset", "Choose a size")) {
            for (const Size &s : SIZES)
                if (ImGui::Selectable(s.name)) {
                    canvasInput[0] = s.w; canvasInput[1] = s.h;
                    pendingCanvasW = s.w; pendingCanvasH = s.h;
                }
            ImGui::EndCombo();
        }
        ImGui::DragInt2("Size", canvasInput, 8.0f, 64, maxCanvas);
        if (ImGui::Button("Resize canvas")) {
            pendingCanvasW = canvasInput[0];
            pendingCanvasH = canvasInput[1];
        }
        ImGui::SameLine();
        if (ImGui::Button("Match window")) {
            pendingCanvasW = winW; pendingCanvasH = winH;
        }
        ImGui::TextWrapped("Resizing rescales the set paint and empties the wet "
                           "pool -- particles hold canvas coordinates and cannot "
                           "follow. Up to %d px a side and %d megapixels; past "
                           "that it is scaled back to fit.",
                           maxCanvas, maxCanvasPixels >> 20);

        ImGui::Separator();
        ImGui::Text("Window %d x %d", winW, winH);
        // the field follows the window until someone takes hold of it
        if (!windowInputActive && window)
            glfwGetWindowSize(window, &windowInput[0], &windowInput[1]);
        ImGui::DragInt2("Window size", windowInput, 8.0f, 320, 16384);
        windowInputActive = ImGui::IsItemActive();
        if (ImGui::Button("Resize window") && window)
            glfwSetWindowSize(window, windowInput[0], windowInput[1]);
        ImGui::SameLine();
        if (ImGui::Button("Fit to canvas") && window && pixelScaleX > 0) {
            glfwSetWindowSize(window, (int)(canvasW / pixelScaleX),
                              (int)(canvasH / pixelScaleY));
        }
        ImGui::TextWrapped("The window is free to be any shape; the canvas keeps "
                           "its own and sits centred inside it.");
    }

    void panel() {
        ImGui::SetNextWindowPos(ImVec2(10, 10), ImGuiCond_FirstUseEver);
        ImGui::SetNextWindowSize(ImVec2(300, 0), ImGuiCond_FirstUseEver);
        ImGui::Begin("MaxPaint", nullptr, ImGuiWindowFlags_AlwaysAutoResize);

        if (ImGui::Button("Open...")) openDialog();
        ImGui::SameLine();
        if (ImGui::Button("Save PNG")) savePending = true;
        ImGui::SameLine();
        if (ImGui::Button("Clear")) clearCanvas();

#ifdef _WIN32
        char path[MAX_PATH];
        if (ImGui::Button("Open painting")) {
            if (paintingDialog(path, sizeof path, false)) loadPainting(path);
        }
        ImGui::SameLine();
        if (ImGui::Button("Save painting")) {
            if (paintingDialog(path, sizeof path, true)) savePainting(path);
        }
#endif

        ImGui::BeginDisabled(!canUndo());
        if (ImGui::Button("Undo")) undo();
        ImGui::EndDisabled();
        ImGui::SameLine();
        ImGui::BeginDisabled(!canRedo());
        if (ImGui::Button("Redo")) redo();
        ImGui::EndDisabled();
        ImGui::SameLine();
        ImGui::TextDisabled("%d/%d  (ctrl+Z, ctrl+Y)",
                            historyLen ? historyCur + 1 : 0, historyLen);

        if (status[0]) ImGui::TextWrapped("%s", status);
        ImGui::Separator();
        canvasPanel();
        layerPanel();
        ImGui::Separator();

        ImGui::RadioButton("Fluid", &tool, 0); ImGui::SameLine();
        ImGui::RadioButton("Glitch", &tool, 1); ImGui::SameLine();
        ImGui::RadioButton("Reaction", &tool, 2); ImGui::SameLine();
        ImGui::RadioButton("Gas", &tool, 3); ImGui::SameLine();
        ImGui::RadioButton("Nib", &tool, 4); ImGui::SameLine();
        ImGui::RadioButton("Scatter", &tool, 5);
        const char *views[] = {"Paint", "Height", "Normals", "Occlusion", "Reaction"};
        ImGui::Combo("View", &view, views, 5);
        ImGui::Separator();

        // The gas and the nib carry their own radius; showing this one as well
        // put two widgets called "Brush size" in the same window, and ImGui
        // keys a widget on its label -- they would have shared an identity.
        if (tool != 3 && tool != 4)
            ImGui::SliderFloat("Brush size", &flip.brushRadius, 0.004f, 0.17f, "%.3f");

        if (tool == 0) {
            if (ImGui::BeginCombo("Preset", FLIP_PRESETS[flipPreset].name)) {
                for (int i = 0; i < (int)(sizeof FLIP_PRESETS / sizeof *FLIP_PRESETS); i++) {
                    if (ImGui::Selectable(FLIP_PRESETS[i].name, i == flipPreset)) {
                        flipPreset = i;
                        applyFlipPreset(i);
                    }
                }
                ImGui::EndCombo();
            }
            ImGui::SliderFloat("Flow", &flip.flowRate, 0, 40, "%.0f dabs/s");
            ImGui::SliderFloat("Volume", &flip.compensate, 0, 4, "%.2f");
            float dragPct = (float)((1.0 - std::exp(-flip.particleDrag)) * 100.0);
            if (ImGui::SliderFloat("Drag", &dragPct, 0, 99, "%.0f%%/s"))
                flip.particleDrag = (float)-std::log(1.0 - std::min(dragPct, 99.0f) / 100.0);
            ImGui::SliderFloat("Density", &flip.particlesPerCell, 1, 400, "%.0f per cell");
            ImGui::SliderInt("Separation", &flip.separationIters, 0, 4);
            ImGui::SliderInt("Pressure", &flip.solveIters, 1, 200, "%d sweeps");
            ImGui::SliderFloat("Settle", &flip.settleTime, 0, 10, "%.1f s wet");
            ImGui::SliderFloat("Cohesion", &flip.cohesion, 0, 200, "%.0f");
            float inh = flip.flipRatio * 100;
            if (ImGui::SliderFloat("Motion inheritance", &inh, 0, 100, "%.0f%%"))
                flip.flipRatio = inh / 100;
            ImGui::SliderFloat("Drop size", &flip.pointSize, 0.5f, 48, "%.1f px");
            ImGui::Separator();
            ImGui::Text("Image relief");
            ImGui::SliderFloat("Relief", &flip.relief, 0, 20, "%.1f");
            ImGui::Checkbox("Dark is high", &reliefInvert);
            ImGui::SliderFloat("Steepness", &slope, 5, 200, "%.0f");
            ImGui::SliderFloat("AO radius", &aoRadius, 4, 96, "%.0f px");
            ImGui::SliderFloat("Dry in shade", &flip.shadeDry, 0, 1, "%.2f");
            ImGui::SliderFloat("Ink from height", &flip.heightInk, 0, 1, "%.2f");
            ImGui::TextWrapped("Relief makes the picture the gravity field: paint "
                               "runs downhill on it. Dry in shade shortens the "
                               "settle time where the occlusion map says the "
                               "picture is buried, so paint sets in the creases "
                               "while the open planes are still wet. Ink from "
                               "height charges each drop by the brightness it was "
                               "laid on. Set View to Height, Normals or Occlusion "
                               "to see what the brushes see. There is no gravity "
                               "otherwise - a canvas has no up.");
        } else if (tool == 1) {
            const char *modes[] = {"Pixel sort", "Channel drift", "Block shuffle",
                                   "Slit-scan", "Bit crush"};
            ImGui::Combo("Mode", &glitchMode, modes, 5);

            const char *dirs[] = {"Horizontal", "Vertical"};
            int dir = glitchVertical ? 1 : 0;

            if (glitchMode == 0) {
                if (ImGui::BeginCombo("Preset", GLITCH_PRESETS[glitchPreset].name)) {
                    for (int i = 0; i < (int)(sizeof GLITCH_PRESETS / sizeof *GLITCH_PRESETS); i++) {
                        if (ImGui::Selectable(GLITCH_PRESETS[i].name, i == glitchPreset)) {
                            glitchPreset = i;
                            applyGlitchPreset(i);
                        }
                    }
                    ImGui::EndCombo();
                }
                if (ImGui::Combo("Direction", &dir, dirs, 2)) glitchVertical = dir == 1;
                int ord = glitchDescending ? 1 : 0;
                const char *ords[] = {"Dark to light", "Light to dark"};
                if (ImGui::Combo("Order", &ord, ords, 2)) glitchDescending = ord == 1;
                ImGui::SliderFloat("Low", &glitchLo, 0, 1, "%.2f");
                ImGui::SliderFloat("High", &glitchHi, 0, 1, "%.2f");
                if (glitchHi < glitchLo) glitchHi = glitchLo;
                ImGui::SliderFloat("Edge bound", &glitchEdgeBound, 0, 1, "%.2f");
                ImGui::Checkbox("Axis from surface", &glitchAutoDir);
            } else if (glitchMode == 1) {
                if (ImGui::Combo("Direction", &dir, dirs, 2)) glitchVertical = dir == 1;
                ImGui::SliderFloat("Separation", &driftAmount, 0, 40, "%.0f px");
            } else if (glitchMode == 2) {
                ImGui::SliderInt("Block", &blockSize, 2, 64, "%d px");
                ImGui::SliderFloat("Displacement", &blockAmount, 0, 64, "%.0f px");
            } else if (glitchMode == 3) {
                if (ImGui::Combo("Direction", &dir, dirs, 2)) glitchVertical = dir == 1;
                ImGui::SliderFloat("Stretch", &slitStretch, 0, 1, "%.2f");
            } else {
                ImGui::SliderInt("Levels", &crushLevels, 2, 32, "%d per channel");
            }

            ImGui::SliderFloat("Edge falloff", &glitchFalloff, 0, 1, "%.2f");

            const char *blurb =
                glitchMode == 0 ? "Sorts the pixels under the brush by brightness "
                    "along each row or column. Runs inside the band get sorted; "
                    "everything outside it holds its place. Edge bound makes the "
                    "picture's own contours stop a run, so silhouettes survive "
                    "while the smooth interiors they enclose liquefy; Axis from "
                    "surface lets the layer's slope choose the direction, so the "
                    "sort follows the form rather than the screen."
              : glitchMode == 1 ? "Pulls red and blue apart along the stroke axis "
                    "and leaves green where it was: the colour fringing of a "
                    "misregistered scan. It shows on a grey photograph too -- the "
                    "separation is what makes the colour."
              : glitchMode == 2 ? "Reads the picture on a fixed grid and lets each "
                    "block fetch from a displaced one. The grid is anchored to the "
                    "canvas, so overlapping dabs keep breaking on the same seams. "
                    "Set Block to 8 for a JPEG's own lattice."
              : glitchMode == 3 ? "Extrudes the single line under the centre of the "
                    "brush across the disc, the way a slit-scan camera draws time. "
                    "Hold still and it keeps reaching further out."
              : "Quantises each channel to a few levels, with a 4x4 ordered dither "
                "deciding which way a value rounds, so flats break into crosshatch "
                "rather than banding.";
            ImGui::TextWrapped("%s Edge falloff eases the mark out at the rim "
                               "instead of ending it on a circle -- 0 is a hard "
                               "edge. Open a photo and take it apart.", blurb);
        } else if (tool == 5) {
            ImGui::SliderInt("Boxes", &scatterCount, 1, 64, "%d a dab");
            ImGui::SliderFloat("Box size", &scatterSize, 1, 80, "%.0f px");
            ImGui::SliderFloat("Size spread", &scatterJitter, 0, 1, "%.2f");
            ImGui::Separator();
            ImGui::SliderFloat("Align", &scatterAlign, 0, 1, "%.2f");
            ImGui::SliderFloat("Stretch", &scatterStretch, 0, 8, "%.2f");
            ImGui::Separator();
            const char *from[] = {"Under the box", "Elsewhere in the disc",
                                  "Anywhere in the picture"};
            ImGui::Combo("Colour", &scatterColour, from, 3);
            ImGui::SliderFloat("Opacity", &scatterOpacity, 0, 1, "%.2f");
            ImGui::SliderFloat("Edge falloff", &glitchFalloff, 0, 1, "%.2f");
            ImGui::TextWrapped("Boxes strewn through the disc, each turned and "
                               "stretched by the picture it lands on. The property "
                               "map's gradient runs across an edge, so a box lies "
                               "along the perpendicular -- on a cheekbone they "
                               "follow the bone, in flat sky they stay square. "
                               "Stretch draws a box out along its feature and "
                               "squeezes it across, by the same amount, because "
                               "those are the same fact. Align 0 keeps them square "
                               "to the screen instead.\n\nThe brush carries no "
                               "pigment: every box takes a colour already in the "
                               "layer, so a blank canvas stays blank and a "
                               "photograph comes apart into its own palette. Edge "
                               "falloff thins the scatter at the rim rather than "
                               "fading it, so every box keeps its edges.");
        } else if (tool == 4) {
            ImGui::SliderFloat("Nib size", &nib.radius, 0.001f, 0.05f, "%.4f");
            ImGui::SliderFloat("Load", &nib.load, 0.1f, 3, "%.2f");
            ImGui::SliderFloat("Ink", &nib.ink, 0, 4, "%.2f");
            ImGui::SliderFloat("Hardness", &nib.hardness, 0, 1, "%.2f");
            ImGui::Separator();
            ImGui::SliderFloat("Soak", &nib.soak, 0, 3, "%.2f");
            ImGui::SliderFloat("Dry", &nib.dry, 0, 3, "%.2f");
            ImGui::SliderFloat("Paper grain", &nib.grain, 0, 1, "%.2f");
            ImGui::SliderFloat("Paper scale", &nib.paperScale, 0.05f, 2, "%.2f");
            ImGui::SliderFloat("Thin limit", &nib.threshold, 0, 0.2f, "%.3f");
            ImGui::TextWrapped("Pen and charcoal. The mark goes into its own "
                               "field, so the fluid cannot smear it, and a "
                               "capillary pass creeps it into the paper and dries "
                               "it onto the layer. Soak is how far ink travels, "
                               "Paper grain how much the fibre steers it. The "
                               "paper keeps drinking after the pen lifts.");
        } else if (tool == 3) {
            ImGui::SliderFloat("Gas size", &gas.brushRadius, 0.005f, 0.25f, "%.3f");
            ImGui::SliderFloat("Ink", &gas.inkPerStroke, 0, 10, "%.2f");
            ImGui::SliderFloat("Push", &gas.velocityGain, 0, 4, "%.2f");
            ImGui::SliderFloat("Vorticity", &gas.vorticity, 0, 60, "%.0f");
            ImGui::SliderFloat("Dye fade", &gas.dyeDissipation, 0, 1, "%.2f");
            ImGui::SliderFloat("Velocity drag", &gas.velocityDrag, 0, 4, "%.2f");
            ImGui::SliderInt("Pressure", &gas.pressureIters, 1, 80, "%d sweeps");
            ImGui::Separator();
            ImGui::SliderFloat("Settle speed", &gas.settleSpeed, 0, 4, "%.2f");
            ImGui::SliderFloat("Bake rate", &gas.bakeRate, 0, 12, "%.1f");
            ImGui::SliderFloat("Hold", &gas.settleMinAge, 0, 8, "%.1f s");
            if (ImGui::Button("Freeze now")) gasFreeze = true;
            ImGui::SameLine();
            if (ImGui::Button("Thaw")) gasThaw = true;
            ImGui::Separator();
            ImGui::Checkbox("Stir only", &gasForceOnly);
            if (gasForceOnly) {
                const char *modes[] = {"Swirl", "Push", "Pinch", "Comb"};
                ImGui::Combo("Force", &gas.forceMode, modes, 4);
                ImGui::SliderFloat("Strength", &gas.forceStrength, -4, 4, "%.2f");
                if (gas.forceMode == 3)
                    ImGui::SliderFloat("Comb", &gas.combFrequency, 1, 60, "%.0f tines");
            }
            ImGui::TextWrapped("The Eulerian medium: a velocity field you push "
                               "pigment through, incompressible again every frame. "
                               "Paint that slows below Settle speed and has been "
                               "live for Hold seconds bakes onto the layer; Thaw "
                               "lifts it back into the air. Stir only pushes what "
                               "is already there without adding ink. It keeps "
                               "running after you switch brushes -- paint has to "
                               "go on settling.");
        } else {
            if (ImGui::BeginCombo("Pattern", RD_PRESETS[rdPreset].name)) {
                for (int i = 0; i < (int)(sizeof RD_PRESETS / sizeof *RD_PRESETS); i++) {
                    if (ImGui::Selectable(RD_PRESETS[i].name, i == rdPreset)) {
                        rdPreset = i;
                        rdFeed = RD_PRESETS[i].feed;
                        rdKill = RD_PRESETS[i].kill;
                    }
                }
                ImGui::EndCombo();
            }
            ImGui::SliderFloat("Feed", &rdFeed, 0.01f, 0.09f, "%.4f");
            ImGui::SliderFloat("Kill", &rdKill, 0.04f, 0.075f, "%.4f");
            ImGui::SliderFloat("Image steer", &rdCouple, 0, 1, "%.2f");
            ImGui::SliderInt("Speed", &rdIters, 1, 40, "%d steps/frame");
            ImGui::Checkbox("Run", &rdRun);
            ImGui::SameLine();
            if (ImGui::Button("Reset reaction")) rdClear();
            ImGui::Separator();
            ImGui::SliderFloat("Ink", &rdInk, 0, 1, "%.2f");
            ImGui::SliderFloat("Ink from", &rdLo, 0, 0.5f, "%.2f");
            ImGui::SliderFloat("Ink to", &rdHi, 0, 0.5f, "%.2f");
            if (rdHi < rdLo) rdHi = rdLo;
            ImGui::SliderFloat("Edge falloff", &glitchFalloff, 0, 1, "%.2f");
            ImGui::TextWrapped("Gray-Scott reaction-diffusion. The brush does not "
                               "draw the pattern -- it seeds a disturbance, and the "
                               "chemistry grows coral, maze or spots out of it while "
                               "you watch. Feed and kill decide which; the window "
                               "they live in is narrow, so start from Pattern. "
                               "Image steer lets the picture underneath shift those "
                               "rates by its brightness, and the growth finds the "
                               "face on its own. Set View to Reaction to see the "
                               "raw field.");
        }
        ImGui::End();
    }

    void updateProps() {
        pProps.use();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, referenceTex());
        pProps.set("uSrc", 0);
        glBindImageTexture(0, props, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
        pProps.set("uInvert", reliefInvert ? 1.0f : 0.0f);
        pProps.set("uAoRadius", aoRadius);
        pProps.set("uSlope", slope);
        glDispatchCompute((canvasW + 7) / 8, (canvasH + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    // the canvas as it is meant to be seen, into whatever viewport is bound
    void compositeDraw() {
        glUseProgram(compositeProgram);
        glActiveTexture(GL_TEXTURE0 + 0);
        glBindTexture(GL_TEXTURE_2D, flatResult ? flatResult : background);
        glActiveTexture(GL_TEXTURE0 + 1); glBindTexture(GL_TEXTURE_2D, live);
        glActiveTexture(GL_TEXTURE0 + 2); glBindTexture(GL_TEXTURE_2D, props);
        glActiveTexture(GL_TEXTURE0 + 3); glBindTexture(GL_TEXTURE_2D, rdTex[rdCur]);
        glActiveTexture(GL_TEXTURE0 + 4); glBindTexture(GL_TEXTURE_2D, gas.dye.read);
        glActiveTexture(GL_TEXTURE0 + 5); glBindTexture(GL_TEXTURE_2D, nib.field.read);
        glActiveTexture(GL_TEXTURE0);
        // named explicitly rather than trusting the layout qualifier
        glUniform1i(glGetUniformLocation(compositeProgram, "uBackground"), 0);
        glUniform1i(glGetUniformLocation(compositeProgram, "uLive"), 1);
        glUniform1i(glGetUniformLocation(compositeProgram, "uProps"), 2);
        glUniform1i(glGetUniformLocation(compositeProgram, "uRd"), 3);
        glUniform1i(glGetUniformLocation(compositeProgram, "uDye"), 4);
        glUniform1i(glGetUniformLocation(compositeProgram, "uNib"), 5);
        glUniform1i(glGetUniformLocation(compositeProgram, "uView"), view);
        glUniform1f(glGetUniformLocation(compositeProgram, "uRdInk"),
                    rdSeeded ? rdInk : 0.0f);
        glUniform1f(glGetUniformLocation(compositeProgram, "uRdLo"), rdLo);
        glUniform1f(glGetUniformLocation(compositeProgram, "uRdHi"), rdHi);
        glBindVertexArray(emptyVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
    }

    void frame(float dt) {
        if (pendingCanvasW > 0 && pendingCanvasH > 0) {
            resizeCanvas(pendingCanvasW, pendingCanvasH);
            pendingCanvasW = pendingCanvasH = 0;
        }
        bool wasPainting = painting;
        painting = mouseDown && !ImGui::GetIO().WantCaptureMouse;
        if (painting && !wasPainting) commitIfDirty();
        if (tool == 1) glitchStroke();
        else if (tool == 2) rdStroke();
        else if (tool == 3) gasStroke(dt);
        else if (tool == 4) nibStroke();
        else if (tool == 5) scatterStroke();
        else pour(dt);
        if (rdSeeded && rdRun) rdStep();
        if (flip.emitted > 0) flip.step(dt);
        // the gas keeps running once touched: paint must go on settling after
        // the hand moves to another brush
        if (gas.inUse) {
            gas.step(dt, background, backgroundB, gasFreeze, gasThaw);
            gasFreeze = gasThaw = false;
            rebindBackgroundFbo();
            canvasDirty = true;
        }
        // paper goes on drinking after the pen leaves it
        if (nib.active) {
            nib.step(dt, background, backgroundB);
            rebindBackgroundFbo();
            canvasDirty = true;
        }

        // freshly dried particles land in the background, permanently
        flip.draw(2.0f, fboBackground, canvasW, canvasH, false);
        // live particles are redrawn from scratch each frame
        flip.draw(1.0f, fboLive, canvasW, canvasH, true);

        // the stack as one picture, for the screen and for the brushes
        flatResult = flatten();

        // the layer changes as paint bakes; refresh the maps every few frames,
        // always when they are on screen or driving a brush
        bool sortReadsProps = tool == 1 && glitchMode == 0 &&
                              (glitchEdgeBound > 0.0f || glitchAutoDir);
        bool paintReadsProps = flip.relief > 0.0f || flip.shadeDry > 0.0f ||
                               flip.heightInk > 0.0f;
        bool wanted = view != 0 || paintReadsProps || sortReadsProps || tool == 5;
        if (wanted && (propsFrame++ % 4) == 0) updateProps();

        // the window, then the canvas laid on it
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, winW, winH);
        glClearColor(0.11f, 0.11f, 0.12f, 1.0f);   // the desk the canvas lies on
        glClear(GL_COLOR_BUFFER_BIT);
        Rect r = canvasRect();
        glViewport(r.x, r.y, r.w, r.h);
        compositeDraw();

        if (savePending) { savePending = false; savePng(); }
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
    if (count > 0) app.openPath(paths[0]);
}
static void onFramebufferSize(GLFWwindow *, int w, int h) {
    app.winW = w; app.winH = h;   // 0 x 0 while minimised; canvasRect() copes
}
static void onKey(GLFWwindow *, int key, int, int action, int mods) {
    if (action != GLFW_PRESS && action != GLFW_REPEAT) return;
    if (ImGui::GetIO().WantCaptureKeyboard) return;
    if (mods & GLFW_MOD_CONTROL) {
        // ctrl+Z steps back, ctrl+Y or ctrl+shift+Z steps forward
        if (key == GLFW_KEY_Z) { (mods & GLFW_MOD_SHIFT) ? app.redo() : app.undo(); }
        else if (key == GLFW_KEY_Y) app.redo();
        return;
    }
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
        case GLFW_KEY_3: app.tool = 2; break;
        case GLFW_KEY_4: app.tool = 3; break;
        case GLFW_KEY_5: app.tool = 4; break;
        case GLFW_KEY_6: app.tool = 5; break;
        case GLFW_KEY_M: app.glitchMode = (app.glitchMode + 1) % 5; break;
        case GLFW_KEY_LEFT_BRACKET: f.brushRadius = std::max(0.004f, f.brushRadius - 0.004f); break;
        case GLFW_KEY_RIGHT_BRACKET: f.brushRadius = std::min(0.17f, f.brushRadius + 0.004f); break;
        case GLFW_KEY_H: app.glitchLo = std::max(0.0f, app.glitchLo - 0.05f); break;
        case GLFW_KEY_J: app.glitchLo = std::min(app.glitchHi, app.glitchLo + 0.05f); break;
        case GLFW_KEY_K: app.glitchHi = std::max(app.glitchLo, app.glitchHi - 0.05f); break;
        case GLFW_KEY_L: app.glitchHi = std::min(1.0f, app.glitchHi + 0.05f); break;
        case GLFW_KEY_V: app.glitchVertical = !app.glitchVertical; break;
        case GLFW_KEY_B: app.glitchDescending = !app.glitchDescending; break;
        case GLFW_KEY_C: app.clearCanvas(); break;
    }
}

int main(int argc, char **argv) {
    // The shaders ship next to the executable, so find them there rather than
    // in whatever directory the app happened to be launched from. Double-click
    // it and the two coincide; run it by path from anywhere else and they do
    // not, and the app used to die on the first shader it could not open.
#ifdef _WIN32
    {
        char exePath[MAX_PATH];
        DWORD n = GetModuleFileNameA(nullptr, exePath, (DWORD)sizeof exePath);
        if (n > 0 && n < sizeof exePath) {
            std::string p(exePath, n);
            size_t slash = p.find_last_of("\\/");
            if (slash != std::string::npos) shaderDir = p.substr(0, slash) + "/shaders";
        }
    }
#endif
    if (!glfwInit()) { std::fprintf(stderr, "glfwInit failed\n"); return 1; }
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
    // The window is free; the painting keeps its own shape inside it.
    glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

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
    app.window = win;
    app.winW = fw; app.winH = fh;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &app.maxCanvas);
    app.maxCanvas = std::min(app.maxCanvas, 16384);
    app.allocate(fw, fh);
    app.canvasInput[0] = fw; app.canvasInput[1] = fh;
    {
        int ww = 0, wh = 0;
        glfwGetWindowSize(win, &ww, &wh);
        app.windowInput[0] = ww; app.windowInput[1] = wh;
    }

    glfwSetMouseButtonCallback(win, onMouseButton);
    glfwSetCursorPosCallback(win, onCursor);
    glfwSetKeyCallback(win, onKey);
    glfwSetDropCallback(win, onDrop);
    glfwSetFramebufferSizeCallback(win, onFramebufferSize);
    if (argc > 1) app.openPath(argv[1]);

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGui::StyleColorsDark();
    ImGui::GetIO().IniFilename = nullptr;   // no imgui.ini next to the exe
    // Only the title bar drags the panel. Missing a slider by a few pixels
    // otherwise picks the whole panel up and carries it off the window.
    ImGui::GetIO().ConfigWindowsMoveFromTitleBarOnly = true;
    ImGui_ImplGlfw_InitForOpenGL(win, true);   // chains to the callbacks above
    ImGui_ImplOpenGL3_Init("#version 430");

    double last = glfwGetTime();
    double titleAt = 0;
    while (!glfwWindowShouldClose(win)) {
        double now = glfwGetTime();
        float dt = (float)std::min(std::max(now - last, 1.0 / 120.0), 1.0 / 20.0);
        last = now;

        // the cursor arrives in window coordinates; the canvas is measured in
        // framebuffer pixels, and on a scaled display those differ
        int ww = 0, wh = 0;
        glfwGetWindowSize(win, &ww, &wh);
        glfwGetFramebufferSize(win, &app.winW, &app.winH);
        app.pixelScaleX = ww > 0 ? (float)app.winW / (float)ww : 1.0f;
        app.pixelScaleY = wh > 0 ? (float)app.winH / (float)wh : 1.0f;

        ImGui_ImplOpenGL3_NewFrame();
        ImGui_ImplGlfw_NewFrame();
        ImGui::NewFrame();
        app.panel();

        app.frame(dt);

        ImGui::Render();
        ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
        glfwSwapBuffers(win);
        glfwPollEvents();

        if (now - titleAt > 0.5) {
            titleAt = now;
            char title[128];
            static const char *GLITCH_NAMES[] = {"pixel sort", "channel drift",
                                                 "block shuffle", "slit-scan",
                                                 "bit crush"};
            std::snprintf(title, sizeof title, "MaxPaint  |  %s",
                          app.tool == 0 ? "fluid"
                        : app.tool == 2 ? "reaction"
                        : app.tool == 5 ? "scatter"
                        : app.tool == 4 ? "nib"
                        : app.tool == 3 ? "gas"
                                        : GLITCH_NAMES[app.glitchMode]);
            glfwSetWindowTitle(win, title);
        }
    }
    ImGui_ImplOpenGL3_Shutdown();
    ImGui_ImplGlfw_Shutdown();
    ImGui::DestroyContext();
    glfwTerminate();
    return 0;
}
