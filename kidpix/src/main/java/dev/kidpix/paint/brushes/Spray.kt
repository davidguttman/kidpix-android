@file:JvmName("brushes_Spray")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.engine.KRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Port of js/brushes/spray.js (no caller upstream; ported because it is small)

fun KiddoPaint.Brushes.Spray(color1In: String? = null, color2In: String? = null): BrushFill {
    val color1 = color1In ?: "black"
    val color2 = color2In ?: "black"

    val radius = 10 * KiddoPaint.Current.scaling * KiddoPaint.Current.multiplier
    val density = 128 * KiddoPaint.Current.scaling * KiddoPaint.Current.multiplier

    val canvasBrush = KCanvas()
    canvasBrush.width = (radius * 2).toInt()
    canvasBrush.height = (radius * 2).toInt()
    val contextBrush = canvasBrush.getContext("2d")
    contextBrush.fillStyle = color1

    fun ring() {
        val theta = KRandom.random() * 2 * PI
        val r1 = radius
        val r2 = radius * 0.75
        val rp = KRandom.random() + 0.33

        val dist = sqrt(abs(ziggurat()) * ((r1 * r1) - (r2 * r2)) + (r2 * r2))
        val xr = dist * cos(theta)
        val yr = dist * sin(theta)
        contextBrush.fillRect(radius + xr, radius + yr, rp, rp)
    }

    fun disc() {
        val rr = ziggurat() * radius * 1.1
        val ra = KRandom.random() * 2 * PI
        val rp = KRandom.random()
        val x = cos(ra) * rr
        val y = sin(ra) * rr
        contextBrush.fillRect(radius + x, radius + y, rp, rp)
    }

    var i = 0
    while (i < density) {
        if (KiddoPaint.Current.modifiedToggle) {
            contextBrush.fillStyle = color1
            if (KiddoPaint.Current.modifiedMeta) {
                contextBrush.globalAlpha = KRandom.random() / 4
            } else {
                contextBrush.globalAlpha = KRandom.random() / 2
            }
            ring()
            if (KiddoPaint.Current.modifiedMeta) {
                contextBrush.globalAlpha = KRandom.random() / 3
                contextBrush.fillStyle = color2
                disc()
            }
        } else {
            contextBrush.globalAlpha = KRandom.random() / 2
            disc()
        }
        i++
    }
    return BrushFill(brush = canvasBrush, offset = radius)
}
