@file:JvmName("brushes_Connectthedots")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas

// Port of js/brushes/connectthedots.js

fun KiddoPaint.Brushes.ConnectTheDots(color1In: String?, step: Double): BrushFill {
    val color1 = color1In ?: "black"

    val canvasBrush = KCanvas()
    canvasBrush.width = 150
    canvasBrush.height = 150
    val contextBrush = canvasBrush.getContext("2d")

    contextBrush.font = "16px sans-serif"
    contextBrush.textBaseline = "middle"
    contextBrush.textAlign = "center"
    contextBrush.fillStyle = color1
    val dotandnumber = "• " + jsNumberToString(step)
    contextBrush.measureText(dotandnumber)
    contextBrush.fillText(dotandnumber, 16, 16, canvasBrush.width) // 3rd arg tries to constrain width

    return BrushFill(brush = trimCanvas3(canvasBrush), offset = 0.0)
}

/** String(n) for the integers the brushes count with ("3", not "3.0"). */
internal fun jsNumberToString(n: Double): String =
    if (n == Math.floor(n) && !n.isInfinite() && kotlin.math.abs(n) < 1e21) n.toLong().toString() else n.toString()
