@file:JvmName("brushes_Rainbowdoughnut")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Port of js/brushes/rainbowdoughnut.js (no caller upstream; ported because it is small)

@Suppress("UNUSED_PARAMETER")
fun KiddoPaint.Brushes.RainbowDoughnut(step: Double): BrushFill {
    val canvas = KCanvas()
    val size = 32 * KiddoPaint.Current.scaling
    canvas.width = (size * 2).toInt()
    canvas.height = (size * 2).toInt()
    val ctx = canvas.getContext("2d")

    fun drawMultiRadiantCircle(xc: Double, yc: Double, r: Double, radientColors: List<String>) {
        val partLength = (2 * PI) / radientColors.size
        var start = 0.0
        for (i in radientColors.indices) {
            val startColor = radientColors[i]
            val endColor = radientColors[(i + 1) % radientColors.size]

            val xStart = xc + cos(start) * r
            val xEnd = xc + cos(start + partLength) * r
            val yStart = yc + sin(start) * r
            val yEnd = yc + sin(start + partLength) * r

            ctx.beginPath()

            val gradient = ctx.createLinearGradient(xStart, yStart, xEnd, yEnd)
            gradient.addColorStop(0, startColor)
            gradient.addColorStop(1.0, endColor)

            ctx.strokeStyle = gradient
            ctx.arc(xc, yc, r, start, start + partLength)
            ctx.lineWidth = size / 4
            ctx.stroke()
            ctx.closePath()

            start += partLength
        }
    }

    val someColors = listOf("#0F0", "#0FF", "#F00", "#FF0", "#F0F")

    drawMultiRadiantCircle(size / 2, size / 2, size / 3, someColors)

    return BrushFill(brush = canvas, offset = size / 2.0)
}
