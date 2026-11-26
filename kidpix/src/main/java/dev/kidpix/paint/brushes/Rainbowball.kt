@file:JvmName("brushes_Rainbowball")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas

// Port of js/brushes/rainbowball.js (no caller upstream; ported because it is small)

@Suppress("UNUSED_PARAMETER")
fun KiddoPaint.Brushes.RainbowBall(step: Double): BrushFill {
    val canvas = KCanvas()
    val size = 100 * KiddoPaint.Current.scaling
    canvas.width = (size * 2).toInt()
    canvas.height = (size * 2).toInt()
    val ctx = canvas.getContext("2d")

    val g1 = ctx.createRadialGradient(45, 45, 10, 52, 50, 30)
    g1.addColorStop(0, "#A7D30C")
    g1.addColorStop(0.9, "#019F62")
    g1.addColorStop(1, "rgba(1,159,98,0)")
    val g2 = ctx.createRadialGradient(125, 45, 20, 132, 50, 30)
    g2.addColorStop(0, "#FF5F98")
    g2.addColorStop(0.75, "#FF0188")
    g2.addColorStop(1, "rgba(255,1,136,0)")
    ctx.fillStyle = g1

    ctx.fillRect(0, 0, size, size)

    return BrushFill(brush = canvas, offset = size / 2.0)
}
