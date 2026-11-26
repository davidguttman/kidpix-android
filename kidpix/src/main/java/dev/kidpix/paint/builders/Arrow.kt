@file:JvmName("builders_Arrow")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.engine.KRandom

// Port of js/builders/arrow.js (no caller upstream; ported because it is small)

fun KiddoPaint.Builders.Arrow(color1In: String? = null, angleIn: Double? = null): KCanvas {
    fun jitter(): Double {
        val baseJitter = 10
        return baseJitter + (KRandom.random() * baseJitter)
    }
    val color1 = color1In ?: "black"
    val angle = angleIn ?: 0.0

    val canvasBrush = KCanvas()
    canvasBrush.width = 50
    canvasBrush.height = 50
    val contextBrush = canvasBrush.getContext("2d")

    contextBrush.beginPath()

    contextBrush.translate(21, 21)
    contextBrush.rotate(angle)
    contextBrush.translate(-10.5, -15.5)

    contextBrush.strokeStyle = color1

    for (i in 0 until 5) {
        contextBrush.moveTo(10 + jitter() / 2.0, 0 + jitter() / 2.0)
        contextBrush.lineTo(0 + jitter(), 23 + jitter())
        contextBrush.moveTo(10 + jitter(), 0 + jitter())
        contextBrush.lineTo(20 + jitter(), 20 + jitter())
    }

    contextBrush.stroke()

    return canvasBrush
}
