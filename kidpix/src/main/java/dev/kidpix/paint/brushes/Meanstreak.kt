@file:JvmName("brushes_Meanstreak")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import kotlin.math.PI

// Port of js/brushes/meanstreak.js (no caller upstream; ported because it is small)

fun KiddoPaint.Brushes.MeanStreak(step: Double): BrushFill {
    val canvasBrush = KCanvas()
    val size = 32 * KiddoPaint.Current.scaling
    canvasBrush.width = (size * 2).toInt()
    canvasBrush.height = (size * 2).toInt()
    val contextBrush = canvasBrush.getContext("2d")

    val transforms = listOf(
        "source-in",
        "source-out",
        "destination-atop",
        "screen",
        "overlay",
        "soft-light",
        "lighter",
        "exclusion",
        "luminosity",
    )

    val c = makeComposite(transforms[KiddoPaint.Current.multiplier])

    contextBrush.translate(size / 2, size / 2)
    contextBrush.rotate(((step % 360)) * (PI / 180))
    contextBrush.translate(-size / 2, -size / 2)
    contextBrush.drawImage(c, 0, 0, c.width, c.height, 0, 0, size, size)

    return BrushFill(brush = canvasBrush, offset = size / 2.0)
}
