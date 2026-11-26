/*
 * Internals of glfx.js (http://evanw.github.com/glfx.js/, Copyright 2011 Evan Wallace, MIT)
 * translated to OpenGL ES 2.0. Names follow the de-minified glfx.js source:
 *   h -> Shader, r -> Texture, z -> Spline, `a` (the current WebGL context) -> Gl.
 */
package dev.kidpix.glfx

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Per-context state that glfx.js hangs off the WebGL context object (`a.defaultShader`, `a.framebuffer`, ...). */
internal class Gl(val extensions: Set<String>, val es3: Boolean) {
    val shaders = HashMap<String, Shader>()
    var defaultShader: Shader? = null
    var vertexBuffer = 0
    var texCoordBuffer = 0
    var framebuffer = 0
    /** Reused for drawRect's vertex upload (glfx allocates a Float32Array per call). */
    val rectBuffer: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun getExtension(name: String): Boolean = name in extensions

    inline fun shader(name: String, make: () -> Shader): Shader = shaders.getOrPut(name, make)

    /**
     * WebGL 1 takes unsized `RGBA` + `FLOAT` with OES_texture_float (Chrome backs it with RGBA32F).
     * GLES 3 contexts need the sized format for a float texture to be renderable.
     */
    fun internalFormat(format: Int, type: Int): Int =
        if (es3 && type == GLES20.GL_FLOAT && format == GLES20.GL_RGBA) GL_RGBA32F else format

    companion object {
        const val GL_RGBA32F = 0x8814
    }
}

internal class GlfxException(msg: String) : RuntimeException(msg)

internal class Shader(private val gl: Gl, vertexSource: String?, fragmentSource: String?) {
    var vertexAttribute: Int? = null
    var texCoordAttribute: Int? = null
    var program: Int = GLES20.glCreateProgram()

    init {
        val vs = vertexSource ?: DEFAULT_VERTEX
        val fs = "precision highp float;" + (fragmentSource ?: DEFAULT_FRAGMENT)
        GLES20.glAttachShader(program, compileSource(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(program, compileSource(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw GlfxException("link error: " + GLES20.glGetProgramInfoLog(program))
    }

    private fun compileSource(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw GlfxException("compile error: " + GLES20.glGetShaderInfoLog(shader))
        return shader
    }

    fun destroy() {
        GLES20.glDeleteProgram(program)
        program = 0
    }

    /** Values: Number -> uniform1f; FloatArray/DoubleArray of length 1,2,3,4,9,16 -> vectors/matrices. */
    fun uniforms(u: Map<String, Any>): Shader {
        GLES20.glUseProgram(program)
        for ((name, value) in u) {
            val location = GLES20.glGetUniformLocation(program, name)
            if (location == -1) continue
            when (value) {
                is Number -> GLES20.glUniform1f(location, value.toFloat())
                is Boolean -> GLES20.glUniform1f(location, if (value) 1f else 0f)
                is FloatArray, is DoubleArray -> {
                    val v = if (value is DoubleArray) FloatArray(value.size) { value[it].toFloat() } else value as FloatArray
                    when (v.size) {
                        1 -> GLES20.glUniform1fv(location, 1, v, 0)
                        2 -> GLES20.glUniform2fv(location, 1, v, 0)
                        3 -> GLES20.glUniform3fv(location, 1, v, 0)
                        4 -> GLES20.glUniform4fv(location, 1, v, 0)
                        9 -> GLES20.glUniformMatrix3fv(location, 1, false, v, 0)
                        16 -> GLES20.glUniformMatrix4fv(location, 1, false, v, 0)
                        else -> throw GlfxException("dont't know how to load uniform \"$name\" of length ${v.size}")
                    }
                }
                else -> throw GlfxException("attempted to set uniform \"$name\" to invalid value $value")
            }
        }
        return this
    }

    fun textures(t: Map<String, Int>): Shader {
        GLES20.glUseProgram(program)
        for ((name, unit) in t) GLES20.glUniform1i(GLES20.glGetUniformLocation(program, name), unit)
        return this
    }

    fun drawRect(left: Float? = null, top: Float? = null, right: Float? = null, bottom: Float? = null) {
        val f = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, f, 0)
        val c = if (top != null) (top - f[1]) / f[3] else 0f
        val b = if (left != null) (left - f[0]) / f[2] else 0f
        val e = if (right != null) (right - f[0]) / f[2] else 1f
        val d = if (bottom != null) (bottom - f[1]) / f[3] else 1f
        if (gl.vertexBuffer == 0) gl.vertexBuffer = genBuffer()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gl.vertexBuffer)
        val verts = gl.rectBuffer
        verts.clear()
        verts.put(b).put(c).put(b).put(d).put(e).put(c).put(e).put(d)
        verts.position(0)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 8 * 4, verts, GLES20.GL_STATIC_DRAW)
        if (gl.texCoordBuffer == 0) {
            gl.texCoordBuffer = genBuffer()
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gl.texCoordBuffer)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 8 * 4, floats(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f), GLES20.GL_STATIC_DRAW)
        }
        if (vertexAttribute == null) {
            vertexAttribute = GLES20.glGetAttribLocation(program, "vertex").also { if (it >= 0) GLES20.glEnableVertexAttribArray(it) }
        }
        if (texCoordAttribute == null) {
            texCoordAttribute = GLES20.glGetAttribLocation(program, "_texCoord").also { if (it >= 0) GLES20.glEnableVertexAttribArray(it) }
        }
        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gl.vertexBuffer)
        vertexAttribute!!.let { if (it >= 0) GLES20.glVertexAttribPointer(it, 2, GLES20.GL_FLOAT, false, 0, 0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gl.texCoordBuffer)
        texCoordAttribute!!.let { if (it >= 0) GLES20.glVertexAttribPointer(it, 2, GLES20.GL_FLOAT, false, 0, 0) }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    companion object {
        const val DEFAULT_VERTEX =
            "attribute vec2 vertex;attribute vec2 _texCoord;varying vec2 texCoord;void main(){texCoord=_texCoord;gl_Position=vec4(vertex*2.0-1.0,0.0,1.0);}"
        const val DEFAULT_FRAGMENT =
            "uniform sampler2D texture;varying vec2 texCoord;void main(){gl_FragColor=texture2D(texture,texCoord);}"

        fun getDefaultShader(gl: Gl): Shader {
            val s = gl.defaultShader ?: Shader(gl, null, null)
            gl.defaultShader = s
            return s
        }

        private fun genBuffer(): Int = IntArray(1).also { GLES20.glGenBuffers(1, it, 0) }[0]

        fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }
    }
}

internal class Texture(private val gl: Gl, var width: Int, var height: Int, var format: Int, var type: Int) {
    var id: Int = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]

    init {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        if (width != 0 && height != 0) texImage(width, height, null)
    }

    private fun texImage(w: Int, h: Int, pixels: java.nio.Buffer?) {
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, gl.internalFormat(format, type), w, h, 0, format, type, pixels)
    }

    /**
     * `texImage2D(..., element)`: upload straight (non-premultiplied) RGBA bytes, top row first,
     * no flip, as WebGL does with UNPACK_FLIP_Y and UNPACK_PREMULTIPLY_ALPHA left false.
     */
    fun loadContentsOf(w: Int, h: Int, rgba: ByteBuffer) {
        width = w
        height = h
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        texImage(w, h, rgba)
    }

    fun initFromBytes(w: Int, h: Int, data: IntArray) {
        width = w
        height = h
        format = GLES20.GL_RGBA
        type = GLES20.GL_UNSIGNED_BYTE
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        val buf = ByteBuffer.allocateDirect(data.size)
        for (v in data) buf.put(v.toByte())
        buf.position(0)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, type, buf)
    }

    fun destroy() {
        GLES20.glDeleteTextures(1, intArrayOf(id), 0)
        id = 0
    }

    fun use(unit: Int = 0) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
    }

    fun unuse(unit: Int = 0) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun ensureFormat(other: Texture) = ensureFormat(other.width, other.height, other.format, other.type)

    fun ensureFormat(w: Int, h: Int, f: Int, t: Int) {
        if (w != width || h != height || f != format || t != type) {
            width = w; height = h; format = f; type = t
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            texImage(w, h, null)
        }
    }

    fun drawTo(callback: () -> Unit) {
        if (gl.framebuffer == 0) gl.framebuffer = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, gl.framebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, id, 0)
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw GlfxException("incomplete framebuffer")
        }
        GLES20.glViewport(0, 0, width, height)
        callback()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /** Same fields as glfx.js swaps: id, width, height, format (not type). */
    fun swapWith(other: Texture) {
        var t = other.id; other.id = id; id = t
        t = other.width; other.width = width; width = t
        t = other.height; other.height = height; height = t
        t = other.format; other.format = format; format = t
    }
}

/** glfx.js `z`: natural cubic spline through the control points, used by curves(). */
internal class Spline(points: List<DoubleArray>) {
    private val xa = ArrayList<Double>()
    private val ya = ArrayList<Double>()
    private val u = DoubleArray(points.size)
    private val y2 = DoubleArray(points.size)

    init {
        val d = points.size
        val a = points.sortedBy { it[0] }
        for (c in 0 until d) { xa.add(a[c][0]); ya.add(a[c][1]) }
        u[0] = 0.0
        y2[0] = 0.0
        for (c in 1 until d - 1) {
            val span = xa[c + 1] - xa[c - 1]
            val e = (xa[c] - xa[c - 1]) / span
            val g = e * y2[c - 1] + 2
            y2[c] = (e - 1) / g
            u[c] = (6 * ((ya[c + 1] - ya[c]) / (xa[c + 1] - xa[c]) - (ya[c] - ya[c - 1]) / (xa[c] - xa[c - 1])) / span - e * u[c - 1]) / g
        }
        y2[d - 1] = 0.0
        for (c in d - 2 downTo 0) y2[c] = y2[c] * y2[c + 1] + u[c]
    }

    fun interpolate(x: Double): Double {
        var d = 0
        var c = ya.size - 1
        while (1 < c - d) {
            val e = (c + d) shr 1
            if (xa[e] > x) c = e else d = e
        }
        val e = xa[c] - xa[d]
        val g = (xa[c] - x) / e
        val a = (x - xa[d]) / e
        return g * ya[d] + a * ya[c] + ((g * g * g - g) * y2[d] + (a * a * a - a) * y2[c]) * e * e / 6
    }
}
