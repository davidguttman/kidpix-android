@file:JvmName("brushes_Splatter")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import kotlin.math.PI

// Port of js/brushes/splatter.js

fun KiddoPaint.Brushes.Splatters(): BrushFill {
    val size = 27 * KiddoPaint.Current.scaling

    val canvasBrush = KCanvas()
    canvasBrush.width = (size * 2).toInt()
    canvasBrush.height = (size * 2).toInt()
    val contextBrush = canvasBrush.getContext("2d")

    // JS re-evaluates the loop bound (and so draws a random number) on every iteration
    var i = 0
    while (i < (2 + getRandomInt(1, 3))) {
        val csize = getRandomFloat(1.0, 7.0)
        contextBrush.beginPath()
        val cx = size + getRandomFloat(-size / 2.0, size / 2.0)
        val cy = size + getRandomFloat(-size / 2.0, size / 2.0)
        contextBrush.arc(cx, cy, csize, 0, 2 * PI)
        contextBrush.fillStyle = KiddoPaint.Colors.randomAllColor()
        contextBrush.fill()
        contextBrush.closePath()
        i++
    }

    return BrushFill(brush = canvasBrush, offset = size / 2.0)
}
