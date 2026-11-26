package dev.kidpix.paint.canvas

import android.graphics.Bitmap

/** Anything drawImage/createPattern accepts: KCanvas, KImage, or a wrapped Bitmap. */
interface CanvasImageSource {
    val width: Int
    val height: Int

    /** Current pixels, or null when the source is empty/not loaded (drawImage is then a no-op). */
    fun sourceBitmap(): Bitmap?
}

class BitmapSource(val bitmap: Bitmap) : CanvasImageSource {
    override val width get() = bitmap.width
    override val height get() = bitmap.height
    override fun sourceBitmap() = bitmap
}

/**
 * `document.createElement('canvas')`: an ARGB_8888 software bitmap with one Ctx2D.
 * Setting width or height (even to the same value) clears the pixels and resets the context
 * state, as in the browser. Default size 300x150 like the web.
 */
class KCanvas(width: Int = 300, height: Int = 150) : CanvasImageSource {
    private var w = width.coerceAtLeast(0)
    private var h = height.coerceAtLeast(0)

    /** The backing bitmap. Replaced when the canvas is resized; never hold it across a resize. */
    var bitmap: Bitmap = newBitmap(w, h)
        private set

    private var ctx: Ctx2D? = null

    /** DOM-ish extras some tools stash on canvases. */
    var id: String = ""
    var className: String = ""

    override var width: Int
        get() = w
        set(v) {
            w = v.coerceAtLeast(0); reset()
        }

    override var height: Int
        get() = h
        set(v) {
            h = v.coerceAtLeast(0); reset()
        }

    /** Sets both at once with a single reallocation. */
    fun setSize(width: Int, height: Int) {
        w = width.coerceAtLeast(0); h = height.coerceAtLeast(0); reset()
    }

    private fun reset() {
        // ink is known only if nothing wrote behind Ctx2D's back since the last look
        val known = trackDirty && bitmap.generationId == cleanGen
        if (bitmap.width == w.coerceAtLeast(1) && bitmap.height == h.coerceAtLeast(1)) {
            bitmap.eraseColor(0)
            if (known) dirty.union(ink) else dirty.set(0, 0, w, h)
        } else {
            bitmap = newBitmap(w, h)
            dirty.set(0, 0, w, h)
        }
        ctx?.rebind()
        ink.setEmpty() // erased (or new): transparent everywhere
        cleanGen = bitmap.generationId
    }

    // ---- what changed since the app last looked (its low-latency pen layer redraws only that).
    // Ctx2D reports the device box of each draw. A write that bypasses Ctx2D (setPixels on the
    // bitmap, History) shows as a generation id the last draw did not leave: then all is dirty.
    private val dirty = android.graphics.Rect()
    /** Bound of the pixels that may not be transparent: clearing outside it changes nothing. */
    private val ink = android.graphics.Rect()
    private var cleanGen = bitmap.generationId
    private val hit = android.graphics.Rect()

    /** Before a Ctx2D draw: was the bitmap changed behind Ctx2D's back since the last one? */
    internal fun beforeDraw() {
        if (trackDirty && bitmap.generationId != cleanGen) markAllDirty()
    }

    /** After a Ctx2D draw that may have changed the device box l,t,r,b (any order of floats). */
    internal fun markDirty(l: Float, t: Float, r: Float, b: Float) {
        if (!trackDirty) return
        val il = kotlin.math.floor(l).toInt(); val it = kotlin.math.floor(t).toInt()
        val ir = kotlin.math.ceil(r).toInt(); val ib = kotlin.math.ceil(b).toInt()
        dirty.union(il, it, ir, ib)
        ink.union(il, it, ir, ib)
        cleanGen = bitmap.generationId
    }

    /**
     * After a clear of device box l,t,r,b: only where there was ink changed. [whole] = no clip was
     * active, so all ink inside the box is gone (tmp/preview are cleared whole on every move).
     */
    internal fun markCleared(l: Float, t: Float, r: Float, b: Float, whole: Boolean) {
        if (!trackDirty) return
        hit.set(kotlin.math.floor(l).toInt(), kotlin.math.floor(t).toInt(), kotlin.math.ceil(r).toInt(), kotlin.math.ceil(b).toInt())
        val covers = whole && hit.contains(ink)
        if (hit.intersect(ink)) dirty.union(hit)
        if (covers) ink.setEmpty()
        cleanGen = bitmap.generationId
    }

    /** Known to be transparent everywhere (only with [trackDirty]; false means "maybe not"). */
    val knownBlank: Boolean get() = trackDirty && ink.isEmpty && bitmap.generationId == cleanGen

    fun markAllDirty() {
        if (!trackDirty) return
        dirty.set(0, 0, w, h)
        ink.set(0, 0, w, h)
        cleanGen = bitmap.generationId
    }

    /** Moves the box changed since the last call into [out] (clipped to the canvas); false if none. */
    fun takeDirty(out: android.graphics.Rect): Boolean {
        if (bitmap.generationId != cleanGen) { dirty.set(0, 0, w, h); ink.set(0, 0, w, h) }
        cleanGen = bitmap.generationId
        hit.set(0, 0, w, h)
        val any = !dirty.isEmpty && out.setIntersect(dirty, hit)
        dirty.setEmpty()
        if (!any) out.setEmpty()
        return any
    }

    fun getContext(type: String = "2d"): Ctx2D {
        require(type == "2d") { "only 2d contexts" }
        return ctx ?: Ctx2D(this).also { ctx = it }
    }

    override fun sourceBitmap(): Bitmap? { ctx?.flushBatch(); return if (w == 0 || h == 0) null else bitmap }

    /** A detached copy of the pixels (what toDataURL+Image round trip gives in the JS). */
    fun snapshot(): Bitmap { ctx?.flushBatch(); return bitmap.copy(Bitmap.Config.ARGB_8888, true) }

    companion object {
        private fun newBitmap(w: Int, h: Int): Bitmap =
            Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)

        /** `document.createElement('canvas')` with a size. */
        fun create(width: Int, height: Int) = KCanvas(width, height)

        /** Dirty boxes are kept only when the app asks (PaintView); the headless harness never does. */
        @JvmField var trackDirty = false
    }
}

/**
 * `new Image()`. Loads synchronously from [KAssets] when [src] is set; [onload] is then called
 * through a zero-delay timer, like the browser's async load. `complete` is true once loaded.
 */
class KImage() : CanvasImageSource {
    constructor(bitmap: Bitmap) : this() {
        this.bmp = bitmap; complete = true
    }

    private var bmp: Bitmap? = null
    var complete = false
        private set
    var onload: (() -> Unit)? = null
    var onerror: (() -> Unit)? = null
    var crossOrigin: String? = null

    var src: String = ""
        set(v) {
            field = v
            bmp = KAssets.loadBitmap(v)
            complete = bmp != null
            dev.kidpix.paint.engine.KTime.setTimeout({ if (bmp != null) onload?.invoke() else onerror?.invoke() }, 0)
        }

    override val width get() = bmp?.width ?: 0
    override val height get() = bmp?.height ?: 0
    override fun sourceBitmap() = bmp
}
