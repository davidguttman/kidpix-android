@file:JvmName("brushes_Triangles")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas

// Port of js/brushes/triangles.js

fun KiddoPaint.Brushes.Triangles(): BrushFill {
    val size = 35 * KiddoPaint.Current.scaling

    val canvasBrush = KCanvas()
    canvasBrush.width = (size * 2).toInt()
    canvasBrush.height = (size * 2).toInt()
    val contextBrush = canvasBrush.getContext("2d")

    // JS re-evaluates the loop bound (and so draws a random number) on every iteration
    var i = 0
    while (i < (1 + getRandomInt(1, 3))) {
        contextBrush.beginPath()
        contextBrush.moveTo(getRandomFloat(0.0, size), getRandomFloat(0.0, size))
        contextBrush.lineTo(getRandomFloat(0.0, size), getRandomFloat(0.0, size))
        contextBrush.lineTo(getRandomFloat(0.0, size), getRandomFloat(0.0, size))
        contextBrush.closePath()
        contextBrush.fillStyle = KiddoPaint.Colors.randomAllColor()
        contextBrush.fill()
        i++
    }

    return BrushFill(brush = canvasBrush, offset = size / 2.0)
}
