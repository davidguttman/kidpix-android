@file:JvmName("brushes_Rpent")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Port of js/brushes/rpent.js

fun KiddoPaint.Brushes.RotatingPentagon(color1In: String?, stepIn: Double): BrushFill {
    val color1 = color1In ?: "black"
    val interval = 50.0
    val step = stepIn % interval / interval

    val canvasBrush = KCanvas()
    val size = 33 * KiddoPaint.Current.scaling
    canvasBrush.width = (size * 2).toInt()
    canvasBrush.height = (size * 2).toInt()
    val contextBrush = canvasBrush.getContext("2d")
    contextBrush.fillStyle = color1
    contextBrush.strokeStyle = color1
    contextBrush.lineWidth = 1.0

    var i = 0
    var s = step
    while (i < 6) {
        val x = size + (size * cos(2 * PI * s))
        val y = size + (size * sin(2 * PI * s))
        contextBrush.lineTo(x, y)
        i++; s += 10 / interval
    }

    contextBrush.stroke()
    return BrushFill(brush = canvasBrush, offset = size, inplace = true)
}
