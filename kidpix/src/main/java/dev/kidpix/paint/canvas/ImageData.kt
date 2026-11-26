package dev.kidpix.paint.canvas

import kotlin.math.floor

/**
 * Uint8ClampedArray: reads give 0..255; writes clamp and round half to even like the browser
 * (NaN -> 0). Out-of-range reads return 0 (JS gives undefined), out-of-range writes are ignored.
 */
class Uint8Clamped(val size: Int) {
    private var raw: ByteArray? = null

    /**
     * Pixels from getImageData as non-premultiplied ARGB ints (one per 4 bytes), not yet turned
     * into bytes: most tools only move image data around (putImageData), and that never needs
     * them. The bytes are made on first access, after which this is null.
     */
    internal var argb: IntArray? = null
        private set

    internal constructor(size: Int, argb: IntArray) : this(size) { this.argb = argb }

    val bytes: ByteArray get() = raw ?: materialize()

    /** The ARGB ints while no bytes were made (writable: they are the data), else null. */
    fun argbPixels(): IntArray? = argb

    private fun materialize(): ByteArray {
        val b = ByteArray(size)
        val px = argb
        if (px != null) {
            var j = 0
            for (i in 0 until size / 4) {
                val c = px[i]
                b[j] = (c shr 16).toByte(); b[j + 1] = (c shr 8).toByte(); b[j + 2] = c.toByte(); b[j + 3] = (c ushr 24).toByte()
                j += 4
            }
            argb = null
        }
        raw = b
        return b
    }

    val length: Int get() = size

    operator fun get(i: Int): Int = if (i in 0 until size) bytes[i].toInt() and 0xff else 0
    operator fun get(i: Double): Int = get(i.toInt())

    operator fun set(i: Int, v: Int) {
        if (i in 0 until size) bytes[i] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
    }

    operator fun set(i: Int, v: Double) {
        if (i in 0 until size) bytes[i] = clamp(v).toByte()
    }

    operator fun set(i: Int, v: Float) = set(i, v.toDouble())
    operator fun set(i: Int, v: Long) = set(i, v.coerceIn(-1, 256).toInt())
    operator fun set(i: Double, v: Int) = set(i.toInt(), v)
    operator fun set(i: Double, v: Double) = set(i.toInt(), v)

    fun fill(v: Int) = bytes.fill(clamp(v.toDouble()).toByte())

    companion object {
        fun clamp(v: Double): Int {
            if (v.isNaN() || v <= 0.0) return 0
            if (v >= 255.0) return 255
            val f = floor(v)
            val d = v - f
            return when {
                d > 0.5 -> f.toInt() + 1
                d < 0.5 -> f.toInt()
                else -> if (f.toInt() % 2 == 0) f.toInt() else f.toInt() + 1
            }
        }
    }
}

/** Canvas ImageData: RGBA, straight (non-premultiplied) alpha, row-major. */
class ImageData(val width: Int, val height: Int, val data: Uint8Clamped = Uint8Clamped(width * height * 4)) {
    init {
        require(data.size == width * height * 4) { "ImageData size mismatch" }
    }

    /** ARGB color ints (non-premultiplied), for Bitmap.setPixels. */
    fun toArgb(out: IntArray = IntArray(width * height)): IntArray {
        data.argb?.let { System.arraycopy(it, 0, out, 0, width * height); return out }
        val b = data.bytes
        var j = 0
        for (i in 0 until width * height) {
            val r = b[j].toInt() and 0xff
            val g = b[j + 1].toInt() and 0xff
            val bl = b[j + 2].toInt() and 0xff
            val a = b[j + 3].toInt() and 0xff
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or bl
            j += 4
        }
        return out
    }

    fun setFromArgb(px: IntArray) {
        val b = data.bytes
        var j = 0
        for (i in 0 until width * height) {
            val c = px[i]
            b[j] = (c shr 16).toByte()
            b[j + 1] = (c shr 8).toByte()
            b[j + 2] = c.toByte()
            b[j + 3] = (c ushr 24).toByte()
            j += 4
        }
    }
}
