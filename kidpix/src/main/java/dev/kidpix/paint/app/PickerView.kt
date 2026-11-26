package dev.kidpix.paint.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ScrollView
import java.util.concurrent.Executors

/**
 * The wordless open-a-picture picker of the WebView build (#kp-picker in overlay/android.css):
 * white page over everything, a grid of thumbnails (4 px black frame, 6 px grey shadow, 4:3),
 * a red X top right, the faded folder icon when there is nothing saved yet. Sizes in CSS px = dp.
 */
@SuppressLint("ViewConstructor")
class PickerView(
    context: Context,
    private val pictures: Pictures,
    private val emptyIcon: Bitmap?,
    private val onClose: () -> Unit,
    private val onPick: (Long) -> Unit,
) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Number) = (v.toFloat() * d + 0.5f).toInt()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val grid = GridLayout(context)
    private val scroll = ScrollView(context)
    @Volatile private var closed = false
    val thumbs = ArrayList<Thumb>()
    var empty = false
        private set

    init {
        setBackgroundColor(Color.WHITE)
        isClickable = true // nothing reaches the drawing underneath
        scroll.isVerticalScrollBarEnabled = false
        scroll.setPadding(dp(24), dp(24), dp(96), dp(24))
        scroll.clipToPadding = false
        scroll.addView(grid)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(CloseButton(context).apply { setOnClickListener { onClose() } },
            LayoutParams(dp(64 + 4), dp(64 + 4)).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = dp(16); rightMargin = dp(16) - dp(4)
            })
        io.execute {
            val list = runCatching { pictures.list() }.getOrNull()
            main.post { if (!closed) show(list) }
        }
    }

    fun close() {
        closed = true
        io.shutdownNow()
    }

    private fun show(list: List<Pictures.Pic>?) {
        if (list.isNullOrEmpty()) {
            empty = true
            if (list != null && emptyIcon != null) {
                addView(EmptyIcon(context, emptyIcon), 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            return
        }
        if (width == 0) { post { if (!closed) show(list) }; return }
        // grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 20px
        val avail = width / d - 24 - 96
        val cols = maxOf(1, ((avail + 20) / (230 + 20)).toInt())
        val cellW = (avail - 20 * (cols - 1)) / cols
        grid.columnCount = cols
        list.forEachIndexed { i, pic ->
            val t = Thumb(context, pic.id)
            t.setOnClickListener { onPick(pic.id) }
            // the card fills the cell (aspect-ratio 4/3 on the content box, inside the 4 px
            // border); the view is 6 px larger for the box-shadow, which CSS hangs outside
            grid.addView(t, GridLayout.LayoutParams().apply {
                width = dp(cellW + 6); height = dp((cellW - 8) * 3 / 4 + 8 + 6)
                if (i % cols != 0) leftMargin = dp(20 - 6)
                if (i >= cols) topMargin = dp(20 - 6)
            })
            thumbs.add(t)
            io.execute {
                val b = runCatching { pictures.thumb(pic.id) }.getOrNull()
                main.post { if (!closed) t.bitmap = b }
            }
        }
    }

    /** .kp-thumb: white, 4 px black border, 6 px #888 shadow (pressed: moves 3 px into it). */
    inner class Thumb(context: Context, val id: Long) : View(context) {
        var bitmap: Bitmap? = null; set(v) { field = v; invalidate() }
        private val p = Paint(Paint.FILTER_BITMAP_FLAG)
        private val fill = Paint()

        init { setWillNotDraw(false) }

        override fun setPressed(pressed: Boolean) { super.setPressed(pressed); invalidate() }

        override fun onDraw(c: Canvas) {
            val sh = if (isPressed) 3 * d else 6 * d
            val off = if (isPressed) 3 * d else 0f
            val w = width - 6 * d; val h = height - 6 * d // the card; the last 6 px are for the shadow
            fill.color = 0xff888888.toInt()
            c.drawRect(off + sh, off + sh, off + w + sh, off + h + sh, fill)
            fill.color = Color.BLACK
            c.drawRect(off, off, off + w, off + h, fill)
            fill.color = Color.WHITE
            val b4 = 4 * d
            c.drawRect(off + b4, off + b4, off + w - b4, off + h - b4, fill)
            bitmap?.let { b -> // object-fit: contain
                val iw = w - 2 * b4; val ih = h - 2 * b4
                val s = minOf(iw / b.width, ih / b.height)
                val dw = b.width * s; val dh = b.height * s
                val l = off + b4 + (iw - dw) / 2; val t = off + b4 + (ih - dh) / 2
                c.drawBitmap(b, null, RectF(l, t, l + dw, t + dh), p)
            }
        }
    }

    /** .kp-close: 64 px red square, 4 px black border, 4 px #888 shadow, white ✕. */
    private inner class CloseButton(context: Context) : View(context) {
        private val fill = Paint()
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 40 * d }
        override fun onDraw(c: Canvas) {
            val s = 64 * d; val sh = 4 * d
            fill.color = 0xff888888.toInt(); c.drawRect(sh, sh, s + sh, s + sh, fill)
            fill.color = Color.BLACK; c.drawRect(0f, 0f, s, s, fill)
            fill.color = 0xffee0000.toInt(); c.drawRect(sh, sh, s - sh, s - sh, fill)
            c.drawText("✕", s / 2, s / 2 - (tp.descent() + tp.ascent()) / 2, tp)
        }
    }

    /** .kp-empty-icon: the folder at 198 px, 35% opacity, pixelated, centered. */
    private class EmptyIcon(context: Context, val b: Bitmap) : View(context) {
        private val p = Paint().apply { isFilterBitmap = false; alpha = (0.35f * 255).toInt() }
        override fun onDraw(c: Canvas) {
            val s = 198 * resources.displayMetrics.density
            val l = (width - s) / 2; val t = (height - s) / 2
            c.drawBitmap(b, null, RectF(l, t, l + s, t + s), p)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}
