@file:JvmName("util_GlfxBridge")

package dev.kidpix.paint

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import dev.kidpix.glfx.Fx
import dev.kidpix.glfx.FxCanvas
import dev.kidpix.glfx.FxTexture
import dev.kidpix.paint.canvas.CanvasImageSource
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

// The glfx bridge (no upstream file): what `fx.canvas()` from js/util/glfx.js gives the mixer.
//
// The engine side reads like the JS and stays synchronous:
//   val gfx = fx.canvas(); val tex = gfx.texture(main_canvas)
//   val rendered = gfx.draw(tex).swirl(x, y, r, a).update()   // a CanvasImageSource
//   ctx.drawImage(rendered, 0, 0); tex.destroy()
// draw() and the filters only record the chain; update() hands it to KGlfx.bridge, which runs it
// and returns the picture. In the app the bridge is :glfx on one dedicated GL thread (the engine
// thread blocks for the frame). Under Robolectric there is no GL: update() throws GlfxNeedsGL,
// and the harness records the scenario as "skipped: needs GL" instead of saving a wrong picture.

/** Thrown where a real GLES context is needed and there is none (the JVM harness). */
class GlfxNeedsGL : RuntimeException("skipped: needs GL")

/** One recorded glfx call: the method name and its numeric arguments. */
class GfxOp(val name: String, val args: DoubleArray)

/** Runs glfx chains. Handles are opaque to the engine. */
interface GlfxBridge {
    /** `canvas.texture(element)`: upload [bitmap] (read synchronously; the caller may change it afterwards). */
    fun texture(bitmap: Bitmap): Any
    fun destroyTexture(handle: Any)
    /** draw(texture), then [ops] in order, then update(); the picture comes back premultiplied. */
    fun render(texture: Any, ops: List<GfxOp>, reuse: Bitmap?): Bitmap

    /**
     * [render] without waiting: [done] gets the picture later on the engine's thread (or right
     * away where rendering is synchronous). Requests made while one is rendering coalesce: only
     * the newest is rendered next. The bitmap is valid until [done] returns.
     */
    fun renderAsync(texture: Any, ops: List<GfxOp>, done: (Bitmap) -> Unit) { done(render(texture, ops, null)) }
}

/** Robolectric: textures are free placeholders, rendering needs GL. */
object HeadlessGlfx : GlfxBridge {
    override fun texture(bitmap: Bitmap): Any = Unit
    override fun destroyTexture(handle: Any) {}
    override fun render(texture: Any, ops: List<GfxOp>, reuse: Bitmap?): Bitmap = throw GlfxNeedsGL()
}

/** The app: :glfx on one HandlerThread that owns the EGL context for the life of the process. */
class GlThreadGlfx : GlfxBridge {
    private val thread = HandlerThread("kidpix-glfx").apply { start() }
    private val handler = Handler(thread.looper)
    private var gfx: FxCanvas? = null

    private fun <T> onGl(block: () -> T): T {
        if (Looper.myLooper() === thread.looper) return block()
        val task = FutureTask(block)
        handler.post(task)
        try {
            return task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun canvas(): FxCanvas = gfx ?: Fx.canvas().also { gfx = it }

    override fun texture(bitmap: Bitmap): Any = onGl { canvas().texture(bitmap) }

    override fun destroyTexture(handle: Any) {
        if (latest?.texture === handle) latest = null
        handler.post { (handle as FxTexture).destroy() }
    }

    // renderAsync: one request in flight at a time; newer ones wait in [latest] (only the newest)
    private val main = Handler(Looper.getMainLooper())
    private class Req(val texture: Any, val ops: List<GfxOp>, val done: (Bitmap) -> Unit)
    private var latest: Req? = null
    private var inFlight = false
    private var asyncOut: Bitmap? = null

    override fun renderAsync(texture: Any, ops: List<GfxOp>, done: (Bitmap) -> Unit) {
        latest = Req(texture, ops, done)
        if (!inFlight) launch()
    }

    private fun launch() {
        val r = latest ?: return
        latest = null
        inFlight = true
        handler.post {
            val out = try { draw(r.texture, r.ops, asyncOut) } catch (t: Throwable) { android.util.Log.e("KidPix", "glfx render failed", t); null }
            if (out != null) asyncOut = out
            main.post {
                inFlight = false
                if (out != null) r.done(out)
                if (latest != null) launch()
            }
        }
    }

    override fun render(texture: Any, ops: List<GfxOp>, reuse: Bitmap?): Bitmap = onGl { draw(texture, ops, reuse) }

    private fun draw(texture: Any, ops: List<GfxOp>, reuse: Bitmap?): Bitmap {
        var c = canvas().draw(texture as FxTexture)
        var stack: GfxOp? = null
        for (op in ops) {
            val a = op.args
            if (op.name == "pancakeStack") { stack = op; continue } // on the updated canvas, below
            c = when (op.name) {
                "bulgePinch" -> c.bulgePinch(a[0], a[1], a[2], a[3])
                "swirl" -> c.swirl(a[0], a[1], a[2], a[3])
                "lensBlur" -> c.lensBlur(a[0], a[1], a[2])
                "triangleBlur" -> c.triangleBlur(a[0])
                "zoomBlur" -> c.zoomBlur(a[0], a[1], a[2])
                "hexagonalPixelate" -> c.hexagonalPixelate(a[0], a[1], a[2])
                "ink" -> c.ink(a[0])
                "hueSaturation" -> c.hueSaturation(a[0], a[1])
                "edgeWork" -> c.edgeWork(a[0])
                "brightnessContrast" -> c.brightnessContrast(a[0], a[1])
                else -> throw IllegalArgumentException("glfx: ${op.name} is not bridged")
            }
        }
        c = c.update()
        stack?.let { val a = it.args; c = c.pancakeStack(a[0], a[1], a[2], a[3]) }
        return c.toBitmap(reuse)
    }
}

object KGlfx {
    /** Robolectric reports FINGERPRINT "robolectric"; everything else has GLES. */
    private val default: GlfxBridge by lazy { if (Build.FINGERPRINT == "robolectric") HeadlessGlfx else GlThreadGlfx() }
    private var override: GlfxBridge? = null
    var bridge: GlfxBridge
        get() = override ?: default
        set(v) { override = v }
}

/** `{}` placeholder of the JS (`tool.textureGfx = {}`) or a real uploaded texture. */
class GfxTexture internal constructor(private val bridge: GlfxBridge, private var handle: Any?) {
    fun destroy() {
        handle?.let { bridge.destroyTexture(it) }
        handle = null
    }

    internal fun handle(): Any = handle ?: throw IllegalStateException("glfx: texture was destroyed")
}

/** `fx.canvas()`: the WebGL canvas element glfx returns; drawImage-able after update(). */
class GfxCanvas internal constructor(private val bridge: GlfxBridge) : CanvasImageSource {
    private var source: GfxTexture? = null
    private val ops = ArrayList<GfxOp>()
    private var result: Bitmap? = null

    override val width: Int get() = result?.width ?: 0
    override val height: Int get() = result?.height ?: 0
    override fun sourceBitmap(): Bitmap? = result

    fun texture(element: CanvasImageSource): GfxTexture {
        val bmp = element.sourceBitmap() ?: throw IllegalArgumentException("glfx: empty texture source")
        return GfxTexture(bridge, bridge.texture(bmp))
    }

    fun draw(texture: GfxTexture): GfxCanvas {
        source = texture
        ops.clear()
        return this
    }

    private fun op(name: String, vararg args: Double): GfxCanvas {
        ops.add(GfxOp(name, args))
        return this
    }

    fun bulgePinch(centerX: Double, centerY: Double, radius: Double, strength: Double) = op("bulgePinch", centerX, centerY, radius, strength)
    fun swirl(centerX: Double, centerY: Double, radius: Double, angle: Double) = op("swirl", centerX, centerY, radius, angle)
    fun lensBlur(radius: Double, brightness: Double, angle: Double) = op("lensBlur", radius, brightness, angle)
    fun triangleBlur(radius: Double) = op("triangleBlur", radius)
    fun zoomBlur(centerX: Double, centerY: Double, strength: Double) = op("zoomBlur", centerX, centerY, strength)
    fun hexagonalPixelate(centerX: Double, centerY: Double, scale: Double) = op("hexagonalPixelate", centerX, centerY, scale)
    fun ink(strength: Double) = op("ink", strength)
    fun hueSaturation(hue: Double, saturation: Double) = op("hueSaturation", hue, saturation)
    fun edgeWork(radius: Double) = op("edgeWork", radius)
    fun brightnessContrast(brightness: Double, contrast: Double) = op("brightnessContrast", brightness, contrast)

    /** Kid Pix's Pancake Stack on the result (FxCanvas.pancakeStack), applied after the filters. */
    fun pancakeStack(n: Double, increment: Double, dirX: Double, dirY: Double) = op("pancakeStack", n, increment, dirX, dirY)

    /**
     * update() without waiting (GlfxBridge.renderAsync): [done] gets the picture later, valid only
     * inside the callback. The chain recorded so far is taken now.
     */
    fun updateAsync(done: (CanvasImageSource) -> Unit) {
        val tex = source ?: throw IllegalStateException("glfx: update() before draw()")
        bridge.renderAsync(tex.handle(), ops.toList()) { bmp -> done(dev.kidpix.paint.canvas.BitmapSource(bmp)) }
    }

    fun update(): GfxCanvas {
        val tex = source ?: throw IllegalStateException("glfx: update() before draw()")
        result = bridge.render(tex.handle(), ops.toList(), result)
        return this
    }
}

/** The `fx` global of glfx.js. */
@Suppress("ClassName")
object fx {
    fun canvas(): GfxCanvas = GfxCanvas(KGlfx.bridge)
}
