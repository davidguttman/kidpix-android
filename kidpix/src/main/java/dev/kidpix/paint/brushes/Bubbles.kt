@file:JvmName("brushes_Bubbles")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.engine.KRandom
import kotlin.math.PI

// Port of js/brushes/bubbles.js

fun KiddoPaint.Brushes.Bubbles(color1In: String? = null): BrushFill {
    val color1 = color1In ?: "black"

    val canvasBrush = KCanvas()
    val size = (20 * KiddoPaint.Current.scaling)
    canvasBrush.width = (size * 2).toInt()
    canvasBrush.height = (size * 2).toInt()
    val contextBrush = canvasBrush.getContext("2d")

    var i = -(size / 2)
    while (i < (size / 2)) {
        var j = -(size / 2)
        while (j < (size / 2)) {
            if (KRandom.random() > 0.5) {
                contextBrush.beginPath()
                contextBrush.arc(size + i, size + j, 4, 0, PI * 2)
                contextBrush.strokeStyle = color1
                contextBrush.lineWidth = 1.0
                contextBrush.stroke()
                if (KRandom.random() > 0.35) {
                    contextBrush.fillStyle = color1
                } else {
                    contextBrush.fillStyle = "white"
                }
                contextBrush.fill()
                contextBrush.closePath()
            }
            j += 4
        }
        i += 4
    }

    return BrushFill(brush = canvasBrush, offset = size)
}
