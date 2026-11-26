@file:JvmName("brushes_Dumbbell")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.engine.KRandom
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Port of js/brushes/dumbbell.js (no caller upstream; ported because it is small)

fun KiddoPaint.Brushes.Dumbbell(color1In: String? = null, color2In: String? = null): BrushFill {
    val color1 = color1In ?: "black"
    @Suppress("UNUSED_VARIABLE") val color2 = color2In ?: "black"

    val radius = 25 * KiddoPaint.Current.scaling * KiddoPaint.Current.multiplier
    val density = 128 * KiddoPaint.Current.scaling * KiddoPaint.Current.multiplier

    val canvasBrush = KCanvas()
    canvasBrush.width = (radius * 2).toInt()
    canvasBrush.height = (radius * 2).toInt()
    val contextBrush = canvasBrush.getContext("2d")
    contextBrush.fillStyle = color1

    fun bar() {
        val rr = ziggurat() * radius
        val ra = KRandom.random() * 2 * PI
        val x = cos(ra) * rr
        val y = sin(ra) * rr / 11
        contextBrush.fillRect(radius + x, radius + y, 0.7, 0.7)
    }

    var i = 0
    while (i < density) {
        contextBrush.globalAlpha = KRandom.random() / 2
        bar()
        i++
    }
    return BrushFill(brush = canvasBrush, offset = radius)
}
