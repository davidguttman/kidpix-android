@file:JvmName("util_Filters")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.CanvasImageSource
import dev.kidpix.paint.canvas.ImageData
import dev.kidpix.paint.canvas.KCanvas
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Port of js/util/filters.js
// https://www.html5rocks.com/en/tutorials/canvas/imagefilters/

/** `{width, height, data: Float32Array}` from convoluteFloat32. */
class FloatImageData(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height * 4))

object Filters {
    fun getPixels(img: CanvasImageSource): ImageData {
        val c: KCanvas
        if (img is KCanvas) {
            c = img
        } else {
            c = getCanvas(img.width, img.height)
            c.getContext("2d").drawImage(img, 0, 0)
        }
        return c.getContext("2d").getImageData(0, 0, c.width, c.height)
    }

    fun getCanvas(w: Int, h: Int): KCanvas {
        val c = KCanvas()
        c.width = w
        c.height = h
        return c
    }

    fun filterImage(filter: (ImageData, Array<out Any?>) -> ImageData, image: CanvasImageSource, vararg varArgs: Any?): ImageData =
        filter(getPixels(image), varArgs)

    fun grayscale(pixels: ImageData): ImageData {
        val d = pixels.data
        var i = 0
        while (i < d.length) {
            val r = d[i]
            val g = d[i + 1]
            val b = d[i + 2]
            // CIE luminance for the RGB
            val v = 0.2126 * r + 0.7152 * g + 0.0722 * b
            d[i] = v; d[i + 1] = v; d[i + 2] = v
            i += 4
        }
        return pixels
    }

    fun brightness(pixels: ImageData, adjustment: Double): ImageData {
        val d = pixels.data
        var i = 0
        while (i < d.length) {
            d[i] = d[i] + adjustment
            d[i + 1] = d[i + 1] + adjustment
            d[i + 2] = d[i + 2] + adjustment
            i += 4
        }
        return pixels
    }

    fun invert(pixels: ImageData): ImageData {
        val d = pixels.data
        var i = 0
        while (i < d.length) {
            d[i] = 255 - d[i]
            d[i + 1] = 255 - d[i + 1]
            d[i + 2] = 255 - d[i + 2]
            i += 4
        }
        return pixels
    }

    fun gcoOpWithWhite(imageData: ImageData, alpha: Double, op: String): KCanvas {
        val canvas = KiddoPaint.Display.imageTypeToCanvas(imageData)
        val ctx = canvas.getContext("2d")
        ctx.globalCompositeOperation = op
        ctx.fillStyle = "white"
        ctx.globalAlpha = alpha
        ctx.fillRect(0, 0, canvas.width, canvas.height)
        return canvas
    }

    /** JS calls gcoInvert(imageData) with alpha undefined -> globalAlpha = undefined is ignored -> 1. */
    fun gcoInvert(imageData: ImageData, alpha: Double = 1.0): KCanvas = gcoOpWithWhite(imageData, alpha, "difference")

    fun gcoOverlay(imageData: ImageData, alpha: Double = 1.0): KCanvas = gcoOpWithWhite(imageData, alpha, "overlay")

    fun threshold(pixels: ImageData, threshold: Double): ImageData {
        val d = pixels.data
        var i = 0
        while (i < d.length) {
            val r = d[i]
            val g = d[i + 1]
            val b = d[i + 2]
            val v = if (0.2126 * r + 0.7152 * g + 0.0722 * b >= threshold) 255 else 0
            d[i] = v; d[i + 1] = v; d[i + 2] = v
            i += 4
        }
        return pixels
    }

    val tmpCanvas = KCanvas()
    val tmpCtx = tmpCanvas.getContext("2d")

    fun createImageData(w: Int, h: Int): ImageData = tmpCtx.createImageData(w, h)

    fun convolute(pixels: ImageData, weights: DoubleArray, opaque: Boolean = false): ImageData {
        val side = jsRound(sqrt(weights.size.toDouble())).toInt()
        val halfSide = floor(side / 2.0).toInt()

        val src = pixels.data
        val sw = pixels.width
        val sh = pixels.height

        val w = sw
        val h = sh
        val output = createImageData(w, h)
        val dst = output.data

        val alphaFac = if (opaque) 1 else 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val sy = y
                val sx = x
                val dstOff = (y * w + x) * 4
                var r = 0.0
                var g = 0.0
                var b = 0.0
                var a = 0.0
                for (cy in 0 until side) {
                    for (cx in 0 until side) {
                        val scy = min(sh - 1, max(0, sy + cy - halfSide))
                        val scx = min(sw - 1, max(0, sx + cx - halfSide))
                        val srcOff = (scy * sw + scx) * 4
                        val wt = weights[cy * side + cx]
                        r += src[srcOff] * wt
                        g += src[srcOff + 1] * wt
                        b += src[srcOff + 2] * wt
                        a += src[srcOff + 3] * wt
                    }
                }
                dst[dstOff] = r
                dst[dstOff + 1] = g
                dst[dstOff + 2] = b
                dst[dstOff + 3] = a + alphaFac * (255 - a)
            }
        }
        return output
    }

    fun convoluteFloat32(pixels: ImageData, weights: DoubleArray, opaque: Boolean = false): FloatImageData {
        val side = jsRound(sqrt(weights.size.toDouble())).toInt()
        val halfSide = floor(side / 2.0).toInt()

        val src = pixels.data
        val sw = pixels.width
        val sh = pixels.height

        val w = sw
        val h = sh
        val output = FloatImageData(w, h)
        val dst = output.data

        val alphaFac = if (opaque) 1 else 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val sy = y
                val sx = x
                val dstOff = (y * w + x) * 4
                var r = 0.0
                var g = 0.0
                var b = 0.0
                var a = 0.0
                for (cy in 0 until side) {
                    for (cx in 0 until side) {
                        val scy = min(sh - 1, max(0, sy + cy - halfSide))
                        val scx = min(sw - 1, max(0, sx + cx - halfSide))
                        val srcOff = (scy * sw + scx) * 4
                        val wt = weights[cy * side + cx]
                        r += src[srcOff] * wt
                        g += src[srcOff + 1] * wt
                        b += src[srcOff + 2] * wt
                        a += src[srcOff + 3] * wt
                    }
                }
                dst[dstOff] = r.toFloat()
                dst[dstOff + 1] = g.toFloat()
                dst[dstOff + 2] = b.toFloat()
                dst[dstOff + 3] = (a + alphaFac * (255 - a)).toFloat()
            }
        }
        return output
    }

    fun sobel(px0: ImageData): ImageData {
        val px = grayscale(px0)
        val vertical = convoluteFloat32(px,
            doubleArrayOf(-1.0, -2.0, -1.0,
                0.0, 0.0, 0.0,
                1.0, 2.0, 1.0))
        val horizontal = convoluteFloat32(px,
            doubleArrayOf(-1.0, 0.0, 1.0,
                -2.0, 0.0, 2.0,
                -1.0, 0.0, 1.0))
        val id = createImageData(vertical.width, vertical.height)
        var i = 0
        while (i < id.data.length) {
            val v = abs(vertical.data[i].toDouble())
            id.data[i] = v
            val h = abs(horizontal.data[i].toDouble())
            id.data[i + 1] = h
            id.data[i + 2] = (v + h) / 4
            id.data[i + 3] = 255
            i += 4
        }
        return id
    }
}
