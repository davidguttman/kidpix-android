package dev.kidpix.paint.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * #kp-pen-badge: the current tool's icon riding up-left of a hovering pen. 40 px box, 3 px
 * padding, 2 px black border, 6 px radius, 2 px shadow at 35% black, icon pixelated. Never touchable.
 */
class PenBadgeView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    var icon: Bitmap? = null
        private set
    var shown = false
        private set
    /** Top-left of the badge in this view's px. */
    var bx = 0f
        private set
    var by = 0f
        private set

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb((0.35f * 255).toInt(), 0, 0, 0) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val px = Paint().apply { isFilterBitmap = false }
    private val r = RectF()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(icon: Bitmap?, x: Float, y: Float) {
        if (shown && this.icon === icon && bx == x && by == y) return
        this.icon = icon; bx = x; by = y; shown = true
        invalidate()
    }

    fun hide() {
        if (!shown) return
        shown = false
        invalidate()
    }

    override fun onTouchEvent(event: android.view.MotionEvent?) = false

    override fun onDraw(c: Canvas) {
        if (!shown) return
        val s = 40 * d; val rad = 6 * d
        r.set(bx + 2 * d, by + 2 * d, bx + s + 2 * d, by + s + 2 * d)
        c.drawRoundRect(r, rad, rad, shadow)
        r.set(bx, by, bx + s, by + s)
        c.drawRoundRect(r, rad, rad, fill)
        r.inset(d, d)
        c.drawRoundRect(r, rad - d, rad - d, border)
        icon?.let { b ->
            val inset = 5 * d
            r.set(bx + inset, by + inset, bx + s - inset, by + s - inset)
            c.drawBitmap(b, null, r, px)
        }
    }
}
