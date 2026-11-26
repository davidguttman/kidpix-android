/*
 * glfx.js (http://evanw.github.com/glfx.js/, Copyright 2011 Evan Wallace, MIT license)
 * translated to Kotlin on OpenGL ES 2.0 with the same GLSL. The JS file is the spec:
 * upstream/js/util/glfx.js. Function names, argument order and pass structure are kept.
 */
package dev.kidpix.glfx

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Entry point, the `fx` object of glfx.js.
 *
 * ```
 * val gfx = Fx.canvas()              // owns an EGL context; create once, reuse
 * val tex = gfx.texture(bitmap)
 * val out = gfx.draw(tex).bulgePinch(x, y, r, s).update().toBitmap()
 * tex.destroy()
 * ```
 */
object Fx {
    /** `fx.canvas()`. The calling thread becomes the owner thread; see [FxCanvas]. */
    @JvmStatic
    fun canvas(): FxCanvas = FxCanvas()

    /** `fx.splineInterpolate(points)`: 256 values in 0..255. */
    @JvmStatic
    fun splineInterpolate(points: List<DoubleArray>): IntArray {
        val a = Spline(points)
        return IntArray(256) { c -> clampi(0, floor(256 * a.interpolate(c / 255.0)).toInt(), 255) }
    }

    internal fun clampi(lo: Int, v: Int, hi: Int) = max(lo, min(v, hi))
}

/** A texture handle from [FxCanvas.texture] (the `w(...)` wrapper of glfx.js). */
class FxTexture internal constructor(private val canvas: FxCanvas, internal val _t: Texture) {
    val width get() = _t.width
    val height get() = _t.height

    fun loadContentsOf(bitmap: Bitmap) = canvas.enter { canvas.upload(_t, bitmap) }
    fun loadContentsOf(rgba: ByteArray, width: Int, height: Int) = canvas.enter { canvas.upload(_t, rgba, width, height) }
    fun destroy() = canvas.enter { _t.destroy() }
}

/**
 * The glfx canvas: an offscreen EGL context (1x1 pbuffer, all work in framebuffer objects)
 * with the glfx filter chain on it.
 *
 * Threading: the thread that called [Fx.canvas] owns the context. Every method must be called
 * from that thread; any other thread gets an [IllegalStateException]. Several canvases may share
 * one owner thread (each call makes its own context current, like glfx's `a = this._.gl`).
 * Call [destroy] on the owner thread when done.
 *
 * Pixels: [texture] takes a premultiplied ARGB_8888 [Bitmap] (or straight RGBA bytes, like
 * ImageData) and uploads straight alpha, as WebGL does with a canvas source. [toBitmap] returns
 * what drawing the glfx canvas onto a 2D canvas gives: the result of the last [update], clamped
 * to 8 bits and premultiplied.
 */
class FxCanvas internal constructor() {
    private val owner: Thread = Thread.currentThread()
    private var display: EGLDisplay
    private var context: EGLContext
    private var surface: EGLSurface
    internal val gl: Gl

    var width = 0
        private set
    var height = 0
        private set

    // this._ in glfx.js
    private var isInitialized = false
    private var texture: Texture? = null
    private var spareTexture: Texture? = null
    private var extraTexture: Texture? = null
    private var flippedShader: Shader? = null
    /** Stands in for the canvas drawing buffer that update() draws to. */
    private var canvasTexture: Texture? = null
    private var stackTexture: Texture? = null
    private var readBuffer: ByteBuffer? = null

    /** Tests: false forces the 8-bit fallback (as on GPUs that cannot render to float textures). */
    internal var allowFloat = true

    /** True when the intermediate textures are float (glfx's OES_texture_float path). */
    val usesFloatTextures: Boolean get() = texture?.type == GLES20.GL_FLOAT

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("glfx: no EGL display")
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw IllegalStateException("glfx: eglInitialize failed")
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) || n[0] == 0) {
            throw IllegalStateException("This device does not support OpenGL ES 2.0 pbuffers")
        }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("This device does not support OpenGL ES 2.0")
        surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw IllegalStateException("glfx: eglMakeCurrent failed")
        val version = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
        val es3 = Regex("OpenGL ES (\\d+)").find(version)?.groupValues?.get(1)?.toIntOrNull()?.let { it >= 3 } ?: false
        val ext = (GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: "").split(' ').filter { it.isNotEmpty() }.toMutableSet()
        // glfx.js polyfill: claim OES_texture_float_linear when linear filtering of float textures works anyway.
        if (es3) ext.add("OES_texture_float") // core in ES 3 (texture side; rendering is probed below)
        if ("OES_texture_float_linear" !in ext && "OES_texture_float" in ext && floatLinearWorks()) ext.add("OES_texture_float_linear")
        gl = Gl(ext, es3)
    }

    val glVersion: String get() = enter { GLES20.glGetString(GLES20.GL_VERSION) + " / " + GLES20.glGetString(GLES20.GL_RENDERER) }

    /** The `k(...)` wrapper: check the thread, make this context current. */
    internal fun <T> enter(block: () -> T): T {
        val t = Thread.currentThread()
        check(t === owner) { "FxCanvas is owned by thread '${owner.name}' but was called from '${t.name}'" }
        check(surface != EGL14.EGL_NO_SURFACE) { "FxCanvas was destroyed" }
        if (EGL14.eglGetCurrentContext() != context) {
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw IllegalStateException("glfx: eglMakeCurrent failed")
        }
        return block()
    }

    // ---- canvas methods -------------------------------------------------------------------

    /** `canvas.texture(element)` for a Bitmap (any config; premultiplied input is un-premultiplied). */
    fun texture(bitmap: Bitmap): FxTexture = enter {
        val t = Texture(gl, 0, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE)
        upload(t, bitmap)
        FxTexture(this, t)
    }

    /** `canvas.texture(imageData)`: straight-alpha RGBA bytes, top row first. */
    fun texture(rgba: ByteArray, width: Int, height: Int): FxTexture = enter {
        val t = Texture(gl, 0, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE)
        upload(t, rgba, width, height)
        FxTexture(this, t)
    }

    internal fun upload(t: Texture, bitmap: Bitmap) {
        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val w = src.width
        val h = src.height
        // reused between uploads (a whole picture per mixer stroke: 3.4 MB twice was garbage each time)
        val px = uploadPx?.takeIf { it.size >= w * h } ?: IntArray(w * h).also { uploadPx = it }
        src.getPixels(px, 0, w, 0, 0, w, h) // non-premultiplied ARGB, same un-premultiply as Chrome's (Skia)
        val buf = (uploadBuf?.takeIf { it.capacity() >= w * h * 4 } ?: ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN).also { uploadBuf = it })
        buf.clear()
        val ib = buf.asIntBuffer()
        for (i in 0 until w * h) {
            val p = px[i]
            // little-endian int 0xAABBGGRR = bytes R,G,B,A
            px[i] = (p and 0xFF00FF00.toInt()) or ((p shr 16) and 0xFF) or ((p and 0xFF) shl 16)
        }
        ib.put(px, 0, w * h)
        buf.position(0); buf.limit(w * h * 4)
        t.loadContentsOf(w, h, buf)
    }
    private var uploadPx: IntArray? = null
    private var uploadBuf: ByteBuffer? = null

    internal fun upload(t: Texture, rgba: ByteArray, w: Int, h: Int) {
        require(rgba.size >= w * h * 4) { "need ${w * h * 4} bytes, got ${rgba.size}" }
        val buf = ByteBuffer.allocateDirect(w * h * 4)
        buf.put(rgba, 0, w * h * 4)
        buf.position(0)
        t.loadContentsOf(w, h, buf)
    }

    /** glfx `B`: (re)create the ping-pong textures, float when the context can render to them. */
    private fun initialize(w: Int, h: Int) {
        var type = GLES20.GL_UNSIGNED_BYTE
        if (allowFloat && gl.getExtension("OES_texture_float") && gl.getExtension("OES_texture_float_linear")) {
            val e = Texture(gl, 100, 100, GLES20.GL_RGBA, GLES20.GL_FLOAT)
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain: an unsupported format must not leak */ }
            try {
                e.drawTo { type = GLES20.GL_FLOAT }
            } catch (_: GlfxException) {
            }
            e.destroy()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
        texture?.destroy()
        spareTexture?.destroy()
        width = w
        height = h
        texture = Texture(gl, w, h, GLES20.GL_RGBA, type)
        spareTexture = Texture(gl, w, h, GLES20.GL_RGBA, type)
        extraTexture = extraTexture ?: Texture(gl, 0, 0, GLES20.GL_RGBA, type)
        flippedShader = flippedShader ?: Shader(gl, null,
            "uniform sampler2D texture;varying vec2 texCoord;void main(){gl_FragColor=texture2D(texture,vec2(texCoord.x,1.0-texCoord.y));}")
        isInitialized = true
    }

    /** `canvas.draw(texture, width?, height?)`. */
    @JvmOverloads
    fun draw(tex: FxTexture, width: Int? = null, height: Int? = null): FxCanvas = enter {
        val a = tex._t
        if (!(isInitialized && a.width == this.width && a.height == this.height)) {
            initialize(width ?: a.width, height ?: a.height)
        }
        a.use()
        texture!!.drawTo { Shader.getDefaultShader(gl).drawRect() }
        this
    }

    /**
     * `canvas.update()`: draw the current image to the canvas. glfx draws it flipped into the WebGL
     * drawing buffer (bottom-up); here the "drawing buffer" is a top-down RGBA8 texture, so the copy
     * is not flipped, and it is premultiplied the way a 2D canvas premultiplies on drawImage.
     */
    fun update(): FxCanvas = enter {
        val c = canvasTexture ?: Texture(gl, 0, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE).also { canvasTexture = it }
        c.ensureFormat(width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE)
        texture!!.use()
        val s = gl.shader("_present") {
            Shader(gl, null, "uniform sampler2D texture;varying vec2 texCoord;void main(){vec4 c=floor(clamp(texture2D(texture,texCoord),0.0,1.0)*255.0+0.5)/255.0;gl_FragColor=vec4(c.rgb*c.a,c.a);}")
        }
        c.drawTo { s.drawRect() }
        this
    }

    /**
     * The canvas contents (as of the last [update]) as a premultiplied ARGB_8888 bitmap.
     * Pass [reuse] (same size, ARGB_8888, mutable) to avoid allocating per frame.
     */
    @JvmOverloads
    fun toBitmap(reuse: Bitmap? = null): Bitmap = enter {
        val c = canvasTexture ?: throw IllegalStateException("toBitmap() before update()")
        val bytes = c.width * c.height * 4
        val buf = readBuffer?.takeIf { it.capacity() >= bytes } ?: ByteBuffer.allocateDirect(bytes).also { readBuffer = it }
        buf.position(0)
        c.drawTo {
            GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
            GLES20.glReadPixels(0, 0, c.width, c.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        }
        buf.position(0)
        val out = reuse?.takeIf { it.width == c.width && it.height == c.height && it.config == Bitmap.Config.ARGB_8888 && it.isMutable }
            ?: Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ARGB_8888)
        out.copyPixelsFromBuffer(buf.limit(bytes))
        buf.clear()
        out
    }

    /**
     * Kid Pix's Pancake Stack (not glfx): after [update], the canvas replaced by copies of itself
     * composited source-over onto transparent, as the mixer does with drawImage on a 2D canvas:
     * copy i = 1 until [n] at offset ((n * increment) - i * increment) * (dirX, dirY) device px,
     * nearest pixel, with globalAlpha i / n. One pass on the GPU instead of n canvas blits.
     */
    fun pancakeStack(n: Double, increment: Double, dirX: Double, dirY: Double): FxCanvas = enter {
        val c = canvasTexture ?: throw IllegalStateException("pancakeStack() before update()")
        val out = stackTexture ?: Texture(gl, 0, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE).also { stackTexture = it }
        out.ensureFormat(c.width, c.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE)
        c.use()
        val s = gl.shader("_pancake") {
            Shader(gl, null, "uniform sampler2D texture;uniform vec2 texSize;uniform float n;uniform float inc;uniform vec2 dir;varying vec2 texCoord;" +
                "void main(){vec2 p=texCoord*texSize;vec4 acc=vec4(0.0);float far=n*inc;" +
                "for(int k=1;k<256;k++){float i=float(k);if(i>=n)break;vec2 q=floor(p-(far-i*inc)*dir);" +
                "if(q.x>=0.0&&q.y>=0.0&&q.x<texSize.x&&q.y<texSize.y){vec4 s=texture2D(texture,(q+0.5)/texSize);float a=i/n;acc=s*a+acc*(1.0-s.a*a);}}" +
                "gl_FragColor=acc;}")
        }
        out.drawTo { s.uniforms(mapOf("texSize" to doubleArrayOf(c.width.toDouble(), c.height.toDouble()), "n" to n, "inc" to increment, "dir" to doubleArrayOf(dirX, dirY))).drawRect() }
        canvasTexture = out; stackTexture = c
        this
    }

    /** `canvas.contents()`: a copy of the current image as a new 8-bit texture. */
    fun contents(): FxTexture = enter {
        val t = texture!!
        val b = Texture(gl, t.width, t.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE)
        t.use()
        b.drawTo { Shader.getDefaultShader(gl).drawRect() }
        FxTexture(this, b)
    }

    /** `canvas.getPixelArray()`: straight RGBA bytes of the current image, top row first. */
    fun getPixelArray(): ByteArray = enter {
        val t = texture!!
        val src = if (t.type == GLES20.GL_UNSIGNED_BYTE) t else Texture(gl, t.width, t.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE).also { b ->
            t.use(); b.drawTo { Shader.getDefaultShader(gl).drawRect() }
        }
        val buf = ByteBuffer.allocateDirect(t.width * t.height * 4)
        src.drawTo {
            GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
            GLES20.glReadPixels(0, 0, t.width, t.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        }
        if (src !== t) src.destroy()
        ByteArray(t.width * t.height * 4).also { buf.position(0); buf.get(it) }
    }

    /** Free the textures, shaders and the EGL context. Owner thread only. */
    fun destroy() {
        enter {
            listOfNotNull(texture, spareTexture, extraTexture, canvasTexture, stackTexture).forEach { it.destroy() }
            gl.shaders.values.forEach { it.destroy() }
            gl.defaultShader?.destroy()
            flippedShader?.destroy()
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        surface = EGL14.EGL_NO_SURFACE
    }

    // ---- filter plumbing --------------------------------------------------------------------

    /** glfx `f`: one pass of [shader] from [source] into the spare texture, then swap it into [dest]. */
    private fun simpleShader(shader: Shader, uniforms: Map<String, Any>, source: Texture? = null, dest: Texture? = null) {
        (source ?: texture!!).use()
        spareTexture!!.drawTo { shader.uniforms(uniforms).drawRect() }
        spareTexture!!.swapWith(dest ?: texture!!)
    }

    private fun size() = doubleArrayOf(width.toDouble(), height.toDouble())

    /** glfx `u`: a warp shader around the coordinate-mapping GLSL [code]. */
    private fun warpShader(uniforms: String, code: String) = Shader(gl, null,
        uniforms + "uniform sampler2D texture;uniform vec2 texSize;varying vec2 texCoord;void main(){vec2 coord=texCoord*texSize;" +
            code + "gl_FragColor=texture2D(texture,coord/texSize);vec2 clampedCoord=clamp(coord,vec2(0.0),texSize);if(coord!=clampedCoord){gl_FragColor.a*=max(0.0,1.0-length(coord-clampedCoord));}}")

    private fun q(lo: Double, v: Double, hi: Double) = max(lo, min(v, hi))

    // ---- filters (alphabetical order of the JS method table is not kept; the bodies are) -----

    fun brightnessContrast(brightness: Double, contrast: Double): FxCanvas = enter {
        val s = gl.shader("brightnessContrast") { Shader(gl, null, "uniform sampler2D texture;uniform float brightness;uniform float contrast;varying vec2 texCoord;void main(){vec4 color=texture2D(texture,texCoord);color.rgb+=brightness;if(contrast>0.0){color.rgb=(color.rgb-0.5)/(1.0-contrast)+0.5;}else{color.rgb=(color.rgb-0.5)*(1.0+contrast)+0.5;}gl_FragColor=color;}") }
        simpleShader(s, mapOf("brightness" to q(-1.0, brightness, 1.0), "contrast" to q(-1.0, contrast, 1.0)))
        this
    }

    /** `curves(red)` or `curves(red, green, blue)`; each a list of [x, y] control points in 0..1. */
    @JvmOverloads
    fun curves(red: List<DoubleArray>, green: List<DoubleArray>? = null, blue: List<DoubleArray>? = null): FxCanvas = enter {
        val r = Fx.splineInterpolate(red)
        val g: IntArray
        val b: IntArray
        if (green == null && blue == null) { g = r; b = r } else {
            g = Fx.splineInterpolate(green!!); b = Fx.splineInterpolate(blue!!)
        }
        val e = IntArray(256 * 4)
        for (i in 0 until 256) { e[i * 4] = r[i]; e[i * 4 + 1] = g[i]; e[i * 4 + 2] = b[i]; e[i * 4 + 3] = 255 }
        extraTexture!!.initFromBytes(256, 1, e)
        extraTexture!!.use(1)
        val s = gl.shader("curves") { Shader(gl, null, "uniform sampler2D texture;uniform sampler2D map;varying vec2 texCoord;void main(){vec4 color=texture2D(texture,texCoord);color.r=texture2D(map,vec2(color.r)).r;color.g=texture2D(map,vec2(color.g)).g;color.b=texture2D(map,vec2(color.b)).b;gl_FragColor=color;}") }
        s.textures(mapOf("map" to 1))
        simpleShader(s, emptyMap())
        this
    }

    fun denoise(exponent: Double): FxCanvas = enter {
        val s = gl.shader("denoise") { Shader(gl, null, "uniform sampler2D texture;uniform float exponent;uniform float strength;uniform vec2 texSize;varying vec2 texCoord;void main(){vec4 center=texture2D(texture,texCoord);vec4 color=vec4(0.0);float total=0.0;for(float x=-4.0;x<=4.0;x+=1.0){for(float y=-4.0;y<=4.0;y+=1.0){vec4 sample=texture2D(texture,texCoord+vec2(x,y)/texSize);float weight=1.0-abs(dot(sample.rgb-center.rgb,vec3(0.25)));weight=pow(weight,exponent);color+=sample*weight;total+=weight;}}gl_FragColor=color/total;}") }
        for (d in 0 until 2) simpleShader(s, mapOf("exponent" to max(0.0, exponent), "texSize" to size()))
        this
    }

    fun hueSaturation(hue: Double, saturation: Double): FxCanvas = enter {
        val s = gl.shader("hueSaturation") { Shader(gl, null, "uniform sampler2D texture;uniform float hue;uniform float saturation;varying vec2 texCoord;void main(){vec4 color=texture2D(texture,texCoord);float angle=hue*3.14159265;float s=sin(angle),c=cos(angle);vec3 weights=(vec3(2.0*c,-sqrt(3.0)*s-c,sqrt(3.0)*s-c)+1.0)/3.0;float len=length(color.rgb);color.rgb=vec3(dot(color.rgb,weights.xyz),dot(color.rgb,weights.zxy),dot(color.rgb,weights.yzx));float average=(color.r+color.g+color.b)/3.0;if(saturation>0.0){color.rgb+=(average-color.rgb)*(1.0-1.0/(1.001-saturation));}else{color.rgb+=(average-color.rgb)*(-saturation);}gl_FragColor=color;}") }
        simpleShader(s, mapOf("hue" to q(-1.0, hue, 1.0), "saturation" to q(-1.0, saturation, 1.0)))
        this
    }

    fun noise(amount: Double): FxCanvas = enter {
        val s = gl.shader("noise") { Shader(gl, null, "uniform sampler2D texture;uniform float amount;varying vec2 texCoord;float rand(vec2 co){return fract(sin(dot(co.xy,vec2(12.9898,78.233)))*43758.5453);}void main(){vec4 color=texture2D(texture,texCoord);float diff=(rand(texCoord)-0.5)*amount;color.r+=diff;color.g+=diff;color.b+=diff;gl_FragColor=color;}") }
        simpleShader(s, mapOf("amount" to q(0.0, amount, 1.0)))
        this
    }

    fun sepia(amount: Double): FxCanvas = enter {
        val s = gl.shader("sepia") { Shader(gl, null, "uniform sampler2D texture;uniform float amount;varying vec2 texCoord;void main(){vec4 color=texture2D(texture,texCoord);float r=color.r;float g=color.g;float b=color.b;color.r=min(1.0,(r*(1.0-(0.607*amount)))+(g*(0.769*amount))+(b*(0.189*amount)));color.g=min(1.0,(r*0.349*amount)+(g*(1.0-(0.314*amount)))+(b*0.168*amount));color.b=min(1.0,(r*0.272*amount)+(g*0.534*amount)+(b*(1.0-(0.869*amount))));gl_FragColor=color;}") }
        simpleShader(s, mapOf("amount" to q(0.0, amount, 1.0)))
        this
    }

    fun unsharpMask(radius: Double, strength: Double): FxCanvas = enter {
        val s = gl.shader("unsharpMask") { Shader(gl, null, "uniform sampler2D blurredTexture;uniform sampler2D originalTexture;uniform float strength;uniform float threshold;varying vec2 texCoord;void main(){vec4 blurred=texture2D(blurredTexture,texCoord);vec4 original=texture2D(originalTexture,texCoord);gl_FragColor=mix(blurred,original,1.0+strength);}") }
        val extra = extraTexture!!
        extra.ensureFormat(texture!!)
        texture!!.use()
        extra.drawTo { Shader.getDefaultShader(gl).drawRect() }
        extra.use(1)
        triangleBlur(radius)
        s.textures(mapOf("originalTexture" to 1))
        simpleShader(s, mapOf("strength" to strength))
        extra.unuse(1)
        this
    }

    fun vibrance(amount: Double): FxCanvas = enter {
        val s = gl.shader("vibrance") { Shader(gl, null, "uniform sampler2D texture;uniform float amount;varying vec2 texCoord;void main(){vec4 color=texture2D(texture,texCoord);float average=(color.r+color.g+color.b)/3.0;float mx=max(color.r,max(color.g,color.b));float amt=(mx-average)*(-amount*3.0);color.rgb=mix(color.rgb,vec3(mx),amt);gl_FragColor=color;}") }
        simpleShader(s, mapOf("amount" to q(-1.0, amount, 1.0)))
        this
    }

    fun vignette(size: Double, amount: Double): FxCanvas = enter {
        val s = gl.shader("vignette") { Shader(gl, null, "uniform sampler2D texture;uniform float size;uniform float amount;varying vec2 texCoord;void main(){vec4 color=texture2D(texture,texCoord);float dist=distance(texCoord,vec2(0.5,0.5));color.rgb*=smoothstep(0.8,size*0.799,dist*(amount+size));gl_FragColor=color;}") }
        simpleShader(s, mapOf("size" to q(0.0, size, 1.0), "amount" to q(0.0, amount, 1.0)))
        this
    }

    fun lensBlur(radius: Double, brightness: Double, angle: Double): FxCanvas = enter {
        val pre = gl.shader("lensBlurPrePass") { Shader(gl, null, "uniform sampler2D texture;uniform float power;varying vec2 texCoord;void main(){vec4 color=texture2D(texture,texCoord);color=pow(color,vec4(power));gl_FragColor=vec4(color);}") }
        val common = "uniform sampler2D texture0;uniform sampler2D texture1;uniform vec2 delta0;uniform vec2 delta1;uniform float power;varying vec2 texCoord;" +
            RANDOM + "vec4 sample(vec2 delta){float offset=random(vec3(delta,151.7182),0.0);vec4 color=vec4(0.0);float total=0.0;for(float t=0.0;t<=30.0;t++){float percent=(t+offset)/30.0;color+=texture2D(texture0,texCoord+delta*percent);total+=1.0;}return color/total;}"
        val lb0 = gl.shader("lensBlur0") { Shader(gl, null, common + "void main(){gl_FragColor=sample(delta0);}") }
        val lb1 = gl.shader("lensBlur1") { Shader(gl, null, common + "void main(){gl_FragColor=(sample(delta0)+sample(delta1))*0.5;}") }
        val lb2 = gl.shader("lensBlur2") { Shader(gl, null, common + "void main(){vec4 color=(sample(delta0)+2.0*texture2D(texture1,texCoord))/3.0;gl_FragColor=pow(color,vec4(power));}").textures(mapOf("texture1" to 1)) }
        val e = Array(3) { g ->
            val l = angle + 2 * g * PI / 3
            doubleArrayOf(radius * sin(l) / width, radius * cos(l) / height)
        }
        val b = 10.0.pow(q(-1.0, brightness, 1.0))
        simpleShader(pre, mapOf("power" to b))
        val extra = extraTexture!!
        extra.ensureFormat(texture!!)
        simpleShader(lb0, mapOf("delta0" to e[0]), texture, extra)
        simpleShader(lb1, mapOf("delta0" to e[1], "delta1" to e[2]), extra, extra)
        simpleShader(lb0, mapOf("delta0" to e[1]))
        extra.use(1)
        simpleShader(lb2, mapOf("power" to 1 / b, "delta0" to e[2]))
        this
    }

    fun tiltShift(startX: Double, startY: Double, endX: Double, endY: Double, blurRadius: Double, gradientRadius: Double): FxCanvas = enter {
        val s = gl.shader("tiltShift") { Shader(gl, null, "uniform sampler2D texture;uniform float blurRadius;uniform float gradientRadius;uniform vec2 start;uniform vec2 end;uniform vec2 delta;uniform vec2 texSize;varying vec2 texCoord;" + RANDOM + "void main(){vec4 color=vec4(0.0);float total=0.0;float offset=random(vec3(12.9898,78.233,151.7182),0.0);vec2 normal=normalize(vec2(start.y-end.y,end.x-start.x));float radius=smoothstep(0.0,1.0,abs(dot(texCoord*texSize-start,normal))/gradientRadius)*blurRadius;for(float t=-30.0;t<=30.0;t++){float percent=(t+offset-0.5)/30.0;float weight=1.0-abs(percent);vec4 sample=texture2D(texture,texCoord+delta/texSize*percent*radius);sample.rgb*=sample.a;color+=sample*weight;total+=weight;}gl_FragColor=color/total;gl_FragColor.rgb/=gl_FragColor.a+0.00001;}") }
        val n = endX - startX
        val p = endY - startY
        val m = sqrt(n * n + p * p)
        simpleShader(s, mapOf("blurRadius" to blurRadius, "gradientRadius" to gradientRadius, "start" to doubleArrayOf(startX, startY),
            "end" to doubleArrayOf(endX, endY), "delta" to doubleArrayOf(n / m, p / m), "texSize" to size()))
        simpleShader(s, mapOf("blurRadius" to blurRadius, "gradientRadius" to gradientRadius, "start" to doubleArrayOf(startX, startY),
            "end" to doubleArrayOf(endX, endY), "delta" to doubleArrayOf(-p / m, n / m), "texSize" to size()))
        this
    }

    fun triangleBlur(radius: Double): FxCanvas = enter {
        val s = gl.shader("triangleBlur") { Shader(gl, null, "uniform sampler2D texture;uniform vec2 delta;varying vec2 texCoord;" + RANDOM + "void main(){vec4 color=vec4(0.0);float total=0.0;float offset=random(vec3(12.9898,78.233,151.7182),0.0);for(float t=-30.0;t<=30.0;t++){float percent=(t+offset-0.5)/30.0;float weight=1.0-abs(percent);vec4 sample=texture2D(texture,texCoord+delta*percent);sample.rgb*=sample.a;color+=sample*weight;total+=weight;}gl_FragColor=color/total;gl_FragColor.rgb/=gl_FragColor.a+0.00001;}") }
        simpleShader(s, mapOf("delta" to doubleArrayOf(radius / width, 0.0)))
        simpleShader(s, mapOf("delta" to doubleArrayOf(0.0, radius / height)))
        this
    }

    fun zoomBlur(centerX: Double, centerY: Double, strength: Double): FxCanvas = enter {
        val s = gl.shader("zoomBlur") { Shader(gl, null, "uniform sampler2D texture;uniform vec2 center;uniform float strength;uniform vec2 texSize;varying vec2 texCoord;" + RANDOM + "void main(){vec4 color=vec4(0.0);float total=0.0;vec2 toCenter=center-texCoord*texSize;float offset=random(vec3(12.9898,78.233,151.7182),0.0);for(float t=0.0;t<=40.0;t++){float percent=(t+offset)/40.0;float weight=4.0*(percent-percent*percent);vec4 sample=texture2D(texture,texCoord+toCenter*percent*strength/texSize);sample.rgb*=sample.a;color+=sample*weight;total+=weight;}gl_FragColor=color/total;gl_FragColor.rgb/=gl_FragColor.a+0.00001;}") }
        simpleShader(s, mapOf("center" to doubleArrayOf(centerX, centerY), "strength" to strength, "texSize" to size()))
        this
    }

    fun colorHalftone(centerX: Double, centerY: Double, angle: Double, size: Double): FxCanvas = enter {
        val s = gl.shader("colorHalftone") { Shader(gl, null, "uniform sampler2D texture;uniform vec2 center;uniform float angle;uniform float scale;uniform vec2 texSize;varying vec2 texCoord;float pattern(float angle){float s=sin(angle),c=cos(angle);vec2 tex=texCoord*texSize-center;vec2 point=vec2(c*tex.x-s*tex.y,s*tex.x+c*tex.y)*scale;return(sin(point.x)*sin(point.y))*4.0;}void main(){vec4 color=texture2D(texture,texCoord);vec3 cmy=1.0-color.rgb;float k=min(cmy.x,min(cmy.y,cmy.z));cmy=(cmy-k)/(1.0-k);cmy=clamp(cmy*10.0-3.0+vec3(pattern(angle+0.26179),pattern(angle+1.30899),pattern(angle)),0.0,1.0);k=clamp(k*10.0-5.0+pattern(angle+0.78539),0.0,1.0);gl_FragColor=vec4(1.0-cmy-k,color.a);}") }
        simpleShader(s, mapOf("center" to doubleArrayOf(centerX, centerY), "angle" to angle, "scale" to PI / size, "texSize" to size()))
        this
    }

    fun dotScreen(centerX: Double, centerY: Double, angle: Double, size: Double): FxCanvas = enter {
        val s = gl.shader("dotScreen") { Shader(gl, null, "uniform sampler2D texture;uniform vec2 center;uniform float angle;uniform float scale;uniform vec2 texSize;varying vec2 texCoord;float pattern(){float s=sin(angle),c=cos(angle);vec2 tex=texCoord*texSize-center;vec2 point=vec2(c*tex.x-s*tex.y,s*tex.x+c*tex.y)*scale;return(sin(point.x)*sin(point.y))*4.0;}void main(){vec4 color=texture2D(texture,texCoord);float average=(color.r+color.g+color.b)/3.0;gl_FragColor=vec4(vec3(average*10.0-5.0+pattern()),color.a);}") }
        simpleShader(s, mapOf("center" to doubleArrayOf(centerX, centerY), "angle" to angle, "scale" to PI / size, "texSize" to size()))
        this
    }

    fun edgeWork(radius: Double): FxCanvas = enter {
        val s1 = gl.shader("edgeWork1") { Shader(gl, null, "uniform sampler2D texture;uniform vec2 delta;varying vec2 texCoord;" + RANDOM + "void main(){vec2 color=vec2(0.0);vec2 total=vec2(0.0);float offset=random(vec3(12.9898,78.233,151.7182),0.0);for(float t=-30.0;t<=30.0;t++){float percent=(t+offset-0.5)/30.0;float weight=1.0-abs(percent);vec3 sample=texture2D(texture,texCoord+delta*percent).rgb;float average=(sample.r+sample.g+sample.b)/3.0;color.x+=average*weight;total.x+=weight;if(abs(t)<15.0){weight=weight*2.0-1.0;color.y+=average*weight;total.y+=weight;}}gl_FragColor=vec4(color/total,0.0,1.0);}") }
        val s2 = gl.shader("edgeWork2") { Shader(gl, null, "uniform sampler2D texture;uniform vec2 delta;varying vec2 texCoord;" + RANDOM + "void main(){vec2 color=vec2(0.0);vec2 total=vec2(0.0);float offset=random(vec3(12.9898,78.233,151.7182),0.0);for(float t=-30.0;t<=30.0;t++){float percent=(t+offset-0.5)/30.0;float weight=1.0-abs(percent);vec2 sample=texture2D(texture,texCoord+delta*percent).xy;color.x+=sample.x*weight;total.x+=weight;if(abs(t)<15.0){weight=weight*2.0-1.0;color.y+=sample.y*weight;total.y+=weight;}}float c=clamp(10000.0*(color.y/total.y-color.x/total.x)+0.5,0.0,1.0);gl_FragColor=vec4(c,c,c,1.0);}") }
        simpleShader(s1, mapOf("delta" to doubleArrayOf(radius / width, 0.0)))
        simpleShader(s2, mapOf("delta" to doubleArrayOf(0.0, radius / height)))
        this
    }

    fun hexagonalPixelate(centerX: Double, centerY: Double, scale: Double): FxCanvas = enter {
        val s = gl.shader("hexagonalPixelate") { Shader(gl, null, "uniform sampler2D texture;uniform vec2 center;uniform float scale;uniform vec2 texSize;varying vec2 texCoord;void main(){vec2 tex=(texCoord*texSize-center)/scale;tex.y/=0.866025404;tex.x-=tex.y*0.5;vec2 a;if(tex.x+tex.y-floor(tex.x)-floor(tex.y)<1.0)a=vec2(floor(tex.x),floor(tex.y));else a=vec2(ceil(tex.x),ceil(tex.y));vec2 b=vec2(ceil(tex.x),floor(tex.y));vec2 c=vec2(floor(tex.x),ceil(tex.y));vec3 TEX=vec3(tex.x,tex.y,1.0-tex.x-tex.y);vec3 A=vec3(a.x,a.y,1.0-a.x-a.y);vec3 B=vec3(b.x,b.y,1.0-b.x-b.y);vec3 C=vec3(c.x,c.y,1.0-c.x-c.y);float alen=length(TEX-A);float blen=length(TEX-B);float clen=length(TEX-C);vec2 choice;if(alen<blen){if(alen<clen)choice=a;else choice=c;}else{if(blen<clen)choice=b;else choice=c;}choice.x+=choice.y*0.5;choice.y*=0.866025404;choice*=scale/texSize;gl_FragColor=texture2D(texture,choice+center/texSize);}") }
        simpleShader(s, mapOf("center" to doubleArrayOf(centerX, centerY), "scale" to scale, "texSize" to size()))
        this
    }

    fun ink(strength: Double): FxCanvas = enter {
        val s = gl.shader("ink") { Shader(gl, null, "uniform sampler2D texture;uniform float strength;uniform vec2 texSize;varying vec2 texCoord;void main(){vec2 dx=vec2(1.0/texSize.x,0.0);vec2 dy=vec2(0.0,1.0/texSize.y);vec4 color=texture2D(texture,texCoord);float bigTotal=0.0;float smallTotal=0.0;vec3 bigAverage=vec3(0.0);vec3 smallAverage=vec3(0.0);for(float x=-2.0;x<=2.0;x+=1.0){for(float y=-2.0;y<=2.0;y+=1.0){vec3 sample=texture2D(texture,texCoord+dx*x+dy*y).rgb;bigAverage+=sample;bigTotal+=1.0;if(abs(x)+abs(y)<2.0){smallAverage+=sample;smallTotal+=1.0;}}}vec3 edge=max(vec3(0.0),bigAverage/bigTotal-smallAverage/smallTotal);gl_FragColor=vec4(color.rgb-dot(edge,edge)*strength*100000.0,color.a);}") }
        simpleShader(s, mapOf("strength" to strength * strength * strength * strength * strength, "texSize" to size()))
        this
    }

    fun bulgePinch(centerX: Double, centerY: Double, radius: Double, strength: Double): FxCanvas = enter {
        val s = gl.shader("bulgePinch") { warpShader("uniform float radius;uniform float strength;uniform vec2 center;", "coord-=center;float distance=length(coord);if(distance<radius){float percent=distance/radius;if(strength>0.0){coord*=mix(1.0,smoothstep(0.0,radius/distance,percent),strength*0.75);}else{coord*=mix(1.0,pow(percent,1.0+strength*0.75)*radius/distance,1.0-percent);}}coord+=center;") }
        simpleShader(s, mapOf("radius" to radius, "strength" to q(-1.0, strength, 1.0), "center" to doubleArrayOf(centerX, centerY), "texSize" to size()))
        this
    }

    /** `matrixWarp(matrix, inverse, useTextureSpace)`; matrix is 2x2 or 3x3, flat row by row. */
    @JvmOverloads
    fun matrixWarp(matrix: DoubleArray, inverse: Boolean = false, useTextureSpace: Boolean = false): FxCanvas = enter {
        val s = gl.shader("matrixWarp") { warpShader("uniform mat3 matrix;uniform bool useTextureSpace;", "if(useTextureSpace)coord=coord/texSize*2.0-1.0;vec3 warp=matrix*vec3(coord,1.0);coord=warp.xy/warp.z;if(useTextureSpace)coord=(coord*0.5+0.5)*texSize;") }
        var b = matrix
        if (b.size == 4) b = doubleArrayOf(b[0], b[1], 0.0, b[2], b[3], 0.0, 0.0, 0.0, 1.0)
        else if (b.size != 9) throw IllegalArgumentException("can only warp with 2x2 or 3x3 matrix")
        simpleShader(s, mapOf("matrix" to (if (inverse) inverse3(b) else b), "texSize" to size(), "useTextureSpace" to (if (useTextureSpace) 1 else 0)))
        this
    }

    /** Nested form, `matrixWarp([[a, b], [c, d]])` (glfx flattens with concat). */
    @JvmOverloads
    fun matrixWarp(matrix: Array<DoubleArray>, inverse: Boolean = false, useTextureSpace: Boolean = false): FxCanvas =
        matrixWarp(matrix.flatMap { it.asList() }.toDoubleArray(), inverse, useTextureSpace)

    /** `perspective(before, after)`: two quads of 4 points each, [x0, y0, x1, y1, x2, y2, x3, y3]. */
    fun perspective(before: DoubleArray, after: DoubleArray): FxCanvas {
        val c = inverse3(quadToSquare(after))
        val e = quadToSquare(before)
        return matrixWarp(doubleArrayOf(
            c[0] * e[0] + c[1] * e[3] + c[2] * e[6], c[0] * e[1] + c[1] * e[4] + c[2] * e[7], c[0] * e[2] + c[1] * e[5] + c[2] * e[8],
            c[3] * e[0] + c[4] * e[3] + c[5] * e[6], c[3] * e[1] + c[4] * e[4] + c[5] * e[7], c[3] * e[2] + c[4] * e[5] + c[5] * e[8],
            c[6] * e[0] + c[7] * e[3] + c[8] * e[6], c[6] * e[1] + c[7] * e[4] + c[8] * e[7], c[6] * e[2] + c[7] * e[5] + c[8] * e[8],
        ))
    }

    fun swirl(centerX: Double, centerY: Double, radius: Double, angle: Double): FxCanvas = enter {
        val s = gl.shader("swirl") { warpShader("uniform float radius;uniform float angle;uniform vec2 center;", "coord-=center;float distance=length(coord);if(distance<radius){float percent=(radius-distance)/radius;float theta=percent*percent*angle;float s=sin(theta);float c=cos(theta);coord=vec2(coord.x*c-coord.y*s,coord.x*s+coord.y*c);}coord+=center;") }
        simpleShader(s, mapOf("radius" to radius, "center" to doubleArrayOf(centerX, centerY), "angle" to angle, "texSize" to size()))
        this
    }

    // ---- helpers from glfx.js ---------------------------------------------------------------

    /** glfx's OES_texture_float_linear probe: linear sampling of a 2x2 float texture at its centre. */
    private fun floatLinearWorks(): Boolean {
        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        val fb = IntArray(1)
        GLES20.glGenFramebuffers(1, fb, 0)
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            texParams(GLES20.GL_NEAREST)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, ids[0], 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[1])
            texParams(GLES20.GL_LINEAR)
            val data = FloatArray(16).also { it[0] = 2f }
            val es3 = (GLES20.glGetString(GLES20.GL_VERSION) ?: "").contains("ES 3")
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, if (es3) Gl.GL_RGBA32F else GLES20.GL_RGBA, 2, 2, 0, GLES20.GL_RGBA, GLES20.GL_FLOAT, Shader.floats(*data))
            val gl0 = Gl(emptySet(), es3)
            val p = Shader(gl0, "attribute vec2 vertex;void main(){gl_Position=vec4(vertex,0.0,1.0);gl_PointSize=1.0;}",
                "uniform sampler2D texture;void main(){gl_FragColor=texture2D(texture,vec2(0.5));}")
            val buf = IntArray(1)
            GLES20.glGenBuffers(1, buf, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buf[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 8, Shader.floats(0f, 0f), GLES20.GL_STREAM_DRAW)
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glUseProgram(p.program)
            GLES20.glViewport(0, 0, 1, 1)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
            val d = ByteBuffer.allocateDirect(4)
            GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, d)
            GLES20.glDisableVertexAttribArray(0)
            GLES20.glDeleteBuffers(1, buf, 0)
            p.destroy()
            val r = d.get(0).toInt() and 0xFF
            return r == 127 || r == 128
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, fb, 0)
            GLES20.glDeleteTextures(2, ids, 0)
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { }
        }
    }

    private fun texParams(filter: Int) {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    companion object {
        /** glfx's `s`: the per-pixel random function shared by the blur shaders. */
        const val RANDOM = "float random(vec3 scale,float seed){return fract(sin(dot(gl_FragCoord.xyz+seed,scale))*43758.5453+seed);}"

        /** glfx `x`: matrix mapping the unit square to the quad. */
        internal fun quadToSquare(q: DoubleArray): DoubleArray {
            val (a, d, c, e) = listOf(q[0], q[1], q[2], q[3])
            var g = q[4]
            var l = q[5]
            val n = q[6]
            val p = q[7]
            var m = c - g
            val h = e - l
            var f = n - g
            val k = p - l
            g = a - c + g - n
            l = d - e + l - p
            val qq = m * k - f * h
            f = (g * k - f * l) / qq
            m = (m * l - g * h) / qq
            return doubleArrayOf(c - a + f * c, e - d + f * e, f, n - a + m * n, p - d + m * p, m, a, d, 1.0)
        }

        /** glfx `y`: 3x3 inverse. */
        internal fun inverse3(a: DoubleArray): DoubleArray {
            val (d, c, e, g, l) = listOf(a[0], a[1], a[2], a[3], a[4])
            val n = a[5]; val p = a[6]; val m = a[7]; val aa = a[8]
            val f = d * l * aa - d * n * m - c * g * aa + c * n * p + e * g * m - e * l * p
            return doubleArrayOf((l * aa - n * m) / f, (e * m - c * aa) / f, (c * n - e * l) / f, (n * p - g * aa) / f, (d * aa - e * p) / f,
                (e * g - d * n) / f, (g * m - l * p) / f, (c * p - d * m) / f, (d * l - c * g) / f)
        }
    }
}
