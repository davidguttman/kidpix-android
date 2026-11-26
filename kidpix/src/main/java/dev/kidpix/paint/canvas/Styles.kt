package dev.kidpix.paint.canvas

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build

/** createPattern(image, repetition): a snapshot of the image's pixels at creation time. */
class CanvasPattern(val bitmap: Bitmap, val repetition: String) {
    /** Repeats both ways and every pixel is opaque: it paints every pixel it covers fully. */
    val opaqueRepeat: Boolean by lazy {
        (repetition == "repeat" || repetition == "") && run {
            val px = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            px.all { (it ushr 24) == 255 }
        }
    }

    /** An opaque 1x1 repeating pattern (Textures.Solid) paints one colour: that ARGB, else null. */
    val solidColor: Int? by lazy {
        if ((repetition == "repeat" || repetition == "") && bitmap.width == 1 && bitmap.height == 1) {
            bitmap.getPixel(0, 0).takeIf { (it ushr 24) == 255 }
        } else null
    }

    /** Paints the same as [o] (same pixels, same repetition). */
    fun sameAs(o: CanvasPattern): Boolean = o === this || (o.repetition == repetition && o.bitmap.sameAs(bitmap))

    fun shader(): Shader {
        val decal = if (Build.VERSION.SDK_INT >= 31) Shader.TileMode.DECAL else Shader.TileMode.CLAMP
        val rx = repetition == "repeat" || repetition == "repeat-x" || repetition == ""
        val ry = repetition == "repeat" || repetition == "repeat-y" || repetition == ""
        return BitmapShader(bitmap, if (rx) Shader.TileMode.REPEAT else decal, if (ry) Shader.TileMode.REPEAT else decal)
    }
}

/** createLinearGradient / createRadialGradient. */
class CanvasGradient internal constructor(private val kind: Int, private val c: DoubleArray) {
    private val stops = ArrayList<Pair<Double, Int>>()
    private var cached: Shader? = null

    fun addColorStop(offset: Number, color: String) {
        val o = offset.toDouble()
        require(o in 0.0..1.0) { "IndexSizeError: offset $o" }
        val col = CssColor.parse(color) ?: throw IllegalArgumentException("SyntaxError: color $color")
        stops.add(o to col)
        cached = null
    }

    val isEmpty get() = stops.isEmpty()

    fun shader(): Shader? {
        if (stops.isEmpty()) return null
        cached?.let { return it }
        val sorted = stops.sortedBy { it.first } // stable: equal offsets keep insertion order
        var pos = sorted.map { it.first.toFloat() }.toFloatArray()
        var cols = sorted.map { it.second }.toIntArray()
        if (cols.size == 1) {
            pos = floatArrayOf(0f, 1f); cols = intArrayOf(cols[0], cols[0])
        } else {
            // canvas pads with the first/last stop color outside [first,last]
            if (pos.first() > 0f) { pos = floatArrayOf(0f) + pos; cols = intArrayOf(cols.first()) + cols }
            if (pos.last() < 1f) { pos += 1f; cols += cols.last() }
        }
        val s: Shader? = if (kind == 0) {
            if (c[0] == c[2] && c[1] == c[3]) return null.also { cached = null } // degenerate: paints nothing
            LinearGradient(c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), c[3].toFloat(), cols, pos, Shader.TileMode.CLAMP)
        } else radial(pos, cols)
        cached = s
        return s
    }

    private fun radial(pos: FloatArray, cols: IntArray): Shader? {
        val (x0, y0, r0) = Triple(c[0], c[1], c[2])
        val (x1, y1, r1) = Triple(c[3], c[4], c[5])
        if (x0 == x1 && y0 == y1) {
            if (r0 == r1) return null
            // concentric: map t over [r0, r1] onto the single-circle gradient [0, rMax]
            val inverted = r1 < r0
            val rMax = maxOf(r0, r1)
            val rMin = minOf(r0, r1)
            val p = FloatArray(pos.size)
            val cc = IntArray(cols.size)
            for (i in pos.indices) {
                val j = if (inverted) pos.size - 1 - i else i
                val t = if (inverted) 1.0 - pos[j] else pos[j].toDouble()
                p[i] = ((rMin + t * (rMax - rMin)) / rMax).toFloat()
                cc[i] = cols[j]
            }
            // pad inside rMin with the inner stop color
            val pp = floatArrayOf(0f) + p
            val ccc = intArrayOf(cc[0]) + cc
            return RadialGradient(x0.toFloat(), y0.toFloat(), rMax.toFloat(), ccc, pp, Shader.TileMode.CLAMP)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val longs = LongArray(cols.size) { Color.pack(cols[it]) }
            return RadialGradient(x0.toFloat(), y0.toFloat(), r0.toFloat(), x1.toFloat(), y1.toFloat(), r1.toFloat(),
                longs, pos, Shader.TileMode.CLAMP)
        }
        return RadialGradient(x1.toFloat(), y1.toFloat(), maxOf(r1, 0.001).toFloat(), cols, pos, Shader.TileMode.CLAMP)
    }

    companion object {
        fun linear(x0: Double, y0: Double, x1: Double, y1: Double) = CanvasGradient(0, doubleArrayOf(x0, y0, x1, y1))
        fun radial(x0: Double, y0: Double, r0: Double, x1: Double, y1: Double, r1: Double) =
            CanvasGradient(1, doubleArrayOf(x0, y0, r0, x1, y1, r1))
    }
}

class TextMetrics(
    val width: Double,
    val actualBoundingBoxLeft: Double,
    val actualBoundingBoxRight: Double,
    val actualBoundingBoxAscent: Double,
    val actualBoundingBoxDescent: Double,
    val fontBoundingBoxAscent: Double,
    val fontBoundingBoxDescent: Double,
)
