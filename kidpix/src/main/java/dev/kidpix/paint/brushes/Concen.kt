@file:JvmName("brushes_Concen")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import kotlin.math.PI

// Port of js/brushes/concen.js

fun KiddoPaint.Brushes.Concentric(color1In: String?, step: Double): BrushFill {
    val color1 = color1In ?: "black"

    val canvasBrush = KCanvas()
    val size = ((step % 7 * 5) + 5) * KiddoPaint.Current.scaling
    canvasBrush.width = ((size * 2) + 10).toInt()
    canvasBrush.height = ((size * 2) + 10).toInt()
    val contextBrush = canvasBrush.getContext("2d")

    contextBrush.beginPath()
    contextBrush.arc(size + 5, size + 5, size, 0, PI * 2)
    contextBrush.strokeStyle = color1
    contextBrush.lineWidth = 1.0
    contextBrush.stroke()
    if (KiddoPaint.Current.modifiedMeta) {
        contextBrush.fill()
    }
    contextBrush.closePath()

    return BrushFill(brush = canvasBrush, offset = size)
}
