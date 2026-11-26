@file:JvmName("textures_Gco")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import kotlin.math.PI
import kotlin.math.floor

// Port of js/textures/gco.js
// https://developer.mozilla.org/en-US/docs/Web/API/Canvas_API/Tutorial/Compositing/Example

val lightMix: () -> KCanvas = {
    val canvas2 = KCanvas()
    canvas2.width = 360
    canvas2.height = 360
    val ctx = canvas2.getContext("2d")
    ctx.save()
    ctx.globalCompositeOperation = "lighter"
    ctx.beginPath()
    ctx.fillStyle = KiddoPaint.Current.color
    ctx.arc(100, 200, 100, PI * 2, 0, false)
    ctx.fill()
    ctx.beginPath()
    ctx.fillStyle = KiddoPaint.Current.altColor
    ctx.arc(220, 200, 100, PI * 2, 0, false)
    ctx.fill()
    ctx.beginPath()
    ctx.fillStyle = KiddoPaint.Current.terColor
    ctx.arc(160, 100, 100, PI * 2, 0, false)
    ctx.fill()
    ctx.restore()
    ctx.canvas
}

val colorSphere: () -> KCanvas = {
    val canvas1 = KCanvas()
    canvas1.width = 360
    canvas1.height = 360
    val ctx = canvas1.getContext("2d")
    val width = 360
    val halfWidth = width / 2.0
    val rotate = (1.0 / 360) * PI * 2 // per degree
    val oleft = -20
    val otop = -20
    for (n in 0..359) {
        val gradient = ctx.createLinearGradient(oleft + halfWidth, otop, oleft + halfWidth, otop + halfWidth)
        val color = Color.HSV_RGB(HSV((n + 300) % 360.0, 100.0, 100.0))
        gradient.addColorStop(0, if (KiddoPaint.Current.modifiedToggle) "rgba(255, 255, 255, 0)" else "rgba(0, 0, 0, 0)")
        gradient.addColorStop(0.7, "rgba(" + color.R + "," + color.G + "," + color.B + ",1)")
        gradient.addColorStop(1, "rgba(255,255,255,1)")
        ctx.beginPath()
        ctx.moveTo(oleft + halfWidth, otop)
        ctx.lineTo(oleft + halfWidth, otop + halfWidth)
        ctx.lineTo(oleft + halfWidth + 6, otop)
        ctx.fillStyle = gradient
        ctx.fill()
        ctx.translate(oleft + halfWidth, otop + halfWidth)
        ctx.rotate(rotate)
        ctx.translate(-(oleft + halfWidth), -(otop + halfWidth))
    }
    ctx.canvas
}

class HSV(val H: Double, val S: Double, val V: Double)
class RGB(val R: Int, val G: Int, val B: Int)

// HSV (1978) = H: Hue / S: Saturation / V: Value
object Color {
    fun HSV_RGB(o: HSV): RGB {
        var H = o.H / 360
        val S = o.S / 100
        var V = o.V / 100
        val R: Double
        val G: Double
        var B: Double
        if (S == 0.0) {
            R = jsRound(V * 255); G = R; B = R
            return RGB(R.toInt(), G.toInt(), B.toInt())
        }
        if (H >= 1) H = 0.0
        H = 6 * H
        val D = H - floor(H)
        val A = jsRound(255 * V * (1 - S))
        B = jsRound(255 * V * (1 - (S * D)))
        val C = jsRound(255 * V * (1 - (S * (1 - D))))
        V = jsRound(255 * V)
        // note: upstream reuses B for both "blue" and the intermediate value, so cases 1, 3 and 5
        // read the intermediate B; we keep that behaviour.
        val r: Double; val g: Double; val b: Double
        when (floor(H).toInt()) {
            0 -> { r = V; g = C; b = A }
            1 -> { r = B; g = V; b = A }
            2 -> { r = A; g = V; b = C }
            3 -> { r = A; g = B; b = V }
            4 -> { r = C; g = A; b = V }
            else -> { r = V; g = A; b = B }
        }
        return RGB(r.toInt(), g.toInt(), b.toInt())
    }
}

fun makeComposite(gco: String): KCanvas {
    val dest = KCanvas()
    dest.width = 360
    dest.height = 360
    val ctx = dest.getContext("2d")
    ctx.drawImage(colorSphere(), 0, 0)
    ctx.globalCompositeOperation = gco
    ctx.drawImage(lightMix(), 0, 0)
    return dest
}

