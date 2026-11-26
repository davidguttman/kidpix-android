package dev.kidpix.paint.app

import android.content.Context
import android.view.Surface
import android.widget.FrameLayout

/**
 * Keeps the app in landscape when the system won't: on tablets with `fixed-to-user-rotation`
 * the window manager ignores sensorLandscape and turns the window with the tablet. When the window
 * is taller than wide, the one child is laid out landscape (the window's height x width) and turned
 * a quarter clockwise inside the window, so the picture looks as with the display at ROTATION_90
 * (or ROTATION_270 from upside down): turn the tablet a quarter counter-clockwise and it reads
 * upright, and then the system turns the window and this does nothing. Touch and hover reach the
 * child through the View transform (ViewGroup maps them), so the app sees landscape coordinates.
 * The canvas size is the same either way, so a turn never resizes the picture.
 */
class LandscapeFrame(context: Context, private val onTurn: (Boolean) -> Unit) : FrameLayout(context) {
    /** How the child sits: laid out at [childW]x[childH], turned [degrees] about its top-left, then moved. */
    data class Turn(val childW: Int, val childH: Int, val degrees: Float, val tx: Float, val ty: Float) {
        val turned get() = degrees != 0f

        /** Child coordinates -> window coordinates (what the View transform does). */
        fun toWindow(x: Float, y: Float): Pair<Float, Float> = when (degrees) {
            90f -> (tx - y) to (ty + x)
            -90f -> (tx + y) to (ty - x)
            else -> (x + tx) to (y + ty)
        }

        companion object {
            /** For a window of [w]x[h] px on a display at [rotation] (Surface.ROTATION_*). */
            @JvmStatic fun of(w: Int, h: Int, @Suppress("UNUSED_PARAMETER") rotation: Int): Turn =
                // a quarter clockwise from either portrait rotation: ROTATION_0 looks like ROTATION_90,
                // ROTATION_180 like ROTATION_270 (both: the tablet turned a quarter counter-clockwise)
                if (h <= w) Turn(w, h, 0f, 0f, 0f) else Turn(h, w, 90f, w.toFloat(), 0f)
        }
    }

    var turn = Turn(0, 0, 0f, 0f, 0f)
        private set

    private fun rotation(): Int = runCatching { display?.rotation }.getOrNull() ?: Surface.ROTATION_0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val t = Turn.of(w, h, rotation())
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(t.childW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(t.childH, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val t = Turn.of(right - left, bottom - top, rotation())
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.layout(0, 0, t.childW, t.childH)
            c.pivotX = 0f; c.pivotY = 0f
            c.rotation = t.degrees
            c.translationX = t.tx; c.translationY = t.ty
        }
        val was = turn.turned
        turn = t
        if (t.turned != was || changed) onTurn(t.turned)
    }
}
