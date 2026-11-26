@file:JvmName("brushes_Rainbowbar")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import kotlin.math.PI

// Port of js/brushes/rainbowbar.js (its only caller, pencil's "Rainbow 3", is commented out)

@Suppress("UNUSED_PARAMETER")
fun KiddoPaint.Brushes.RainbowBar(step: Double): BrushFill {
    val canvas = KCanvas()
    val size = 35 * KiddoPaint.Current.scaling
    canvas.width = (size * 2.25).toInt()
    canvas.height = (size * 2.25).toInt()
    val ctx = canvas.getContext("2d")

    val gradient = ctx.createLinearGradient(10, 0, size * 2, 0)
    gradient.addColorStop(0, "red")
    gradient.addColorStop(1.0 / 6, "orange")
    gradient.addColorStop(2.0 / 6, "yellow")
    gradient.addColorStop(3.0 / 6, "green")
    gradient.addColorStop(4.0 / 6, "blue")
    gradient.addColorStop(5.0 / 6, "indigo")
    gradient.addColorStop(1, "violet")
    ctx.fillStyle = gradient
    ctx.rotate(20 * PI / 180)
    ctx.fillRect(7, 0, size * 2, 16)

    return BrushFill(brush = canvas, offset = size / 2.0)
}
