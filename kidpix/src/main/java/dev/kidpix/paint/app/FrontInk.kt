package dev.kidpix.paint.app

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.util.Log
import android.view.SurfaceView
import android.view.View
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.graphics.surface.SurfaceControlCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * The wet part of a stroke, on a front-buffered layer above the window (graphics-core's
 * CanvasFrontBufferedRenderer on a SurfaceView that sits on top). PaintView stays the picture:
 * this layer only ever shows pieces of the engine's own composite (white, main, bnim, anim,
 * preview, tmp) that PaintView hands over, never a guess of its own, and only while a contact is
 * down. Outside the pieces drawn in this stroke the layer is transparent, so PaintView shows.
 *
 * Showing and hiding moves the SurfaceView in and out of the view's bounds. A SurfaceView's
 * position changes in the same frame as the View hierarchy that frame draws (the trick graphics-
 * core's LowLatencyCanvasView uses), so at pen-up the layer goes away in exactly the frame in which
 * PaintView first draws the finished stroke: no frame without the ink, none with stale ink. It
 * comes in only once the first piece of the stroke has been rendered (the persisted buffer is
 * cleared first), so the previous stroke's pieces never show.
 *
 * Everything that can fail calls [fail]; PaintView then drops the layer for good (the View path
 * alone, as before). Render-thread callbacks never throw.
 */
class FrontInk(private val host: View, private val onFail: (Throwable) -> Unit) {
    /** One piece: [bitmap] is canvas pixels at canvas [x],[y]; drawn at screen = left + canvas * scale. */
    class Piece(
        /** Dropped once drawn: graphics-core keeps every piece until commit/cancel/clear (see [beginStroke]). */
        @Volatile var bitmap: Bitmap?, val x: Int, val y: Int,
        val left: Float, val top: Float, val sx: Float, val sy: Float,
        val clear: Boolean, val stroke: Int, val eventNanos: Long,
        val requestNanos: Long = System.nanoTime(),
    )

    val surface = SurfaceView(host.context).apply {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        isClickable = false; isFocusable = false
    }

    @Volatile private var broken = false
    private var stroke = 0 // main thread: id of the stroke in progress, 0 between strokes
    private var nextStroke = 1
    private var shown = false
    /** Render thread -> main: the stroke of the last piece rendered. */
    private val renderedStroke = AtomicInteger(0)
    private val pendingEvents = LongArrayList()
    private val pendingRequests = LongArrayList()
    private val pixel = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val m = Matrix()

    /** Pieces presented this stroke and since this layer exists (for tests and the latency log). */
    var presented = 0
        private set

    private val callback = object : CanvasFrontBufferedRenderer.Callback<Piece> {
        override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: Piece) {
            if (broken) return
            try {
                if (param.clear) canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
                val b = param.bitmap ?: return
                param.bitmap = null // the recorded frame holds its own reference until the next piece
                m.setScale(param.sx, param.sy)
                m.postTranslate(param.left + param.x * param.sx, param.top + param.y * param.sy)
                canvas.drawBitmap(b, m, pixel)
                renderedStroke.set(param.stroke)
                synchronized(pendingEvents) { pendingEvents.add(param.eventNanos); pendingRequests.add(param.requestNanos) }
            } catch (t: Throwable) { fail(t) }
        }

        override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<Piece>) {
            // Never used for ink: only the surface's first frame comes here. Keep it see-through.
            try { canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR) } catch (t: Throwable) { fail(t) }
        }

        override fun onFrontBufferedLayerRenderComplete(
            frontBufferedLayerSurfaceControl: SurfaceControlCompat,
            transaction: SurfaceControlCompat.Transaction,
        ) {
            if (broken) return
            try {
                // the transaction is committed right after this returns: count that as "presented"
                val now = System.nanoTime()
                synchronized(pendingEvents) {
                    for (i in 0 until pendingEvents.size) if (pendingEvents[i] != 0L) LatencyStats.front(now - pendingEvents[i])
                    for (i in 0 until pendingRequests.size) LatencyStats.render(now - pendingRequests[i])
                    pendingEvents.clear(); pendingRequests.clear()
                }
                val s = renderedStroke.get()
                host.post { if (!broken && s != 0 && s == stroke && !shown) show() }
            } catch (t: Throwable) { fail(t) }
        }
    }

    private var renderer: CanvasFrontBufferedRenderer<Piece>? = CanvasFrontBufferedRenderer(surface, callback)

    init {
        hide()
        Log.i("KidPix", "front-buffered pen layer on; true front buffer (USAGE_FRONT_BUFFER): $trueFrontBuffer")
    }

    val active get() = stroke != 0

    /** A contact went down on an idle canvas: the next piece clears what the last stroke left. */
    fun beginStroke() {
        if (broken) return
        // graphics-core queues every piece for a multi-buffered redraw that this layer never asks
        // for (no commit): without this the queue held every piece of the session (the tablet ran
        // out of memory after a few hundred strokes). The layer is hidden between strokes, and the
        // next piece shows the front buffer again.
        try { renderer?.cancel() } catch (t: Throwable) { fail(t); return }
        stroke = nextStroke++
        clearNext = true
    }

    private var clearNext = false

    /** Hand over the engine composite [bitmap] of canvas box at [x],[y] (the caller made a fresh bitmap). */
    fun present(bitmap: Bitmap, x: Int, y: Int, left: Float, top: Float, sx: Float, sy: Float, eventNanos: Long) {
        if (broken || stroke == 0) return
        val r = renderer ?: return
        try {
            r.renderFrontBufferedLayer(Piece(bitmap, x, y, left, top, sx, sy, clearNext, stroke, eventNanos))
            clearNext = false
            presented++
        } catch (t: Throwable) { fail(t) }
    }

    /** All contacts are up: hide in the frame that PaintView draws next. */
    fun endStroke() {
        stroke = 0
        hide()
    }

    private fun show() {
        shown = true
        surface.translationX = 0f
        surface.translationY = 0f
    }

    fun hide() {
        shown = false
        // out of the parent's bounds: clipped away, in step with the View frame
        surface.translationX = host.width.toFloat().coerceAtLeast(1f) * 2
        surface.translationY = host.height.toFloat().coerceAtLeast(1f) * 2
    }

    val isShown get() = shown

    fun fail(t: Throwable) {
        if (broken) return
        broken = true
        Log.e("KidPix", "front-buffered pen layer failed; back to the View path", t)
        host.post { release(); onFail(t) }
    }

    fun release() {
        stroke = 0
        runCatching { hide() }
        val r = renderer
        renderer = null
        runCatching { r?.release(true) }
    }

    companion object {
        /**
         * Whether the GPU/composer can scan out a buffer while it is drawn into (API 33+). Without
         * it graphics-core still uses one persisted buffer, but each piece goes through the
         * compositor like a normal frame (skipping only the UI thread's frame and the app's
         * RenderThread), so most of the gain needs this.
         */
        val trueFrontBuffer: Boolean by lazy {
            android.os.Build.VERSION.SDK_INT >= 33 && runCatching {
                android.hardware.HardwareBuffer.isSupported(1, 1, android.hardware.HardwareBuffer.RGBA_8888, 1,
                    android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                        android.hardware.HardwareBuffer.USAGE_COMPOSER_OVERLAY or android.hardware.HardwareBuffer.USAGE_FRONT_BUFFER)
            }.getOrDefault(false)
        }
    }

    /** A tiny growable long list (no boxing on the render thread). */
    private class LongArrayList {
        private var a = LongArray(16)
        var size = 0
            private set
        operator fun get(i: Int) = a[i]
        fun add(v: Long) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun clear() { size = 0 }
    }
}
