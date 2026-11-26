@file:JvmName("builders_Road")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas

// Port of js/builders/road.js (no caller upstream; ported because it is small)

fun KiddoPaint.Builders.Road(color1In: String? = null, color2In: String? = null, angleIn: Double? = null): KCanvas {
    val color1 = color1In ?: "black"
    val color2 = if (color2In == color1) "yellow" else color2In
    val angle = angleIn ?: 0.0

    val canvasBrush = KCanvas()
    canvasBrush.width = 43
    canvasBrush.height = 43
    val contextBrush = canvasBrush.getContext("2d")

    contextBrush.beginPath()

    contextBrush.translate(21, 21)
    contextBrush.rotate(angle)
    contextBrush.translate(-15.5, -15.5)

    contextBrush.fillStyle = color1
    contextBrush.fillRect(0, 0, 30, 40)

    contextBrush.fillStyle = color2 // undefined is ignored, like the browser
    contextBrush.fillRect(13.5, 7.5, 3, 23)

    return canvasBrush
}
