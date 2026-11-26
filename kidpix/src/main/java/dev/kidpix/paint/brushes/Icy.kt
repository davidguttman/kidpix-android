@file:JvmName("brushes_Icy")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.engine.KRandom

// Port of js/brushes/icy.js (no caller upstream; ported because it is small)

fun KiddoPaint.Brushes.Icy(color1In: String? = null): BrushFill {
    val color1 = color1In ?: "black"

    val radius = 32 * KiddoPaint.Current.scaling * KiddoPaint.Current.multiplier
    // clamp(value=0, min=2000, max=x) as upstream wrote it: min(2000, x)
    val density = clamp(0.0, 2000.0, 600 * KiddoPaint.Current.scaling * KiddoPaint.Current.multiplier)

    val canvasBrush = KCanvas()
    canvasBrush.width = 32
    canvasBrush.height = 400
    val contextBrush = canvasBrush.getContext("2d")
    contextBrush.fillStyle = color1

    fun delicatespray() {
        val px = 0.4 * (if (KiddoPaint.Current.multiplier < 6) 1 else 2)
        val x = randn_bm(-5.0, 5.0, 1.0)
        val y = randn_bm(-radius, radius, 5.0)
        contextBrush.fillRect(x, radius + y, px, px)
    }

    var i = 0
    while (i < density) {
        contextBrush.globalAlpha = KRandom.random() / 2
        delicatespray()
        i++
    }

    return BrushFill(brush = canvasBrush, offset = 0.0)
}
