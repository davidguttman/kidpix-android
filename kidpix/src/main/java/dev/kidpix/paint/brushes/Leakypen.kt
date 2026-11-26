@file:JvmName("brushes_Leakypen")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.engine.world
import kotlin.math.E
import kotlin.math.PI

// Port of js/brushes/leakypen.js

/** The JS closure's `prevSize` (per page load). */
private var KiddoPaint.Brushes.leakyPrevSize: Double by world { 3.0 }
private const val LEAKY_BASE_SIZE = 3.0
private const val LEAKY_MAX_SIZE = PI * LEAKY_BASE_SIZE * E

fun KiddoPaint.Brushes.LeakyPen(color1In: String?, distPrev: Double?): BrushFill {
    val color1 = color1In ?: "black"
    // JS: null < 2 is true (null -> 0)
    if ((distPrev ?: 0.0) < 2) {
        if (leakyPrevSize < LEAKY_MAX_SIZE) {
            leakyPrevSize += 0.15
        }
    } else {
        leakyPrevSize = LEAKY_BASE_SIZE
    }

    val size = leakyPrevSize * KiddoPaint.Current.scaling

    val canvasBrush = KCanvas()
    canvasBrush.width = (size * 4.5).toInt()
    canvasBrush.height = (size * 4.5).toInt()
    val contextBrush = canvasBrush.getContext("2d")

    contextBrush.beginPath()
    contextBrush.ellipse(size * 1.5, size * 1.5, size, size, PI / 4, 0, 2 * PI)
    contextBrush.fillStyle = color1
    contextBrush.fill()
    contextBrush.closePath()

    return BrushFill(brush = canvasBrush, offset = canvasBrush.width / 2.0)
}
