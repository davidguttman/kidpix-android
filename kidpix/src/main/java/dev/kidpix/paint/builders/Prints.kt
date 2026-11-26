@file:JvmName("builders_Prints")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas

// Port of js/builders/prints.js

fun KiddoPaint.Builders.Prints(color1In: String?, print: String, angleIn: Double? = null): BrushFill {
    val color1 = color1In ?: "black"
    val angle = angleIn ?: 0.0

    val canvasBrush = KCanvas()
    canvasBrush.width = (150 * KiddoPaint.Current.scaling).toInt()
    canvasBrush.height = (150 * KiddoPaint.Current.scaling).toInt()
    val contextBrush = canvasBrush.getContext("2d")

    contextBrush.save()
    contextBrush.translate(50 * KiddoPaint.Current.scaling, 50 * KiddoPaint.Current.scaling)
    contextBrush.rotate(angle)
    contextBrush.translate(-25 * KiddoPaint.Current.scaling, 0)
    contextBrush.font = jsNumberToString(36 * KiddoPaint.Current.scaling) + "px sans-serif"
    contextBrush.textBaseline = "middle"
    contextBrush.textAlign = "center"
    contextBrush.fillText(print, 0, 0)
    contextBrush.restore()

    // we need to do this for the paw/footprints because you can't change the color of an emoji font any more :(
    contextBrush.globalCompositeOperation = "source-atop"
    contextBrush.fillStyle = color1
    contextBrush.fillRect(0, 0, canvasBrush.width, canvasBrush.height)

    return BrushFill(brush = trimCanvas3(canvasBrush), offset = 0.0)
}
