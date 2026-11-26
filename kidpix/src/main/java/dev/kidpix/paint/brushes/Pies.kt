@file:JvmName("brushes_Pies")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.engine.KRandom
import kotlin.math.PI

// Port of js/brushes/pies.js

fun KiddoPaint.Brushes.Pies(color1In: String? = null): BrushFill {
    val color1 = color1In ?: "black"

    val canvasBrush = KCanvas()
    val size = 20 * KiddoPaint.Current.scaling
    canvasBrush.width = (size * 2).toInt()
    canvasBrush.height = (size * 2).toInt()
    val contextBrush = canvasBrush.getContext("2d")

    contextBrush.beginPath()
    contextBrush.arc(size, size, size, 0, PI * 2)
    contextBrush.fillStyle = color1
    if (KiddoPaint.Current.modifiedMeta) {
        contextBrush.stroke()
    } else {
        contextBrush.fill()
    }
    contextBrush.closePath()

    contextBrush.globalCompositeOperation = "destination-out"
    contextBrush.beginPath()
    contextBrush.fillStyle = color1
    val offset = KRandom.random() * 2 * PI
    contextBrush.arc(size, size, size + 2, 0 + offset, ((0.5 + KRandom.random() - 0.5) * PI) + offset)
    contextBrush.lineTo(size, size)
    contextBrush.fill()

    return BrushFill(brush = canvasBrush, offset = size)
}
