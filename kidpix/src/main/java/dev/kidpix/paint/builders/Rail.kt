@file:JvmName("builders_Rail")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas

// Port of js/builders/rail.js (no caller upstream; ported because it is small)

@Suppress("UNUSED_PARAMETER")
fun KiddoPaint.Builders.Rail(color1In: String? = null, angleIn: Double? = null): KCanvas {
    val angle = angleIn ?: 0.0

    val canvasBrush = KCanvas()
    canvasBrush.width = 43
    canvasBrush.height = 43
    val contextBrush = canvasBrush.getContext("2d")

    contextBrush.beginPath()

    contextBrush.translate(21, 21)
    contextBrush.rotate(angle)
    contextBrush.translate(-15.5, -15.5)

    // left rail
    contextBrush.fillStyle = "rgb(190, 190, 190)"
    contextBrush.fillRect(0.5, 0, 3, 40)
    contextBrush.fillRect(30.5, 0, 2.5, 40)

    // right rail
    contextBrush.fillStyle = "rgb(128, 128, 128)"
    contextBrush.fillRect(3.5, 0, 2.5, 40)
    contextBrush.fillRect(27.5, 0, 2.5, 40)

    // ties
    for (i in 0 until 4) {
        val offset = 8 * i
        contextBrush.fillStyle = "rgb(136, 104, 67)"
        contextBrush.fillRect(0, 5.5 + offset, 35, 2)
        contextBrush.fillStyle = "rgb(73, 61, 38)"
        contextBrush.fillRect(0, 7 + offset, 35, 1.5)
    }
    return canvasBrush
}
