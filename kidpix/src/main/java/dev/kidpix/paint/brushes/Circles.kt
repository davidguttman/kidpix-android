@file:JvmName("brushes_Circles")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.engine.world
import kotlin.math.PI

// Port of js/brushes/circles.js

/** The JS closure's `flip` counter (per page load). */
private var KiddoPaint.Brushes.circlesFlip: Int by world { 0 }

fun KiddoPaint.Brushes.Circles(color1In: String? = null, color2In: String? = null, alwaysFillIn: Boolean? = null): BrushFill {
    val color1 = color1In ?: "black"
    val color2 = color2In ?: color1
    val alwaysFill = alwaysFillIn ?: false
    val size = 20 * KiddoPaint.Current.scaling

    val canvasBrush = KCanvas()
    canvasBrush.width = (size * 2).toInt()
    canvasBrush.height = (size * 2).toInt()
    val contextBrush = canvasBrush.getContext("2d")

    contextBrush.beginPath()
    contextBrush.arc(size, size, size / 2, 0, 2 * PI)
    if (alwaysFill || circlesFlip % 2 == 0) {
        contextBrush.fillStyle = color1
        contextBrush.fill()
    }
    contextBrush.lineWidth = 2.0
    contextBrush.strokeStyle = color2
    contextBrush.stroke()
    contextBrush.closePath()
    circlesFlip++

    return BrushFill(brush = canvasBrush, offset = size)
}

fun KiddoPaint.Brushes.RCircles(): BrushFill {
    val color1 = KiddoPaint.Colors.randomColor()
    val color2 = KiddoPaint.Colors.randomColor()
    return KiddoPaint.Brushes.Circles(color1, color2, true)
}
