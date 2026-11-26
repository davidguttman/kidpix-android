@file:JvmName("brushes_Raindrops")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.engine.KRandom
import kotlin.math.PI

// Port of js/brushes/raindrops.js

fun KiddoPaint.Brushes.Raindrops(color1In: String? = null): BrushFill {
    val color1 = color1In ?: "black"
    val size = (5 + (100 * KRandom.random())) * KiddoPaint.Current.scaling

    val canvasBrush = KCanvas()
    canvasBrush.width = (size * 2).toInt()
    canvasBrush.height = (size * 2).toInt()
    val contextBrush = canvasBrush.getContext("2d")

    contextBrush.beginPath()
    contextBrush.arc(size, size, size / 2, 0, 2 * PI)
    contextBrush.fillStyle = color1
    contextBrush.fill()
    contextBrush.closePath()

    return BrushFill(
        brush = canvasBrush,
        abspos = XY(
            getRandomFloat(-5.0, KiddoPaint.Display.canvas.width + 5.0),
            getRandomFloat(-5.0, KiddoPaint.Display.canvas.width + 5.0),
        ),
    )
}
