package com.maxpaint.spike

import android.content.Context
import android.opengl.GLES31
import android.util.Log

private const val TAG = "MaxPaintGL"

/**
 * One framebuffer, reused. Generating and deleting an FBO per frame is driver
 * churn for no reason, and this was happening three times a frame.
 */
object ScratchFbo {
    private var id = 0

    fun bind(tex: Tex) {
        if (id == 0) {
            val ids = IntArray(1)
            GLES31.glGenFramebuffers(1, ids, 0)
            id = ids[0]
        }
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, id)
        GLES31.glFramebufferTexture2D(
            GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0,
            GLES31.GL_TEXTURE_2D, tex.id, 0
        )
    }

    fun unbind() = GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)

    /** The context went away; forget the name so the next bind makes a new one. */
    fun invalidate() { id = 0 }
}

object GLUtil {

    fun readAsset(ctx: Context, path: String): String =
        ctx.assets.open(path).bufferedReader().use { it.readText() }

    fun compile(type: Int, src: String, label: String): Int {
        val id = GLES31.glCreateShader(type)
        GLES31.glShaderSource(id, src)
        GLES31.glCompileShader(id)
        val status = IntArray(1)
        GLES31.glGetShaderiv(id, GLES31.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES31.glGetShaderInfoLog(id)
            GLES31.glDeleteShader(id)
            throw RuntimeException("Shader compile failed ($label):\n$log")
        }
        return id
    }

    fun link(vararg shaders: Int): Int {
        val prog = GLES31.glCreateProgram()
        shaders.forEach { GLES31.glAttachShader(prog, it) }
        GLES31.glLinkProgram(prog)
        val status = IntArray(1)
        GLES31.glGetProgramiv(prog, GLES31.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES31.glGetProgramInfoLog(prog)
            GLES31.glDeleteProgram(prog)
            throw RuntimeException("Program link failed:\n$log")
        }
        shaders.forEach { GLES31.glDeleteShader(it) }
        return prog
    }

    fun checkError(where: String) {
        var e = GLES31.glGetError()
        while (e != GLES31.GL_NO_ERROR) {
            Log.e(TAG, "GL error 0x${e.toString(16)} at $where")
            e = GLES31.glGetError()
        }
    }
}

/** A compute program with cached uniform locations. */
class ComputeProgram(ctx: Context, assetPath: String) {
    val id: Int = GLUtil.link(
        GLUtil.compile(GLES31.GL_COMPUTE_SHADER, GLUtil.readAsset(ctx, assetPath), assetPath)
    )
    private val locations = HashMap<String, Int>()

    private fun loc(name: String): Int =
        locations.getOrPut(name) { GLES31.glGetUniformLocation(id, name) }

    fun use() = GLES31.glUseProgram(id)
    fun set(name: String, v: Float) = GLES31.glUniform1f(loc(name), v)
    fun set(name: String, v: Int) = GLES31.glUniform1i(loc(name), v)
    fun set(name: String, x: Float, y: Float) = GLES31.glUniform2f(loc(name), x, y)
    fun set(name: String, x: Float, y: Float, z: Float, w: Float) =
        GLES31.glUniform4f(loc(name), x, y, z, w)

    /** Dispatch enough 8x8 workgroups to cover w x h, then barrier. */
    fun dispatch(w: Int, h: Int) {
        GLES31.glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
    }

    fun release() = GLES31.glDeleteProgram(id)
}

/** A GPU texture usable both as a sampler source and an image store target. */
class Tex(val width: Int, val height: Int, val internalFormat: Int, filter: Int) {
    val id: Int

    init {
        val ids = IntArray(1)
        GLES31.glGenTextures(1, ids, 0)
        id = ids[0]
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, id)
        GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, internalFormat, width, height)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, filter)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, filter)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, 0)
        clear()
    }

    fun clear() {
        ScratchFbo.bind(this)
        GLES31.glClearColor(0f, 0f, 0f, 0f)
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)
        ScratchFbo.unbind()
    }

    fun bindSampler(unit: Int) {
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + unit)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, id)
    }

    fun bindImage(unit: Int, access: Int) {
        GLES31.glBindImageTexture(unit, id, 0, false, 0, access, internalFormat)
    }

    /** Approximate VRAM footprint in bytes. */
    fun bytes(): Long {
        val bpp = when (internalFormat) {
            GLES31.GL_RGBA32F -> 16
            GLES31.GL_RGBA16F -> 8
            GLES31.GL_R32F -> 4
            else -> 4
        }
        return width.toLong() * height.toLong() * bpp
    }

    fun release() = GLES31.glDeleteTextures(1, intArrayOf(id), 0)
}

/** Ping-pong pair. */
class DoubleTex(w: Int, h: Int, format: Int, filter: Int) {
    var read = Tex(w, h, format, filter); private set
    var write = Tex(w, h, format, filter); private set

    fun swap() {
        val t = read; read = write; write = t
    }

    fun clear() { read.clear(); write.clear() }
    fun bytes() = read.bytes() + write.bytes()
    fun release() { read.release(); write.release() }
}
