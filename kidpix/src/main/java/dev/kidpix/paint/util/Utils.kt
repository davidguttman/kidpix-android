@file:JvmName("util_Utils")

package dev.kidpix.paint

import android.graphics.PathMeasure
import dev.kidpix.paint.canvas.CanvasGradient
import dev.kidpix.paint.canvas.CanvasImageSource
import dev.kidpix.paint.canvas.CanvasPattern
import dev.kidpix.paint.canvas.CssColor
import dev.kidpix.paint.canvas.ImageData
import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.engine.KRandom
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Port of js/util/utils.js

fun distanceBetween(ev1: Ev, ev2: Ev): Double {
    val deltaxsq = (ev2._x - ev1._x) * (ev2._x - ev1._x)
    val deltaysq = (ev2._y - ev1._y) * (ev2._y - ev1._y)
    return sqrt(deltaxsq + deltaysq)
}

fun angleBetween(ev1: Ev, ev2: Ev): Double {
    val y = ev2._y - ev1._y
    val x = ev2._x - ev1._x
    return atan(y / (if (x == 0.0) 0.001 else x)) + (if (x < 0) PI else 0.0)
}

fun angleBetweenRad(ev1: Ev, ev2: Ev): Double = atan2(ev2._x - ev1._x, ev2._y - ev1._y)

class Hsl(var h: Double, var s: Double, var l: Double)
class Rgb(var r: Int, var g: Int, var b: Int)

// http://axonflux.com/handy-rgb-to-hsl-and-rgb-to-hsv-color-model-c
fun rgbToHsl(r0: Number, g0: Number, b0: Number): Hsl {
    val r = r0.toDouble() / 255; val g = g0.toDouble() / 255; val b = b0.toDouble() / 255
    val max = max(r, max(g, b))
    val min = min(r, min(g, b))
    var h: Double
    val s: Double
    val l = (max + min) / 2
    if (max == min) {
        h = 0.0; s = 0.0 // achromatic
    } else {
        val d = max - min
        s = if (l > 0.5) d / (2 - max - min) else d / (max + min)
        h = when (max) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        h /= 6
    }
    return Hsl(h, s, l)
}

fun hslToRgb(h: Double, s: Double, l: Double): Rgb {
    val r: Double
    val g: Double
    val b: Double
    if (s == 0.0) {
        r = l; g = l; b = l // achromatic
    } else {
        fun hue2rgb(p: Double, q: Double, t0: Double): Double {
            var t = t0
            if (t < 0) t += 1
            if (t > 1) t -= 1
            if (t < 1.0 / 6) return p + (q - p) * 6 * t
            if (t < 1.0 / 2) return q
            if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3 - t) * 6
            return p
        }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        r = hue2rgb(p, q, h + 1.0 / 3)
        g = hue2rgb(p, q, h)
        b = hue2rgb(p, q, h - 1.0 / 3)
    }
    return Rgb(jsRound(r * 255).toInt(), jsRound(g * 255).toInt(), jsRound(b * 255).toInt())
}

fun hueShift(canvas: KCanvas, context: dev.kidpix.paint.canvas.Ctx2D, shift: Double) {
    if (shift == 0.0) return
    val imageData = context.getImageData(0, 0, canvas.width, canvas.height)
    val rawData = imageData.data
    var i = 0
    while (i < rawData.length) {
        val red = rawData[i + 0]
        val green = rawData[i + 1]
        val blue = rawData[i + 2]
        val alpha = rawData[i + 3]
        if (red == 0 && green == 0 && blue == 0 && alpha == 0) { i += 4; continue }
        val hsl = rgbToHsl(red, green, blue)
        val shiftedRgb = hslToRgb(hsl.h + shift, hsl.s, hsl.l)
        rawData[i + 0] = shiftedRgb.r
        rawData[i + 1] = shiftedRgb.g
        rawData[i + 2] = shiftedRgb.b
        rawData[i + 3] = alpha
        i += 4
    }
    context.putImageData(imageData, 0, 0)
}

fun ziggurat(): Double =
    ((KRandom.random() + KRandom.random() + KRandom.random() + KRandom.random() + KRandom.random() + KRandom.random()) - 3) / 3

fun boxmuller(): DoubleArray {
    val r = sqrt(-2 * ln(KRandom.random()))
    val theta = 2 * PI * KRandom.random()
    return doubleArrayOf(r * cos(theta), r * sin(theta))
}

// Standard Normal variate using Box-Muller transform. https://stackoverflow.com/a/36481059
fun randn_bm(min: Double, max: Double, skew: Double): Double {
    var u = 0.0
    var v = 0.0
    while (u == 0.0) u = KRandom.random() // Converting [0,1) to (0,1)
    while (v == 0.0) v = KRandom.random()
    var num = sqrt(-2.0 * ln(u)) * cos(2.0 * PI * v)
    num = num / 10.0 + 0.5 // Translate to 0 -> 1
    if (num > 1 || num < 0) {
        num = randn_bm(min, max, skew) // resample between 0 and 1 if out of range
    } else {
        num = num.pow(skew) // Skew
        num *= max - min // Stretch to fill range
        num += min // offset to min
    }
    return num
}

fun extractSprite(img: CanvasImageSource, size: Int, col: Int, row: Int, offset: Int): KCanvas {
    val canvasIcon = KCanvas(size, size)
    val contextIcon = canvasIcon.getContext("2d")
    contextIcon.imageSmoothingEnabled = false
    val sourceX = offset + (col * size)
    val sourceY = offset + (row * size)
    contextIcon.drawImage(img, sourceX, sourceY, size, size, 0, 0, size, size)
    return canvasIcon
}

/** Returns the icon canvas (the JS returned canvasIcon.toDataURL()). */
fun makeIcon(texture: () -> Any?): KCanvas {
    val canvasIcon = KCanvas(50, 50)
    val contextIcon = canvasIcon.getContext("2d")
    contextIcon.beginPath()
    contextIcon.lineWidth = 1.0
    contextIcon.strokeRect(10, 10, 30, 30)
    texture()?.let { contextIcon.fillStyle = it }
    contextIcon.fillRect(10, 10, 30, 30)
    contextIcon.closePath()
    return canvasIcon
}

/** makeIcon(KiddoPaint.Textures.Solid): textures take an optional color. */
@JvmName("makeIconColor")
fun makeIcon(texture: (String?) -> Any?): KCanvas = makeIcon(fun(): Any? = texture(null))

fun makeCircleIcon(texture: () -> Any?): KCanvas {
    val canvasIcon = KCanvas(50, 50)
    val contextIcon = canvasIcon.getContext("2d")
    contextIcon.beginPath()
    contextIcon.lineWidth = 1.0
    texture()?.let { contextIcon.fillStyle = it }
    contextIcon.arc(25, 25, 15, 0, 2 * PI)
    contextIcon.fill()
    contextIcon.stroke()
    contextIcon.closePath()
    return canvasIcon
}

@JvmName("makeCircleIconColor")
fun makeCircleIcon(texture: (String?) -> Any?): KCanvas = makeCircleIcon(fun(): Any? = texture(null))

class XY(var x: Double, var y: Double)

// the JS signature repeats `m`; the second one wins: (R, r, m, theta, p, Q, m, n) -> m is the 7th arg
fun guil(R: Double, r: Double, @Suppress("UNUSED_PARAMETER") m0: Double, theta: Double, p: Double, Q: Double, m: Double, n: Double): XY {
    val x = (R + r) * cos(m * theta) + (r + p) * cos(((R + r) / r) * m * theta) + Q * cos(n * theta)
    val y = (R + r) * sin(m * theta) - (r + p) * sin(((R + r) / r) * m * theta) + Q * sin(n * theta)
    return XY(x, y)
}

fun scaleImageData(imageData: ImageData, scale: Double): ImageData = scaleImageDataCanvasAPIPixelatedAlt(imageData, scale)

// https://stackoverflow.com/a/40772881 (the JS draws ImageData with drawImage, which browsers
// reject; we draw the pixels)
fun scaleImageDataCanvasAPI(imageData: ImageData, scale: Double): KCanvas {
    val canvas = KCanvas(imageData.width, imageData.height)
    canvas.getContext("2d").putImageData(imageData, 0, 0)
    val scaleCanvas = KCanvas((imageData.width * scale).toInt(), (imageData.height * scale).toInt())
    val scaleCtx = scaleCanvas.getContext("2d")
    scaleCtx.scale(scale, scale)
    scaleCtx.drawImage(canvas, 0, 0)
    return scaleCanvas
}

/** The JS function as written (drawImage of an image or canvas): tools/placer.js passes an Image. */
fun scaleImageDataCanvasAPI(imageData: CanvasImageSource, scale: Double): KCanvas {
    val canvas = KCanvas(imageData.width, imageData.height)
    canvas.getContext("2d").drawImage(imageData, 0, 0)
    val scaleCanvas = KCanvas((imageData.width * scale).toInt(), (imageData.height * scale).toInt())
    val scaleCtx = scaleCanvas.getContext("2d")
    scaleCtx.scale(scale, scale)
    scaleCtx.drawImage(canvas, 0, 0)
    return scaleCanvas
}

fun scaleImageDataCanvasAPIPixelatedAlt(imageData: ImageData, scale: Double): ImageData {
    val canvas = KCanvas(imageData.width, imageData.height)
    canvas.getContext("2d").imageSmoothingEnabled = false
    canvas.getContext("2d").putImageData(imageData, 0, 0)
    val scaleCanvas = KCanvas((imageData.width * scale).toInt(), (imageData.height * scale).toInt())
    val scaleCtx = scaleCanvas.getContext("2d")
    scaleCtx.imageSmoothingEnabled = false
    scaleCtx.scale(scale, scale)
    scaleCtx.drawImage(canvas, 0, 0)
    return scaleCtx.getImageData(0, 0, scaleCanvas.width, scaleCanvas.height)
}

fun scaleImageDataCanvasAPIPixelated(imageData: CanvasImageSource, scale: Double): KCanvas {
    val canvas = KCanvas(imageData.width, imageData.height)
    canvas.getContext("2d").imageSmoothingEnabled = false
    canvas.getContext("2d").drawImage(imageData, 0, 0)
    val scaleCanvas = KCanvas((imageData.width * scale).toInt(), (imageData.height * scale).toInt())
    val scaleCtx = scaleCanvas.getContext("2d")
    scaleCtx.imageSmoothingEnabled = false
    scaleCtx.scale(scale, scale)
    scaleCtx.drawImage(canvas, 0, 0)
    return scaleCanvas
}

fun pixelateCanvas(imageData: CanvasImageSource, block: Double): KCanvas {
    val size = block / 100
    val w = imageData.width * size
    val h = imageData.height * size
    // shrink it down (canvas sizes truncate like the DOM's unsigned long conversion)
    val canvas = KCanvas(w.toInt(), h.toInt())
    val ctx = canvas.getContext("2d")
    ctx.imageSmoothingEnabled = false
    ctx.drawImage(imageData, 0, 0, w, h)
    // draw it back original size
    val canvas2 = KCanvas(imageData.width, imageData.height)
    val ctx2 = canvas2.getContext("2d")
    ctx2.imageSmoothingEnabled = false
    ctx2.drawImage(canvas, 0, 0, w, h, 0, 0, canvas2.width, canvas2.height)
    return canvas2
}

// http://stackoverflow.com/a/9138593 w/ alpha bg flattening
fun scaleImageDataPixelwise(imageData: ImageData, scale: Int): ImageData {
    val scaled = KiddoPaint.Display.main_context.createImageData(imageData.width * scale, imageData.height * scale)
    val d = imageData.data
    for (row in 0 until imageData.height) {
        for (col in 0 until imageData.width) {
            val i = (row * imageData.width + col) * 4
            val transparent = d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 0
            val sourcePixel = if (transparent) intArrayOf(255, 255, 255, 255) else intArrayOf(d[i], d[i + 1], d[i + 2], d[i + 3])
            for (y in 0 until scale) {
                val destRow = row * scale + y
                for (x in 0 until scale) {
                    val destCol = col * scale + x
                    for (k in 0 until 4) {
                        // JS assigns an array for 0 channels, which the clamped array stores as 0
                        scaled.data[(destRow * scaled.width + destCol) * 4 + k] = if (sourcePixel[k] == 0) 0 else sourcePixel[k]
                    }
                }
            }
        }
    }
    return scaled
}

fun greyscaleImageData(imageData: ImageData): ImageData {
    val grey = KiddoPaint.Display.main_context.createImageData(imageData.width, imageData.height)
    val d = imageData.data
    var pixel = 0
    while (pixel < d.length) {
        if (d[pixel] == 0 && d[pixel + 1] == 0 && d[pixel + 2] == 0 && d[pixel + 3] == 0) { pixel += 4; continue }
        val hsl = rgbToHsl(d[pixel], d[pixel + 1], d[pixel + 2])
        val desat = hslToRgb(hsl.h, 0.0, hsl.l)
        grey.data[pixel + 0] = desat.r
        grey.data[pixel + 1] = desat.g
        grey.data[pixel + 2] = desat.b
        grey.data[pixel + 3] = d[pixel + 3] // keep same alpha
        pixel += 4
    }
    return grey
}

// https://github.com/meemoo/iframework/blob/gh-pages/src/nodes/image-monochrome-worker.js
fun ditherImageData(imageData: ImageData): ImageData {
    val threshold = 128
    val bayerThresholdMap = arrayOf(
        intArrayOf(15, 135, 45, 165),
        intArrayOf(195, 75, 225, 105),
        intArrayOf(60, 180, 30, 150),
        intArrayOf(240, 120, 210, 90),
    )
    val lumR = DoubleArray(256) { it * 0.299 }
    val lumG = DoubleArray(256) { it * 0.587 }
    val lumB = DoubleArray(256) { it * 0.114 }
    val dithered = KiddoPaint.Display.main_context.createImageData(imageData.width, imageData.height)
    val d = imageData.data
    val len = d.length
    var i = 0
    while (i < len) {
        dithered.data[i] = floor(lumR[d[i]] + lumG[d[i + 1]] + lumB[d[i + 2]]).toInt()
        i += 4
    }
    var pixel = 0
    while (pixel < len) {
        if (d[pixel] == 0 && d[pixel + 1] == 0 && d[pixel + 2] == 0 && d[pixel + 3] == 0) { pixel += 4; continue }
        val x = pixel / 4 % imageData.width
        val y = pixel / 4 / imageData.width
        // JS reads imageData (not the luminance) here, as upstream does
        val map = floor((d[pixel] + bayerThresholdMap[x % 4][y % 4]) / 2.0).toInt()
        val v = if (map < threshold) 0 else 255
        dithered.data[pixel] = v; dithered.data[pixel + 1] = v; dithered.data[pixel + 2] = v
        dithered.data[pixel + 3] = 255
        pixel += 4
    }
    return dithered
}

// http://michalbe.blogspot.com/2011/02/javascript-random-numbers-with-custom_23.html
class Srng(private var seed: Double) {
    fun next(): Double {
        val constant = 2.0.pow(11) + 1
        val prime = 4241.0
        val maximum = 4243.0
        seed *= constant
        seed += prime
        return seed % maximum / maximum
    }
}

fun srng(seed: Number? = null): Srng = Srng(seed?.toDouble()?.takeIf { it != 0.0 && !it.isNaN() } ?: 7.0)

// https://stackoverflow.com/questions/17924214
fun bresenham(x1In: Int, y1In: Int, x2: Int, y2: Int, callback: (Int, Int) -> Unit) {
    var x1 = x1In
    var y1 = y1In
    var dx = x2 - x1
    var sx = 1
    var dy = y2 - y1
    var sy = 1
    var space = 0
    val spacing = 3
    if (dx < 0) { sx = -1; dx = -dx }
    if (dy < 0) { sy = -1; dy = -dy }
    dx = dx shl 1
    dy = dy shl 1
    if (dy < dx) {
        var fraction = dy - (dx shr 1)
        while (x1 != x2) {
            if (fraction >= 0) { y1 += sy; fraction -= dx }
            fraction += dy
            x1 += sx
            if (space == spacing) { callback(x1, y1); space = 0 } else space += 1
        }
    } else {
        var fraction = dx - (dy shr 1)
        while (y1 != y2) {
            if (fraction >= 0) { x1 += sx; fraction -= dy }
            fraction += dx
            y1 += sy
            if (space == spacing) { callback(x1, y1); space = 0 } else space += 1
        }
    }
    callback(x1, y1)
}

class ColorJson(var r: Int, var g: Int, var b: Int, var a: Int)

/** What a 1x1 fillRect + getImageData gives for a CSS color (premultiplied round trip included). */
fun color2json(color: Any): ColorJson {
    val canvas = KCanvas(1, 1)
    val ctx = canvas.getContext("2d")
    ctx.fillStyle = color
    ctx.fillRect(0, 0, 1, 1)
    val imageData = ctx.getImageData(0, 0, 1, 1)
    return ColorJson(imageData.data[0], imageData.data[1], imageData.data[2], imageData.data[3])
}

fun colorsEqual(color1: ColorJson, color2: ColorJson) =
    color1.r == color2.r && color1.g == color2.g && color1.b == color2.b && color1.a == color2.a

fun colorNearWhite(color: ColorJson): Boolean {
    val basegrey = 221
    return color.r > basegrey && color.g > basegrey && color.b > basegrey && color.a > 235
}

fun clamp(value: Double, min: Double, max: Double) = min(max(value, min), max)
fun clamp(value: Int, min: Int, max: Int) = min(max(value, min), max)
fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
fun invlerp(a: Double, b: Double, v: Double) = (1.0 * (v - a)) / (1.0 * (b - a))
fun remap(imin: Double, imax: Double, omin: Double, omax: Double, v: Double) = lerp(omin, omax, invlerp(imin, imax, v))

fun createFeatherGradient(radius: Double, hardness: Double): CanvasGradient {
    val innerRadius = min(radius * hardness, radius - 1)
    val gradient = KiddoPaint.Display.context.createRadialGradient(0, 0, innerRadius, 0, 0, radius)
    gradient.addColorStop(0, "rgba(255, 0, 0, 0)")
    gradient.addColorStop(1, "rgba(0, 0, 255, 1)")
    return gradient
}

// https://stackoverflow.com/questions/34681457
fun getCubicBezierXYatPercent(startPt: DoubleArray, controlPt1: DoubleArray, controlPt2: DoubleArray, endPt: DoubleArray, percent: Double): Ev {
    val x = CubicN(percent, startPt[0], controlPt1[0], controlPt2[0], endPt[0])
    val y = CubicN(percent, startPt[1], controlPt1[1], controlPt2[1], endPt[1])
    return Ev(x, y)
}

fun CubicN(T: Double, a: Double, b: Double, c: Double, d: Double): Double {
    val t2 = T * T
    val t3 = t2 * T
    return a + (-a * 3 + T * (3 * a - a * T)) * T + (3 * b + T * (-6 * b + b * 3 * T)) * T + (c * 3 - c * 3 * T) * t2 + d * t3
}

// https://stackoverflow.com/a/38626906 (the JS measures an SVG path; we measure an android Path)
fun bezierLength(startPt: DoubleArray, controlPt1: DoubleArray, controlPt2: DoubleArray, endPt: DoubleArray): Double {
    val p = android.graphics.Path()
    p.moveTo(startPt[0].toFloat(), startPt[1].toFloat())
    p.cubicTo(controlPt1[0].toFloat(), controlPt1[1].toFloat(), controlPt2[0].toFloat(), controlPt2[1].toFloat(),
        endPt[0].toFloat(), endPt[1].toFloat())
    return PathMeasure(p, false).length.toDouble()
}

fun getRandomFloat(min: Double, max: Double) = KRandom.random() * (max - min) + min

fun getRandomInt(min0: Number, max0: Number): Int {
    val min = ceil(min0.toDouble())
    val max = floor(max0.toDouble())
    return floor(KRandom.random() * (max - min) + min).toInt() // max exclusive, min inclusive
}

fun flattenImage(imageData: ImageData): ImageData {
    imageData.data.argbPixels()?.let { px -> // pixels still as ints: same result without making bytes
        for (i in px.indices) if ((px[i] ushr 24) == 0) px[i] = -1
        return imageData
    }
    val data = imageData.data
    var i = 0
    while (i < data.length) {
        if (data[i + 3] == 0) { data[i] = 255; data[i + 1] = 255; data[i + 2] = 255; data[i + 3] = 255 }
        i += 4
    }
    return imageData
}

fun getRandomLetter(): String {
    val possible = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return possible[floor(KRandom.random() * possible.length).toInt()].toString()
}

fun makePatternFromImage(image: CanvasImageSource): CanvasPattern? {
    val canvasPattern = KCanvas(KiddoPaint.Display.canvas.width, KiddoPaint.Display.canvas.height)
    val contextPattern = canvasPattern.getContext("2d")
    contextPattern.fillStyle = "white"
    contextPattern.fillRect(0, 0, canvasPattern.width, canvasPattern.height)
    val xOffset = KiddoPaint.Display.canvas.width / 2.0 - image.width
    val yOffset = KiddoPaint.Display.canvas.height / 2.0 - image.height
    contextPattern.imageSmoothingEnabled = false
    contextPattern.drawImage(image, xOffset, yOffset, image.width * 2, image.height * 2)
    return contextPattern.createPattern(canvasPattern, "no-repeat")
}

// ---------------------------------------------------------------- JS semantics helpers

/** Math.round: halves round up (toward +inf), unlike kotlin.math.round. */
fun jsRound(v: Double): Double = floor(v + 0.5)

/** JS `%` on doubles keeps the dividend's sign; Kotlin's `%` does too (same), `.mod()` does not. */
fun jsMod(a: Double, b: Double): Double = a % b

/** CSS color string -> ARGB int, or null. */
fun parseCssColor(c: String): Int? = CssColor.parse(c)
