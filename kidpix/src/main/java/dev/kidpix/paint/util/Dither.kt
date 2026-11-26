@file:JvmName("util_Dither")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.ImageData
import dev.kidpix.paint.canvas.Uint8Clamped
import kotlin.math.floor

// Port of js/util/dither.js
// https://github.com/NielsLeenheer/CanvasDither

/** TypedArray.fill(value, start, end): the value is converted like a single store. */
private fun Uint8Clamped.fill(value: Double, start: Int, end: Int) {
    for (k in start until minOf(end, length)) this[k] = value
}

/**
 * Use the ImageData from a Canvas and turn the image in a 1-bit black and white image using dithering
 */
class CanvasDither {
    /** Change the image to grayscale */
    fun grayscale(image: ImageData): ImageData {
        var i = 0
        while (i < image.data.length) {
            val luminance = (image.data[i] * 0.299) + (image.data[i + 1] * 0.587) + (image.data[i + 2] * 0.114)
            image.data.fill(luminance, i, i + 3)
            i += 4
        }

        return image
    }

    /** Change the image to blank and white using a simple threshold (0-255) */
    fun threshold(image: ImageData, threshold: Double): ImageData {
        var i = 0
        while (i < image.data.length) {
            val luminance = (image.data[i] * 0.299) + (image.data[i + 1] * 0.587) + (image.data[i + 2] * 0.114)

            val value = if (luminance < threshold) 0.0 else 255.0
            image.data.fill(value, i, i + 3)
            i += 4
        }

        return image
    }

    /** Change the image to blank and white using the Bayer algorithm (threshold 0-255) */
    fun bayer(image: ImageData, threshold: Double): ImageData {
        val thresholdMapA = arrayOf(
            intArrayOf(15, 135, 45, 165),
            intArrayOf(195, 75, 225, 105),
            intArrayOf(60, 180, 30, 150),
            intArrayOf(240, 120, 210, 90),
        )

        @Suppress("UNUSED_VARIABLE")
        val thresholdMapB = arrayOf(
            intArrayOf(0, 8, 2, 10),
            intArrayOf(12, 4, 14, 6),
            intArrayOf(3, 11, 1, 9),
            intArrayOf(15, 7, 13, 5),
        )

        @Suppress("UNUSED_VARIABLE")
        val thresholdMapC = arrayOf(
            intArrayOf(0, 48, 12, 60, 3, 51, 15, 63),
            intArrayOf(32, 16, 44, 28, 35, 19, 47, 31),
            intArrayOf(8, 56, 4, 52, 11, 59, 7, 55),
            intArrayOf(40, 24, 36, 20, 43, 27, 39, 23),
            intArrayOf(2, 50, 14, 62, 1, 49, 13, 61),
            intArrayOf(34, 18, 46, 30, 33, 17, 45, 29),
            intArrayOf(10, 58, 6, 54, 9, 57, 5, 53),
            intArrayOf(42, 26, 38, 22, 41, 25, 37, 21),
        )

        val thresholdMap = thresholdMapA

        val ret = KiddoPaint.Display.context.createImageData(image)

        var i = 0
        while (i < image.data.length) {
            val luminance = (image.data[i] * 0.299) + (image.data[i + 1] * 0.587) + (image.data[i + 2] * 0.114)
            val x = i / 4 % image.width
            val y = floor(i / 4.0 / image.width).toInt()
            val map = floor((luminance + thresholdMap[x % 4][y % 4]) / 2)
            val value = if (map < threshold) 0.0 else 255.0
            ret.data.fill(value, i, i + 3)
            ret.data[i + 3] = image.data[i + 3]
            i += 4
        }
        return ret
    }

    /** Change the image to blank and white using the Floyd-Steinberg algorithm */
    fun floydsteinberg(image: ImageData): ImageData {
        val width = image.width
        val luminance = Uint8Clamped(image.width * image.height)

        run {
            var l = 0
            var i = 0
            while (i < image.data.length) {
                luminance[l] = (image.data[i] * 0.299) + (image.data[i + 1] * 0.587) + (image.data[i + 2] * 0.114)
                l++; i += 4
            }
        }

        var l = 0
        var i = 0
        while (i < image.data.length) {
            val value = if (luminance[l] < 129) 0 else 255
            val error = floor((luminance[l] - value) / 16.0)
            image.data.fill(value.toDouble(), i, i + 3)

            luminance[l + 1] = luminance[l + 1] + error * 7
            luminance[l + width - 1] = luminance[l + width - 1] + error * 3
            luminance[l + width] = luminance[l + width] + error * 5
            luminance[l + width + 1] = luminance[l + width + 1] + error * 1
            l++; i += 4
        }

        return image
    }

    /** Change the image to blank and white using the Atkinson algorithm */
    fun atkinson(image: ImageData): ImageData {
        val width = image.width
        val luminance = Uint8Clamped(image.width * image.height)

        run {
            var l = 0
            var i = 0
            while (i < image.data.length) {
                luminance[l] = (image.data[i] * 0.299) + (image.data[i + 1] * 0.587) + (image.data[i + 2] * 0.114)
                l++; i += 4
            }
        }

        var l = 0
        var i = 0
        while (i < image.data.length) {
            val value = if (luminance[l] < 129) 0 else 255
            val error = floor((luminance[l] - value) / 8.0)
            image.data.fill(value.toDouble(), i, i + 3)

            luminance[l + 1] = luminance[l + 1] + error
            luminance[l + 2] = luminance[l + 2] + error
            luminance[l + width - 1] = luminance[l + width - 1] + error
            luminance[l + width] = luminance[l + width] + error
            luminance[l + width + 1] = luminance[l + width + 1] + error
            luminance[l + 2 * width] = luminance[l + 2 * width] + error
            l++; i += 4
        }

        return image
    }
}

val Dither = CanvasDither()
