@file:JvmName("stamps_Stamps")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas

fun KStamps.stamp(stampIn: String?, alt: Boolean, ctrl: Boolean, size: Double, shiftAmount: Double, color: String?): KCanvas {
    val stamp = stampIn ?: ""
    val canvasBrush = KCanvas()
    // canvas sizes are unsigned longs: assignments truncate, reads give the integer back
    canvasBrush.width = Math.max(size + (size * 0.05), 24.0).toInt()
    canvasBrush.height = Math.max(size + (size * 0.05), 24.0).toInt()
    canvasBrush.height = (canvasBrush.height + 0.15 * canvasBrush.height).toInt() // prevent clipping on bottom

    val contextBrush = canvasBrush.getContext("2d")
    contextBrush.font = fmtPx(size) + "px " + KiddoPaint.Stamps.currentFace
    if (color != null) { // chrome & safari compat hack
        contextBrush.fillStyle = color
    }

    contextBrush.save()
    if (ctrl && alt) {
        contextBrush.scale(-1, 1)
        contextBrush.scale(1, -1)
        contextBrush.translate(-size, -size)
        contextBrush.fillText(stamp, 0, size - (0.15 * canvasBrush.height))
    } else if (ctrl) {
        contextBrush.scale(1, -1)
        contextBrush.fillText(stamp, 0, -0.15 * canvasBrush.height)
    } else if (alt) {
        contextBrush.translate(size, size)
        contextBrush.scale(-1, 1)
        contextBrush.fillText(stamp, 0, 0)
    } else {
        contextBrush.fillText(stamp, 0, size)
    }
    contextBrush.restore()

    if (shiftAmount != 0.0) {
        hueShift(canvasBrush, contextBrush, shiftAmount)
    }

    return canvasBrush
}

/** JS number-to-string for the font size: 64 -> "64", 64.5 -> "64.5". */
private fun fmtPx(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

fun KStamps.nextPage() {
    KiddoPaint.Stamps.page += 1
    if (KiddoPaint.Stamps.page > (KiddoPaint.Stamps.grouping?.pages ?: 0)) {
        KiddoPaint.Stamps.page = 1
    }
}

fun KStamps.prevPage() {
    KiddoPaint.Stamps.page -= 1
    if (KiddoPaint.Stamps.page < 1) {
        KiddoPaint.Stamps.page = KiddoPaint.Stamps.grouping?.pages ?: 0
    }
}
