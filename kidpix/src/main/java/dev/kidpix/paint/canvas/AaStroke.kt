package dev.kidpix.paint.canvas

import android.graphics.Bitmap
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Coverage of one straight stroked segment the way Chrome's canvas (GPU raster) anti-aliases it.
 *
 * Measured on the reference pictures (brush-18-xy-to-xy, brush-21-starburst): every edge of the
 * stroke gets a linear coverage ramp across |nx| + |ny| pixels (n = the edge normal: the width a
 * pixel square has in that direction, as with a screen-space fwidth), centred on the edge, and the
 * ramps of the edges are multiplied. For a 1 px line at 53° that gives a peak of 0.71 spread over
 * three pixels; Skia's CPU rasteriser draws the same line as a hairline (peak 0.97, two pixels),
 * and its area coverage of a thin stroke peaks at 0.90.
 *
 * Only for a single segment, butt or square caps, at least 1 px wide (a convex quad). Thinner
 * strokes, round caps (curved outline), polylines and dashes are 4x MSAA in Chrome: MsaaSurface.
 * The pencil (round caps, 9 px) and the tree brush's twigs (0.3 px) show it: edge pixels are
 * exactly 1/4, 1/2 or 3/4 of the colour.
 *
 * Coordinates are device pixels; pixel (i, j) is sampled at its centre (i + 0.5, j + 0.5).
 */
internal object AaStroke {
    /**
     * cap: "butt" or "square". Gives the coverage band by band (at most [BAND] rows, each only as
     * wide as that band's covered pixels: a long diagonal line costs its own area, not its bounding
     * box's) to [emit] as (left, top, width, height, mask): the mask's top-left [width] x [height]
     * pixels hold the band; outside them it is 0. The mask is one scratch bitmap reused for every
     * band and stroke (draw it before returning). Nothing is emitted when nothing is covered.
     */
    /** False when [bands] can emit nothing (no length, no width, or wholly outside the clip). */
    fun mayCover(x0: Double, y0: Double, x1: Double, y1: Double, width: Double, cap: String, clipW: Int, clipH: Int): Boolean {
        if (hypot(x1 - x0, y1 - y0) < 1e-9 || !(width > 0)) return false
        val ext = if (cap == "butt") 0.0 else width / 2
        val reach = width / 2 + 1.5
        return max(x0, x1) + reach + ext + 1 > 0 && max(y0, y1) + reach + ext + 1 > 0 &&
            min(x0, x1) - reach - ext < clipW && min(y0, y1) - reach - ext < clipH
    }

    fun bands(x0: Double, y0: Double, x1: Double, y1: Double, width: Double, cap: String, clipW: Int, clipH: Int, emit: (Int, Int, Int, Int, Bitmap) -> Unit) {
        val dx = x1 - x0; val dy = y1 - y0
        val len = hypot(dx, dy)
        if (len < 1e-9 || !(width > 0)) return
        val ux = dx / len; val uy = dy / len
        val nx = -uy; val ny = ux
        val hw = width / 2
        val rs = abs(nx) + abs(ny) // ramp across the sides
        val rc = abs(ux) + abs(uy) // ramp across butt/square ends
        val ext = if (cap == "butt") 0.0 else hw
        val reach = hw + 1.5 // nothing is covered further than this from the segment's rectangle
        val left = max(0, floor(min(x0, x1) - reach - ext).toInt())
        val top = max(0, floor(min(y0, y1) - reach - ext).toInt())
        val right = min(clipW, ceil(max(x0, x1) + reach + ext).toInt() + 1)
        val bottom = min(clipH, ceil(max(y0, y1) + reach + ext).toInt() + 1)
        if (right <= left || bottom <= top) return
        val tLo = -reach - ext; val tHi = len + reach + ext

        // per row: the columns i where |d| <= reach and tLo <= t <= tHi can hold (one spare
        // column each side; the per-pixel test below decides, exactly as over the whole box)
        fun span(py: Double, out: IntArray): Boolean {
            var lo = Double.NEGATIVE_INFINITY; var hi = Double.POSITIVE_INFINITY
            fun cut(k: Double, c: Double, a: Double, b: Double): Boolean { // a <= px * k + c <= b
                if (abs(k) < 1e-12) return c in a..b
                var p = (a - c) / k; var q = (b - c) / k
                if (p > q) { val t = p; p = q; q = t }
                lo = max(lo, p); hi = min(hi, q); return true
            }
            if (!cut(nx, py * ny, -reach, reach) || !cut(ux, py * uy, tLo, tHi) || lo > hi) return false
            // px = left + i + 0.5 - x0
            val i0 = max(left, floor(lo + x0 - 0.5).toInt() - 1)
            val i1 = min(right - 1, ceil(hi + x0 - 0.5).toInt() + 1)
            if (i0 > i1) return false
            out[0] = i0; out[1] = i1; return true
        }

        // per row: the columns whose centre is inside every edge by at least half its ramp, i.e.
        // coverage exactly 1 (written as 255 without the per-pixel sums; a thick line is mostly these)
        val dIn = hw - rs / 2 - 1e-7
        val tIn0 = rc / 2 - ext + 1e-7; val tIn1 = len + ext - rc / 2 - 1e-7
        fun inner(py: Double, out: IntArray): Boolean {
            if (dIn <= 0 || tIn1 <= tIn0) return false
            var lo = Double.NEGATIVE_INFINITY; var hi = Double.POSITIVE_INFINITY
            fun cut(k: Double, c: Double, a: Double, b: Double): Boolean {
                if (abs(k) < 1e-12) return c in a..b
                var p = (a - c) / k; var q = (b - c) / k
                if (p > q) { val t = p; p = q; q = t }
                lo = max(lo, p); hi = min(hi, q); return true
            }
            if (!cut(nx, py * ny, -dIn, dIn) || !cut(ux, py * uy, tIn0, tIn1) || lo > hi) return false
            val i0 = ceil(lo + x0 - 0.5).toInt(); val i1 = floor(hi + x0 - 0.5).toInt()
            if (i0 > i1) return false
            out[0] = i0; out[1] = i1; return true
        }
        val inn = IntArray(2)

        val sp = IntArray(2)
        val rowLo = IntArray(BAND); val rowHi = IntArray(BAND)
        val bmp = scratch(clipW)
        val stride = bmp.rowBytes
        val bytes = scratchBytes
        var bandTop = top
        while (bandTop < bottom) {
            val bh = min(BAND, bottom - bandTop)
            var bl = Int.MAX_VALUE; var br = Int.MIN_VALUE
            for (r in 0 until bh) {
                if (span(bandTop + r + 0.5 - y0, sp)) { rowLo[r] = sp[0]; rowHi[r] = sp[1]; bl = min(bl, sp[0]); br = max(br, sp[1]) }
                else { rowLo[r] = 1; rowHi[r] = 0 }
            }
            if (bl <= br) {
                val w = br - bl + 1
                java.util.Arrays.fill(bytes, 0, stride * BAND, 0)
                var any = false
                for (r in 0 until bh) {
                    val py = bandTop + r + 0.5 - y0
                    var in0 = Int.MAX_VALUE; var in1 = Int.MIN_VALUE
                    if (inner(py, inn)) { in0 = max(inn[0], rowLo[r]); in1 = min(inn[1], rowHi[r]) }
                    if (in0 <= in1) { java.util.Arrays.fill(bytes, r * stride + (in0 - bl), r * stride + (in1 - bl) + 1, (-1).toByte()); any = true }
                    for (x in rowLo[r]..rowHi[r]) {
                        if (x in in0..in1) continue
                        val px = x + 0.5 - x0
                        val d = px * nx + py * ny
                        if (abs(d) > reach) continue
                        val t = px * ux + py * uy
                        if (t < tLo || t > tHi) continue
                        val c = ramp((hw - d) / rs) * ramp((hw + d) / rs) * ramp((t + ext) / rc) * ramp((len + ext - t) / rc)
                        if (c <= 0.0) continue
                        val v = (c * 255 + 0.5).toInt()
                        if (v > 0) { bytes[r * stride + (x - bl)] = min(255, v).toByte(); any = true }
                    }
                }
                if (any) { bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes, 0, stride * BAND)); emit(bl, bandTop, w, bh, bmp) }
            }
            bandTop += bh
        }
    }

    @PublishedApi internal var scratchBmp: Bitmap? = null
    @PublishedApi internal var scratchBytes = ByteArray(0)
    /** One ALPHA_8 band as wide as the canvas, and its bytes (the engine draws on one thread). */
    @PublishedApi internal fun scratch(w: Int): Bitmap {
        val b = scratchBmp
        if (b != null && b.width >= w) return b
        val nb = Bitmap.createBitmap(maxOf(w, 1), BAND, Bitmap.Config.ALPHA_8)
        scratchBmp = nb
        scratchBytes = ByteArray(nb.rowBytes * BAND)
        return nb
    }

    const val BAND = 16

    /** Coverage for a signed distance (in ramp widths) inside an edge: 0.5 on the edge, linear. */
    @PublishedApi internal fun ramp(s: Double): Double = if (s >= 0.5) 1.0 else if (s <= -0.5) 0.0 else s + 0.5
}
