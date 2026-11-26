package dev.kidpix.paint.app

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * A web-default-looking <button>: light grey face, Chrome's default `2px outset` border (top/left
 * #767676, bottom/right Chrome's `Color::Dark()` of it). Shows a pixel-art bitmap
 * (nearest-neighbour), a text/emoji, or a color swatch. [picked] inverts it (main tools,
 * `.tool.kp-selected`) or frames it (options, `#subtoolbars .tool.kp-selected`); action buttons
 * with [invertWhilePressed] invert while a finger is on them (`.kp-pressed`).
 */
class KButton(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    var bitmap: Bitmap? = null; set(v) { field = v; invalidate() }
    var src: Rect? = null; set(v) { field = v; invalidate() }
    /** Size the bitmap is drawn at, in dp (CSS px). */
    var bitmapDp = 36f
    /**
     * Draw [src] at 1 dp per source pixel with its top-left at (2, 2) dp, over the border
     * (a stamp: `<img class="tool sprite">` is 52 x 52 with object-fit none in the WebView app,
     * so it shows that much of the sprite sheet from the stamp's corner).
     */
    var spriteWindow = false
    var textBold = false; set(v) { field = v; tp.typeface = if (v) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT; invalidate() }
    var text: String? = null; set(v) { field = v; invalidate() }
    var textSp = 16f
    /** Text baseline moved by this many dp from vertically centred (the palette arrows sit higher). */
    var textDyDp = 0f
    var swatch: Int? = null; set(v) { field = v; invalidate() }
    var picked = false; set(v) { if (field != v) { field = v; invalidate() } }
    var frameWhenPicked = false
    var invertWhilePressed = false
    var invisibleSpacer = false
    /** pointerType of the last touch on this button ("pen", "touch", "mouse"), for the palm guard. */
    var lastPointerType = "touch"
        private set

    private val face = Paint().apply { color = Color.rgb(239, 239, 239) }
    /** border width: 2 CSS px snapped to whole device pixels, like Chrome's borders. */
    private val bw = borderWidthPx(d)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } // Chrome blends the mitred corners
    private val edgePath = android.graphics.Path()
    private val px = Paint().apply { isFilterBitmap = false }
    private val dstRect = RectF()
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.BLACK }
    private val frame = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 4 * d }
    private val invert = Paint().apply { colorFilter = ColorMatrixColorFilter(INVERT_HUE_ROTATE_180) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastPointerType = when (event.getToolType(0)) {
                MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> "pen"
                MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
                else -> "touch"
            }
        }
        return super.onTouchEvent(event)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (invertWhilePressed) invalidate()
    }

    /** kp-nope: a little shake, 4 px each way, 0.25 s, twice. */
    fun nudge() {
        ObjectAnimator.ofFloat(this, "translationX", 0f, -4 * d, 0f, 4 * d, 0f).apply {
            duration = 250
            repeatCount = 1
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        if (invisibleSpacer) return
        val inv = (picked && !frameWhenPicked) || (invertWhilePressed && isPressed)
        if (inv) c.saveLayer(null, invert)
        face.color = swatch ?: Color.rgb(239, 239, 239)
        if (inv || (picked && frameWhenPicked)) face.color = Color.WHITE // background: #fff !important
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), face)
        // border-color: #fff on .kp-selected (main tools and options alike)
        drawOutsetBorder(c, if (inv || (picked && frameWhenPicked)) Color.WHITE else BORDER)
        // box-shadow: inset 0 0 0 4px #000 sits inside the border (padding box), under the content
        if (picked && frameWhenPicked) {
            val f = 4 * d
            c.drawRect(bw + f / 2, bw + f / 2, width - bw - f / 2, height - bw - f / 2, frame)
        }
        bitmap?.let { b ->
            val s = src
            if (spriteWindow && s != null) {
                val l = Math.round(2 * d).toFloat(); val t = l
                dstRect.set(l, t, l + Math.round(s.width() * d), t + Math.round(s.height() * d))
                c.drawBitmap(b, s, dstRect, px)
                return@let
            }
            // where Chrome lays out an <img> in a <button> (measured in the WebView app), its
            // rect snapped to whole device pixels
            val wDp = width / d; val hDp = height / d
            val l = imgLeftDp(wDp, bitmapDp); val t = imgTopDp(hDp, bitmapDp)
            dstRect.set(Math.round(l * d).toFloat(), Math.round(t * d).toFloat(), Math.round((l + bitmapDp) * d).toFloat(), Math.round((t + bitmapDp) * d).toFloat())
            c.drawBitmap(b, s, dstRect, px)
        }
        text?.let { t ->
            tp.textSize = textSp * d
            val y = height / 2f - (tp.descent() + tp.ascent()) / 2 + textDyDp * d
            c.drawText(t, width / 2f, y, tp)
        }
        if (inv) c.restore()
    }

    /** `border: 2px outset c`: top and left in c, bottom and right in dark(c), mitred corners. */
    private fun drawOutsetBorder(c: Canvas, color: Int) {
        val w = width.toFloat(); val h = height.toFloat(); val b = bw
        edge.color = color
        edgePath.rewind()
        edgePath.moveTo(0f, 0f); edgePath.lineTo(w, 0f); edgePath.lineTo(w - b, b); edgePath.lineTo(b, b)
        edgePath.lineTo(b, h - b); edgePath.lineTo(0f, h); edgePath.close()
        c.drawPath(edgePath, edge)
        edge.color = outsetDark(color)
        edgePath.rewind()
        edgePath.moveTo(w, 0f); edgePath.lineTo(w, h); edgePath.lineTo(0f, h); edgePath.lineTo(b, h - b)
        edgePath.lineTo(w - b, h - b); edgePath.lineTo(w - b, b); edgePath.close()
        c.drawPath(edgePath, edge)
    }

    companion object {
        /** CSS px: the button's border, and the descent below an inline image's baseline in its line box. */
        private const val BORDER_DP = 2f
        private const val LINE_DESCENT_DP = 10f / 3f

        /** Left of an <img> in a <button> of [buttonDp]: centred in the content box, or at its start when wider. */
        fun imgLeftDp(buttonDp: Float, imgDp: Float): Float = BORDER_DP + maxOf(0f, (buttonDp - 2 * BORDER_DP - imgDp) / 2)

        /** Top: the line box (image + descent) centred in the content box, or at its top when taller. */
        fun imgTopDp(buttonDp: Float, imgDp: Float): Float = BORDER_DP + maxOf(0f, (buttonDp - 2 * BORDER_DP - imgDp - LINE_DESCENT_DP) / 2)

        /** Chrome's ButtonBorder. */
        val BORDER = Color.rgb(118, 118, 118)

        fun borderWidthPx(density: Float): Float = maxOf(1f, kotlin.math.floor(2 * density + 0.001f))

        /**
         * Blink's `Color::Dark()`, which `outset` uses for the bottom and right sides:
         * v = max(r,g,b)/255, each channel times max(0, (v - 0.33) / v). #767676 -> #212121,
         * #fff -> #ababab (both measured in the WebView app).
         */
        fun outsetDark(color: Int): Int {
            // the two border colours the app uses, exactly as measured (the formula lands 1 off)
            if (color == BORDER) return Color.rgb(33, 33, 33)
            if (color == Color.WHITE) return Color.rgb(171, 171, 171)
            val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
            val v = maxOf(r, maxOf(g, b)) / 255f
            if (v == 0f) return Color.rgb(84, 84, 84) // Blink: black darkens to a mid grey
            val m = maxOf(0f, (v - 0.33f) / v)
            return Color.rgb((r * m).toInt(), (g * m).toInt(), (b * m).toInt())
        }

        /**
         * CSS `filter: invert(1) hue-rotate(180deg)`: out = 1 - H * in, H the hue-rotate matrix at
         * 180 degrees (Filter Effects spec), rows summing to 1. Lightness flips, hues stay.
         */
        val INVERT_HUE_ROTATE_180 = ColorMatrix(floatArrayOf(
            0.574f, -1.430f, -0.144f, 0f, 255f,
            -0.426f, -0.430f, -0.144f, 0f, 255f,
            -0.426f, -1.430f, 0.856f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }
}
