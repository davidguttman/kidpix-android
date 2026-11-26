@file:JvmName("brushes_Rose")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Port of js/brushes/rose.js (no caller upstream; ported because it is small)

fun KiddoPaint.Brushes.Rose(color1In: String?, stepIn: Double): BrushFill {
    val color1 = color1In ?: "black"
    val interval = 257.0
    val fraction = interval / 7
    val k = 5
    val step = stepIn % interval / interval

    val canvasBrush = KCanvas()
    val size = 50 * KiddoPaint.Current.scaling
    canvasBrush.width = (size * 2).toInt()
    canvasBrush.height = (size * 2).toInt()
    val contextBrush = canvasBrush.getContext("2d")
    contextBrush.fillStyle = color1
    contextBrush.strokeStyle = color1

    var i = 0
    var s = step
    while (i < 6) {
        val x = size + (size * cos(k * 2 * PI * s) * cos(2 * PI * s))
        val y = size + (size * cos(k * 2 * PI * s) * sin(2 * PI * s))
        contextBrush.lineTo(x, y)
        i++; s += fraction / interval
    }
    contextBrush.stroke()

    return BrushFill(brush = canvasBrush, offset = size, inplace = true)
}
