@file:JvmName("brushes_Twirly")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Port of js/brushes/twirly.js

fun KiddoPaint.Brushes.Twirly(color1In: String?, stepIn: Double): BrushFill {
    val color1 = color1In ?: "black"
    val step = stepIn % 24 / 24

    val canvasBrush = KCanvas()
    val size = 25 * KiddoPaint.Current.scaling
    canvasBrush.width = (size * 2).toInt()
    canvasBrush.height = (size * 2).toInt()
    val contextBrush = canvasBrush.getContext("2d")

    contextBrush.beginPath()
    contextBrush.moveTo(size, size)
    val x = size + (size * cos(2 * PI * step))
    val y = size + (size * sin(2 * PI * step))
    contextBrush.lineTo(x, y)
    contextBrush.strokeStyle = color1
    contextBrush.lineWidth = 1.0
    contextBrush.stroke()
    contextBrush.closePath()

    return BrushFill(brush = canvasBrush, offset = size, inplace = true)
}
