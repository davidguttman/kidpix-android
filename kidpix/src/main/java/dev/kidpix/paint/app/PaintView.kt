package dev.kidpix.paint.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.SparseIntArray
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.PEvent
import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.log.Action
import dev.kidpix.paint.log.KLog
import dev.kidpix.paint.log.RecordCoder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Shows the five layers (main, bnim, anim, preview, tmp, bottom to top) inside a 1 px blue
 * border, like #paint in the web page, and turns MotionEvents into the Pointer Events Chromium
 * would have given the overlay (KAndroid.pointerdown/move/up/cancel/leave). The canvas has one
 * pixel per CSS px (dp), scaled up nearest-neighbour as the WebView's pixelated canvas was.
 *
 * While a contact is down, the newest engine output also goes to a front-buffered layer on top
 * ([FrontInk]): the box of the layers that changed since the last piece, composited exactly as
 * [onDraw] does, so the ink shows without waiting for the next View frame. At pen-up the layer
 * hides in the frame where [onDraw] shows the finished stroke. When the layer is unavailable or
 * fails, this View path alone shows everything (only the latency differs).
 */
class PaintView(context: Context, private val onCanvasSize: (Int, Int) -> Unit) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val pixelPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    /** The 1 CSS px border as Chrome paints it: floored to whole device pixels (1 at 1.5x). */
    internal val borderPx = maxOf(1, floor(density).toInt())
    private val border = Paint().apply { color = Color.rgb(0, 0, 255); style = Paint.Style.STROKE; strokeWidth = borderPx.toFloat() }
    private val dst = Rect()

    /** Android pointer id -> pointerId of the contact (a fresh one per contact, like Chromium). */
    private val contacts = SparseIntArray()
    private var nextPointerId = 2
    /** Pointer Events for hovering pens/mice share one id that no contact uses. */
    private val hoverId = 1

    /** Tests only: draw this picture instead of the layers (shows what the front layer alone adds). */
    var testFrozen: Bitmap? = null

    /** The front-buffered pen layer, or null: not wanted, not available, or failed once. */
    var front: FrontInk? = null
        private set

    /**
     * The stroke in progress started with a pen. Auto mode measures pen strokes only: fingers come
     * once per frame anyway, and on the tablet a session that began with finger strokes measured
     * the two paths alike and dropped the layer that pens gain ~10 ms from.
     */
    private var penStroke = false

    /**
     * The UI is turned inside a portrait window ([LandscapeFrame]). A SurfaceView doesn't follow
     * View rotation, so the front layer sits out (surface gone) and the View path draws alone.
     */
    var turned = false
        set(v) {
            if (field == v) return
            field = v
            front?.let { f -> if (v) { if (f.active) f.endStroke() }; f.surface.visibility = if (v) GONE else VISIBLE }
            invalidate()
        }

    /** No forced mode: keep the layer only if it shows ink sooner than the View path did here. */
    private var autoDecide = true
    /** Auto mode: 0 = View-only baseline strokes, 1 = layer on trial, 2 = decided. */
    private var autoPhase = 0
    private var strokesSeen = 0
    private var viewBase: LatencyStats.Summary? = null
    /** The layer takes strokes (forced on, or auto past its baseline). */
    private var frontLive = true

    init {
        setBackgroundColor(Color.WHITE)
        setWillNotDraw(false)
        isHapticFeedbackEnabled = false
        val intent = (context as? Activity)?.intent
        if (intent?.getBooleanExtra("perfLog", false) == true) {
            StrokePerf.enabled = true
            dev.kidpix.paint.canvas.Ctx2D.batchTimer = { block, flush -> android.util.Log.i("KidPixPerf", "batch strokes=%.2f writeback=%.2f".format(block / 1e6, flush / 1e6)) }
        }
        if (intent?.hasExtra("chromeStrokes") == true) dev.kidpix.paint.canvas.Ctx2D.chromeStrokes = intent.getBooleanExtra("chromeStrokes", true)
        val forced = lowLatency ?: if (intent?.hasExtra(EXTRA_LOW_LATENCY) == true) intent.getBooleanExtra(EXTRA_LOW_LATENCY, true) else null
        autoDecide = forced == null
        frontLive = forced == true
        if (forced != false) {
            LatencyStats.reset()
            front = try {
                FrontInk(this) { frontFailed() }.also {
                    addView(it.surface, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            } catch (t: Throwable) {
                Log.e("KidPix", "no front-buffered pen layer", t); null
            }
        }
        if (front != null) layerUp(+1)
        KCanvas.trackDirty = true // also for skipping blank layers in onDraw
    }

    /**
     * After a few strokes, compare the two paths on this device: per event, time to the front
     * piece's transaction vs time to the end of the View frame that drew it. Both are measured up
     * to the hand-over to the compositor; after that the View frame still waits for its latch and
     * a front buffer (where supported) does not, so this errs toward keeping the View path. If the
     * layer is not ahead by at least [MIN_GAIN_MS] at the median, drop it (logged; View path only).
     */
    private fun judgeFront() {
        if (!autoDecide) return
        strokesSeen++
        when (autoPhase) {
            0 -> { // the View path alone: what the layer has to beat (the layer costs the View path too)
                val vw = LatencyStats.view()
                if (strokesSeen >= 2 && vw.n >= 100) {
                    viewBase = vw; LatencyStats.reset(); frontLive = true; autoPhase = 1
                }
            }
            1 -> {
                val fr = LatencyStats.front(); val base = viewBase ?: return
                if (fr.n < 150) return
                autoPhase = 2; autoDecide = false
                val keep = fr.p50 + MIN_GAIN_MS <= base.p50
                Log.i("KidPix", "pen layer check: front $fr; View path alone $base; true front buffer ${FrontInk.trueFrontBuffer}: ${if (keep) "keeping it" else "View path only"}")
                if (!keep) front?.let { it.release(); frontFailed() }
            }
        }
    }

    /** A contact is down on the canvas (a stroke in progress). */
    val contactDown get() = contacts.size() > 0

    /** Auto mode's verdict so far: null while it measures, then true (kept) or false (View path only). */
    val frontVerdict: Boolean? get() = if (autoDecide) null else front != null

    /** The layer failed (it logged why): the View path alone from now on. */
    private fun frontFailed() {
        val f = front ?: return
        front = null
        layerUp(-1)
        runCatching { removeView(f.surface) }
        invalidate()
    }

    /**
     * Canvas pixels for a view of this size: floor(dp) minus the CSS border. In a portrait window
     * the canvas keeps the size it has in landscape (the window turned, the side bars as wide), so
     * turning the tablet never resizes the picture: on tablets with `fixed-to-user-rotation` the
     * system ignores sensorLandscape and turns the app with the tablet. The view then shows the
     * landscape canvas shrunk to fit ([fitScale]).
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        front?.let { if (!it.isShown) it.hide() }
        val (cw, ch) = canvasSizeFor(w, h, rootView.width, rootView.height, density)
        post { onCanvasSize(cw, ch) } // not during layout: starting the engine builds the option bar
    }

    private fun layers() = listOf(KDisplay.main_canvas, KDisplay.bnimCanvas, KDisplay.animCanvas, KDisplay.previewCanvas, KDisplay.canvas)

    /**
     * Device px per canvas px on screen: [density] (one per CSS px, as the WebView app), or less
     * when the canvas does not fit at that size (a landscape canvas in a portrait window).
     */
    private fun fitScale(): Float {
        if (!KDisplay.isReady || width == 0 || height == 0) return density
        val m = KDisplay.main_canvas
        return minOf(density, (width - 2f * borderPx) / m.width, (height - 2f * borderPx) / m.height)
    }

    /** CSS px of the WebView page per canvas px on screen: 1, or more when shrunk to fit. */
    private var shrink = 1f

    /**
     * Where the canvas goes in this view: right inside the border, density px per canvas px (the
     * WebView app paints it there: at 1.5x, from device pixel 1). Touches still map like the web
     * page's CSS pixels (border 1 dp), as in the WebView app.
     */
    private fun canvasRect(out: Rect) {
        val m = KDisplay.main_canvas
        val s = fitScale()
        shrink = density / s
        val left = borderPx
        out.set(left, left, left + (m.width * s).roundToInt(), left + (m.height * s).roundToInt())
    }

    /** View px -> the page's CSS px (clientX/Y), the canvas starting at 1 after the border. */
    private fun client(v: Float): Double =
        if (shrink == 1f) (v / density).toDouble() else ((v / density - 1f) * shrink + 1f).toDouble()

    /** The page's CSS px (clientX/Y) -> view px; the inverse of [client]. */
    fun viewPx(client: Double): Float =
        if (shrink == 1f) (client * density).toFloat() else (((client - 1) / shrink + 1) * density).toFloat()

    /** Shrunk to fit: a contact that starts beside the canvas is not a stroke (the page had no canvas there). */
    private fun besideCanvas(e: MotionEvent, i: Int): Boolean {
        if (shrink == 1f) return false
        canvasRect(dst)
        return !dst.contains(e.getX(i).toInt(), e.getY(i).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (!KDisplay.isReady) return
        val td = System.nanoTime()
        drawFrame(c)
        StrokePerf.draw(System.nanoTime() - td)
    }

    private fun drawFrame(c: Canvas) {
        canvasRect(dst)
        val bh = borderPx / 2f
        c.drawRect(dst.left - bh, dst.top - bh, dst.right + bh, dst.bottom + bh, border)
        val frozen = testFrozen
        if (frozen != null) c.drawBitmap(frozen, null, dst, pixelPaint)
        else for (layer in layers()) {
            // a transparent layer adds nothing; skipping it saves a canvas-sized texture draw (the
            // GPU was the bottleneck: five full-screen layers a frame) and its upload
            if (layer !== KDisplay.main_canvas && layer.knownBlank) continue
            c.drawBitmap(layer.bitmap, null, dst, pixelPaint)
        }
        frameEvents(drawingTime)
        // a stroke in progress also gets what timers changed (spray held still, animations)
        if (front?.active == true) safe("front") { presentWet(0L) }
    }

    // ------------------------------------------------------------------ front-buffered pen layer

    private val box = Rect()
    private val layerBox = Rect()
    private val pieceDst = Rect()
    private val pieceRect = Rect()

    /** Forget what changed before this stroke: the layer starts transparent over the View. */
    private fun drainDirty() { for (l in layers()) l.takeDirty(layerBox) }

    /** The engine composite of everything changed since the last piece, to the front layer. */
    private fun presentWet(eventNanos: Long) {
        val f = front ?: return
        if (!f.active || !KDisplay.isReady) return
        val t0 = System.nanoTime()
        box.setEmpty()
        val ls = layers()
        for (l in ls) if (l.takeDirty(layerBox)) box.union(layerBox)
        if (box.isEmpty) return
        val mc = KDisplay.main_canvas
        if (box.width().toLong() * box.height() * BIG_PIECE > mc.width.toLong() * mc.height) {
            // A tool that redraws much of the picture per move (line and shape previews, kaleidoscope,
            // whole-picture mixers): a piece that size costs more on the main thread than the layer
            // saves (on the tablet ~16 ms per pen event against ~9 without the layer). The layer
            // steps aside for the rest of this stroke, in step with the View frame that shows it all.
            f.endStroke()
            return
        }
        val bmp = Bitmap.createBitmap(box.width(), box.height(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE) // the view's background, under main as in onDraw
        pieceRect.set(0, 0, box.width(), box.height())
        for (l in ls) c.drawBitmap(l.bitmap, box, pieceRect, null)
        canvasRect(pieceDst)
        val m = KDisplay.main_canvas
        f.present(bmp, box.left, box.top, pieceDst.left.toFloat(), pieceDst.top.toFloat(),
            pieceDst.width().toFloat() / m.width, pieceDst.height().toFloat() / m.height, eventNanos)
        LatencyStats.compose(System.nanoTime() - t0)
    }

    private fun eventNanos(e: MotionEvent): Long =
        if (Build.VERSION.SDK_INT >= 34) e.eventTimeNanos else e.eventTime * 1_000_000

    // ------------------------------------------------------------------ View-path latency (FrameMetrics)

    private val undrawn = ArrayList<Long>()
    private val drawnIn = HashMap<Long, LongArray>()
    private var window: Window? = null
    private val metrics = Window.OnFrameMetricsAvailableListener { _, fm, _ ->
        val key = fm.getMetric(FrameMetrics.VSYNC_TIMESTAMP) / 1_000_000
        val evs = drawnIn.remove(key) ?: return@OnFrameMetricsAvailableListener
        val end = fm.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) + fm.getMetric(FrameMetrics.TOTAL_DURATION)
        for (t in evs) LatencyStats.view(end - t)
        if (drawnIn.size > 64) drawnIn.clear()
    }

    /** Events handled since the last frame were drawn in the frame of [frameMs] (FrameMetrics resolves it). */
    private fun frameEvents(frameMs: Long) {
        if (undrawn.isEmpty()) return
        drawnIn[frameMs] = (drawnIn[frameMs] ?: LongArray(0)) + undrawn.toLongArray()
        undrawn.clear()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        window = (context as? Activity)?.window
        runCatching { window?.addOnFrameMetricsAvailableListener(metrics, Handler(Looper.getMainLooper())) }
    }

    override fun onDetachedFromWindow() {
        runCatching { window?.removeOnFrameMetricsAvailableListener(metrics) }
        window = null
        front?.let { f -> front = null; layerUp(-1); f.release() }
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ MotionEvent -> PEvent

    private fun pointerType(e: MotionEvent, i: Int) = when (e.getToolType(i)) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> "pen"
        MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
        else -> "touch"
    }

    /** Chromium's `buttons`: contact 1 (eraser end 32), barrel 2, second barrel 4; mice their own. */
    private fun buttons(e: MotionEvent, i: Int, contact: Boolean): Int {
        val bs = e.buttonState
        return when (e.getToolType(i)) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> {
                var b = 0
                if (contact) b = b or if (e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER) 32 else 1
                if (bs and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0) b = b or 2
                if (bs and (MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_TERTIARY) != 0) b = b or 4
                b
            }
            MotionEvent.TOOL_TYPE_MOUSE -> {
                var b = 0
                if (bs and MotionEvent.BUTTON_PRIMARY != 0) b = b or 1
                if (bs and MotionEvent.BUTTON_SECONDARY != 0) b = b or 2
                if (bs and MotionEvent.BUTTON_TERTIARY != 0) b = b or 4
                if (contact && b == 0) b = 1
                b
            }
            else -> if (contact) 1 else 0
        }
    }

    /** Which mouse button went down (0 left, 1 middle, 2 right). */
    private fun mouseButton(e: MotionEvent): Int = when {
        Build.VERSION.SDK_INT >= 23 && e.actionButton == MotionEvent.BUTTON_SECONDARY -> 2
        Build.VERSION.SDK_INT >= 23 && e.actionButton == MotionEvent.BUTTON_TERTIARY -> 1
        e.buttonState and MotionEvent.BUTTON_PRIMARY == 0 && e.buttonState and MotionEvent.BUTTON_SECONDARY != 0 -> 2
        else -> 0
    }

    private fun pev(
        e: MotionEvent, i: Int, id: Int, contact: Boolean, primary: Boolean = true,
        x: Float = e.getX(i), y: Float = e.getY(i), pressure: Float = e.getPressure(i), time: Long = e.eventTime,
        coalesced: List<PEvent>? = null, button: Int = 0,
    ): PEvent {
        val type = pointerType(e, i)
        return PEvent(
            pointerId = id, pointerType = type,
            // on the action log's grid (1/16 px, 1/1024 pressure): what the engine sees is what is logged
            clientX = RecordCoder.quantXY(client(x)), clientY = RecordCoder.quantXY(client(y)),
            pressure = RecordCoder.quantPressure(if (contact || type != "pen") pressure.coerceIn(0f, 1f).toDouble() else 0.0),
            buttons = buttons(e, i, contact), isPrimary = primary, button = button,
            timeStamp = time.toDouble(), coalesced = coalesced,
        )
    }

    /** Every sample since the last event for pointer index [i] (getCoalescedEvents). */
    private fun coalesced(e: MotionEvent, i: Int, id: Int, contact: Boolean): List<PEvent> {
        val out = ArrayList<PEvent>(e.historySize + 1)
        for (h in 0 until e.historySize) {
            out.add(pev(e, i, id, contact, x = e.getHistoricalX(i, h), y = e.getHistoricalY(i, h),
                pressure = e.getHistoricalPressure(i, h), time = e.getHistoricalEventTime(h)))
        }
        out.add(pev(e, i, id, contact))
        return out
    }

    /** Engine errors behave like JS exceptions in an event listener: logged, the app goes on. */
    private inline fun safe(what: String, f: () -> Unit) {
        try { f() } catch (t: Throwable) { Log.e("KidPix", "$what failed", t) }
    }

    private val pendingLeave = Runnable { lastHover?.let { e -> safe("pointerleave") { KLog.entry(Action.Pointer(Action.LEAVE, e)) } }; postInvalidateOnAnimation() }
    private var lastHover: PEvent? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!KDisplay.isReady) return false
        val down = e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        if (down && besideCanvas(e, e.actionIndex)) return true // its moves and up find no contact
        LatencyStats.input(System.nanoTime() - eventNanos(e))
        val f = front
        // a first contact: the front layer starts this stroke transparent
        if (f != null && frontLive && !turned && contacts.size() == 0 && e.actionMasked == MotionEvent.ACTION_DOWN) {
            safe("front") { drainDirty(); f.beginStroke() }
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                // Pens: deliver every sample immediately instead of batching to vsync.
                if (e.actionMasked == MotionEvent.ACTION_DOWN) penStroke = pointerType(e, i) == "pen"
                if (e.actionMasked == MotionEvent.ACTION_DOWN && pointerType(e, i) == "pen") requestUnbufferedDispatch(e)
                if (e.actionMasked == MotionEvent.ACTION_DOWN) StrokePerf.begin()
                removeCallbacks(pendingLeave) // Android ends the hover right before a pen touches down
                val id = nextPointerId++
                contacts.put(e.getPointerId(i), id)
                val primary = e.actionMasked == MotionEvent.ACTION_DOWN || pointerType(e, i) == "pen"
                val button = if (pointerType(e, i) == "mouse") mouseButton(e) else 0
                val t0 = System.nanoTime()
                safe("pointerdown") { KLog.entry(Action.Pointer(Action.DOWN, pev(e, i, id, true, primary, button = button))) }
                if (e.actionMasked == MotionEvent.ACTION_DOWN) StrokePerf.down(System.nanoTime() - t0)
            }
            MotionEvent.ACTION_MOVE -> {
                val t0 = System.nanoTime()
                for (i in 0 until e.pointerCount) {
                    val id = contacts.get(e.getPointerId(i), -1)
                    if (id < 0) continue
                    safe("pointermove") { KLog.entry(Action.Pointer(Action.MOVE, pev(e, i, id, true, coalesced = coalesced(e, i, id, true)))) }
                }
                StrokePerf.move(System.nanoTime() - t0)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val pid = e.getPointerId(i)
                val id = contacts.get(pid, -1)
                contacts.delete(pid)
                if (id >= 0) {
                    val p = pev(e, i, id, false)
                    val cancelled = Build.VERSION.SDK_INT >= 33 && (e.flags and MotionEvent.FLAG_CANCELED) != 0
                    val t0 = System.nanoTime()
                    safe("pointerup") { KLog.entry(Action.Pointer(if (cancelled) Action.CANCEL else Action.UP, p)) }
                    if (contacts.size() == 0) StrokePerf.up(System.nanoTime() - t0)
                }
                if (contacts.size() == 0) {
                    StrokePerf.end(dev.kidpix.paint.KCurrent.tool?.javaClass?.simpleName ?: "?", f != null && frontLive)
                    // perfLog: the latency so far in this process (both paths), as auto mode judges it
                    if (StrokePerf.enabled) Log.i("KidPixPerf", "log records ${KLog.records} %.4f ms/record; moves ${KLog.moves} %.4f ms/move max %.3f; ${KLog.describe()}".format(if (KLog.records > 0) KLog.recordNs / 1e6 / KLog.records else 0.0, if (KLog.moves > 0) KLog.moveNs / 1e6 / KLog.moves else 0.0, KLog.moveMaxNs / 1e6))
                    if (StrokePerf.enabled) Log.i("KidPixPerf", "latency front ${LatencyStats.front()} view ${LatencyStats.view()} input ${LatencyStats.input()} render ${LatencyStats.render()}")
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until e.pointerCount) {
                    val id = contacts.get(e.getPointerId(i), -1)
                    if (id >= 0) safe("pointercancel") { KLog.entry(Action.Pointer(Action.CANCEL, pev(e, i, id, false))) }
                }
                contacts.clear()
            }
        }
        if (penStroke && !turned) undrawn.add(eventNanos(e)) // latency is judged on pen strokes only (see judgeFront)
        if (f != null && f === front) {
            if (contacts.size() > 0) safe("front") { presentWet(if (penStroke) eventNanos(e) else 0L) }
            else if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (f.active) f.endStroke() // hides in the frame that draws the finished stroke
                if (penStroke && !turned) judgeFront()
            }
        }
        postInvalidateOnAnimation()
        return true
    }

    /**
     * Hover goes straight to [onHoverEvent], as when this was a plain View: ViewGroup's hover
     * tracking would drop an exit that comes without a fresh enter (the front layer's SurfaceView
     * child never takes hover or touch).
     */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean = onHoverEvent(event)

    /** A hovering pen (or mouse) moves with no contact; leaving the canvas is pointerleave. */
    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (!KDisplay.isReady) return super.onHoverEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                removeCallbacks(pendingLeave)
                val p = pev(e, 0, hoverId, false, coalesced = coalesced(e, 0, hoverId, false))
                lastHover = p
                safe("pointermove") { KLog.entry(Action.Pointer(Action.MOVE, p)) }
            }
            // Android also sends this right before a pen touches down; a down within a few ms cancels it.
            MotionEvent.ACTION_HOVER_EXIT -> {
                lastHover = pev(e, 0, hoverId, false)
                removeCallbacks(pendingLeave)
                postDelayed(pendingLeave, 60)
            }
        }
        postInvalidateOnAnimation()
        return true
    }

    /** Mouse wheel / pen scroll: upstream's mousewheel listener. */
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (KDisplay.isReady && e.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) { safe("wheel") { KLog.entry(Action.Wheel(v.toDouble())) }; postInvalidateOnAnimation(); return true }
        }
        return super.onGenericMotionEvent(e)
    }

    companion object {
        /**
         * `am start ... --ez lowLatency false|true`: View path only, or the front layer kept no
         * matter what it measures (to compare by hand). Without it (auto): 2 strokes measure the
         * View path, the next ones the layer, which stays only if it was faster (logcat KidPix).
         */
        const val EXTRA_LOW_LATENCY = "lowLatency"

        /** Tests: force the front layer on/off for views created from now on (null: the intent decides). */
        @JvmStatic var lowLatency: Boolean? = null

        /** Live front layers (an old view may detach after a new one starts): dirty boxes kept while > 0. */
        private var liveLayers = 0
        private fun layerUp(d: Int) { liveLayers = (liveLayers + d).coerceAtLeast(0); KCanvas.trackDirty = true }

        /**
         * Canvas size (CSS px) for a PaintView of [w]x[h] px in a window of [rootW]x[rootH] px: the
         * view's floor(dp) minus the border; in a portrait window, what it would be with the window
         * turned to landscape (the bars beside the view keep their width).
         */
        @JvmStatic fun canvasSizeFor(w: Int, h: Int, rootW: Int, rootH: Int, density: Float): Pair<Int, Int> {
            var vw = w; var vh = h
            if (rootH > rootW && rootW > 0 && rootW >= w) { vw = rootH - (rootW - w); vh = rootW }
            val cw = (floor(vw / density) - 2).toInt().coerceAtLeast(320)
            val ch = (floor(vh / density) - 2).toInt().coerceAtLeast(240)
            return cw to ch
        }

        /** A piece over 1/BIG_PIECE of the canvas ends the layer's part in the stroke ([presentWet]). */
        const val BIG_PIECE = 4

        /** The layer must beat the View path by this much at the median to stay (auto mode). */
        const val MIN_GAIN_MS = 2.0
    }
}
