@file:JvmName("util_TrimCanvas")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.ImageData
import dev.kidpix.paint.canvas.KCanvas

// Port of js/util/trim-canvas.js. trimCanvas and trimCanvas2 are never called upstream: skipped.

// https://gist.github.com/timdown/021d9c8f2aabc7092df564996f5afbbf
private fun rowBlank(imageData: ImageData, width: Int, y: Int): Boolean {
    for (x in 0 until width) {
        if (imageData.data[y * width * 4 + x * 4 + 3] != 0) return false
    }
    return true
}

private fun columnBlank(imageData: ImageData, width: Int, x: Int, top: Int, bottom: Int): Boolean {
    for (y in top until bottom) {
        if (imageData.data[y * width * 4 + x * 4 + 3] != 0) return false
    }
    return true
}

private fun trimBody(canvas: KCanvas, flatten: Boolean): KCanvas {
    if (canvas.width == 0 || canvas.height == 0) return canvas
    val ctx = canvas.getContext("2d")
    val width = canvas.width
    val imageData = ctx.getImageData(0, 0, canvas.width, canvas.height)
    var top = 0
    var bottom = imageData.height
    var left = 0
    var right = imageData.width

    while (top < bottom && rowBlank(imageData, width, top)) ++top
    while (bottom - 1 > top && rowBlank(imageData, width, bottom - 1)) --bottom
    while (left < right && columnBlank(imageData, width, left, top, bottom)) ++left
    while (right - 1 > left && columnBlank(imageData, width, right - 1, top, bottom)) --right

    // the browser throws IndexSizeError for a 0-sized getImageData (an all-transparent canvas)
    require(right - left != 0 && bottom - top != 0) { "IndexSizeError: getImageData with a zero size" }
    val trimmed = ctx.getImageData(left, top, right - left, bottom - top)
    val copy = KCanvas()
    val copyCtx = copy.getContext("2d")
    copy.width = trimmed.width
    copy.height = trimmed.height

    if (flatten) flattenImage(trimmed)

    copyCtx.putImageData(trimmed, 0, 0)
    return copy
}

/** Returns a new canvas cropped to the non-transparent pixels of [c]. */
fun trimCanvas3(c: KCanvas): KCanvas = trimBody(c, false)

/** Cropped as [trimCanvas3], transparent pixels made white (the save code uses it). */
fun trimAndFlattenCanvas(c: KCanvas): KCanvas = trimBody(c, true)
