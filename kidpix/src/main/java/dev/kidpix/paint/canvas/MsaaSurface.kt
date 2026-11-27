package dev.kidpix.paint.canvas

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The 4x multisampled surface behind a canvas Chrome accelerates, for the strokes Chrome draws
 * with MSAA (see AaStroke for which): each pixel such a stroke touches keeps four sample colours
 * (premultiplied), the stroke's exact outline is tested at the four sample points, covered samples
 * get the colour source-over, and the pixel is the average of its samples.
 *
 * What it reproduces (measured on the references): edge pixels are exact quarters of the colour,
 * and drawing the same outline again does not darken them (brush-01-zig-zag strokes its whole
 * growing path on every move; the pencil's round caps overlap at every joint): a sample that is
 * already the colour stays the colour.
 *
 * Samples are kept per 32x32 tile, only where MSAA strokes went. A pixel's samples are trusted only
 * while the bitmap still holds the value written from them; anything else that changed the pixel
 * (fills, images, clears, analytic strokes, other code writing the bitmap) resets its samples to
 * the pixel's value, as a draw with full-pixel coverage would.
 */
internal class MsaaSurface(private val w: Int, private val h: Int) {
    private val tilesX = (w + T - 1) / T
    private val tiles = arrayOfNulls<IntArray>(tilesX * ((h + T - 1) / T))

    // Batching (Ctx2D.batchStrokes): while [batching], the strokes read and write a copy of the
    // bitmap's pixels (premultiplied, as [premul] packs them) instead of the bitmap: no crop, no
    // write-back, no JNI per stroke. [flush] writes the changed box back. The result is the same as
    // stroking one by one: the same sample arithmetic on the same pixel values, in the same order.
    var batching = false
    private var batchPx: IntArray? = null
    private var bl = 0; private var bt = 0; private var br = 0; private var bb = 0

    /**
     * Where a batch's strokes will go, when the caller knows (Ctx2D.batchStrokes(region)): only
     * that part of the bitmap is copied in and written back. Strokes reaching outside it are not
     * batched (they take the unbatched way, after the batch is written back).
     */
    var batchRegion: android.graphics.Rect? = null
    private var regionCopied: android.graphics.Rect? = null

    private fun batchPixels(bitmap: Bitmap): IntArray? {
        if (!batching) return null
        batchPx?.let { return it }
        if (bitmap.width != w || bitmap.height != h || bitmap.rowBytes != w * 4) return null
        val r = batchRegion?.let { android.graphics.Rect(max(0, it.left), max(0, it.top), min(w, it.right), min(h, it.bottom)) }
        val px: IntArray
        if (r != null && !r.isEmpty && r.width().toLong() * r.height() * 2 < w.toLong() * h) {
            px = batchArray(w * h)
            val rw = r.width(); val rh = r.height()
            val crop = scratch(1, rw, rh)
            scratchCanvas(1, crop).drawBitmap(bitmap, -r.left.toFloat(), -r.top.toFloat(), copy)
            val cp = premul(crop, ints(3, rw * rh))
            for (y in 0 until rh) System.arraycopy(cp, y * rw, px, (r.top + y) * w + r.left, rw)
            regionCopied = r
        } else {
            px = premul(bitmap, batchArray(w * h))
            regionCopied = null
        }
        batchPx = px; bl = w; bt = h; br = 0; bb = 0
        return px
    }

    /** The batch can take a stroke over this box (inside the copied region, if any). */
    private fun batchHolds(left: Int, top: Int, right: Int, bottom: Int): Boolean {
        val r = regionCopied ?: batchRegion?.takeIf { batchPx == null } ?: return true
        // (clamped to the surface first: the region is too, and nothing outside it is drawn)
        return max(0, left) >= r.left && max(0, top) >= r.top && min(w, right) <= r.right && min(h, bottom) <= r.bottom
    }

    /** Writes what the batch changed back to [bitmap]; the box written, or null when nothing was. */
    fun flush(bitmap: Bitmap): android.graphics.Rect? {
        val px = batchPx ?: return null
        batchPx = null
        val region = regionCopied
        regionCopied = null
        if (br <= bl || bb <= bt) return null
        if (region != null) {
            // only the changed box, as a crop drawn back (the rest of the array is stale)
            val cw = br - bl; val ch = bb - bt
            val out = ints(4, cw * ch)
            for (y in 0 until ch) System.arraycopy(px, (bt + y) * w + bl, out, y * cw, cw)
            val ob = scratch(2, cw, ch)
            val bb2 = buffer(cw * ch * 4)
            bb2.asIntBuffer().put(out, 0, cw * ch)
            bb2.rewind(); ob.copyPixelsFromBuffer(bb2)
            val wc = writeCanvas ?: Canvas().also { writeCanvas = it }
            wc.setBitmap(bitmap)
            wc.drawBitmap(ob, bl.toFloat(), bt.toFloat(), copy)
            wc.setBitmap(null)
            return android.graphics.Rect(bl, bt, br, bb)
        }
        val buf = buffer(w * h * 4)
        buf.asIntBuffer().put(px, 0, w * h)
        buf.rewind(); bitmap.copyPixelsFromBuffer(buf)
        return android.graphics.Rect(bl, bt, br, bb)
    }
    private var writeCanvas: Canvas? = null

    /**
     * Strokes [outline] (device space, a filled stroke outline from Paint.getFillPath) onto
     * the bitmap through [c] with [paint]'s colour or shader; the shader is in the space of [ctm].
     * With [windows], only the pixels inside them are touched (they must not overlap).
     */
    fun fill(bitmap: Bitmap, c: Canvas, outline: Path, paint: Paint, ctm: android.graphics.Matrix, windows: List<android.graphics.Rect>? = null) {
        val b = RectF(); outline.computeBounds(b, true)
        val left = max(0, floor(b.left).toInt() - 1); val top = max(0, floor(b.top).toInt() - 1)
        val right = min(w, ceil(b.right).toInt() + 1); val bottom = min(h, ceil(b.bottom).toInt() + 1)
        if (right <= left || bottom <= top) return
        val boxes = if (windows == null) listOf(android.graphics.Rect(left, top, right, bottom))
        else windows.mapNotNull { r ->
            val x = android.graphics.Rect(max(left, r.left), max(top, r.top), min(right, r.right), min(bottom, r.bottom))
            if (x.width() > 0 && x.height() > 0) x else null
        }
        if (boxes.isEmpty()) return

        // coverage of the four sample points: a non-AA fill tests pixel centres, so shift the
        // outline by (0.5 - sx, 0.5 - sy) to test (i + sx, j + sy). Rasterised over the whole
        // canvas in canvas coordinates, whatever part is used: Skia re-rounds edges it clips to a
        // smaller target, which moves samples along them, so a window must not change the raster.
        val maskBmps = Array(4) { k ->
            val (m, mc) = sampleMask(k, w, h)
            mc.setMatrix(null)
            if (!maskDirty[k].isEmpty) { mc.drawRect(maskDirty[k], clear); maskDirty[k].setEmpty() }
            mc.translate(0.5f - SX[k], 0.5f - SY[k])
            mc.drawPath(outline, solid)
            maskDirty[k].set(left, top, right, bottom) // the outline's box (+1), all the fill can touch
            m
        }
        val shader = paint.shader
        val sp = Paint(paint).apply { style = Paint.Style.FILL; blendMode = null; isAntiAlias = false; clearShadowLayer() }
        // a plain colour: its premultiplied value, the same for every pixel
        val solidSrc = if (shader == null) premulColor(sp.color) else 0
        for (box in boxes) fillBox(bitmap, c, sp, shader == null, solidSrc, ctm, box.left, box.top, box.right, box.bottom) { cov, l, t, mw, mh ->
            val n = mw * mh
            if (n.toLong() * BIG_BOX > w.toLong() * h) {
                // a large box (a big oval or rectangle outline): read each mask whole as bytes (a
                // memcpy) instead of converting the box to ARGB ints four times
                for (k in 0 until 4) {
                    val m = maskBmps[k]
                    val stride = m.rowBytes
                    val mb = maskBytes(k, stride * m.height)
                    val bb = ByteBuffer.wrap(mb)
                    m.copyPixelsToBuffer(bb)
                    val bit = 1 shl k
                    for (j in 0 until mh) {
                        var q = (t + j) * stride + l
                        var o = j * mw
                        for (i in 0 until mw) { if (mb[q] != 0.toByte()) cov[o] = cov[o] or bit; q++; o++ }
                    }
                }
                return@fillBox
            }
            val px = ints(0, n)
            for (k in 0 until 4) {
                maskBmps[k].getPixels(px, 0, mw, l, t, mw, mh)
                val bit = 1 shl k
                for (i in 0 until n) if ((px[i] ushr 24) != 0) cov[i] = cov[i] or bit
            }
        }
    }

    /**
     * One thin straight stroked segment (device space, half width [hw], cap "butt", "square" or
     * "round"), sampled at the four points directly: a sample is covered when it lies inside the
     * segment's rectangle (extended by [hw] for square caps) or, for round caps, within [hw] of
     * the segment. Stroking the outline instead gives the same shape up to Skia's 1/64 px vertex
     * snapping, at four times the cost: a tree tap is some 57,000 such strokes.
     */
    fun fillSegment(bitmap: Bitmap, c: Canvas, x0: Double, y0: Double, x1: Double, y1: Double, hw: Double, cap: String, paint: Paint, ctm: android.graphics.Matrix) {
        val shader = paint.shader
        val sp = if (shader == null) paint else Paint(paint).apply { style = Paint.Style.FILL; blendMode = null; isAntiAlias = false; clearShadowLayer() }
        segment(bitmap, c, x0, y0, x1, y1, hw, cap, sp, shader == null, if (shader == null) paint.color else 0, ctm)
    }

    /**
     * [fillSegment] with a plain colour (ARGB, alpha applied) inside a batch, without a Paint;
     * false (nothing done) when the batch cannot hold this bitmap.
     */
    fun fillSegmentBatched(bitmap: Bitmap, x0: Double, y0: Double, x1: Double, y1: Double, hw: Double, cap: String, color: Int): Boolean {
        val ext = if (cap == "butt") 0.0 else hw
        if (!batchHolds(floor(min(x0, x1) - hw - ext).toInt() - 1, floor(min(y0, y1) - hw - ext).toInt() - 1,
                ceil(max(x0, x1) + hw + ext).toInt() + 1, ceil(max(y0, y1) + hw + ext).toInt() + 1)) return false
        if (batchPixels(bitmap) == null) return false
        segment(bitmap, null, x0, y0, x1, y1, hw, cap, null, true, color, null)
        return true
    }

    private fun segment(bitmap: Bitmap, c: Canvas?, x0: Double, y0: Double, x1: Double, y1: Double, hw: Double, cap: String, sp: Paint?, solid: Boolean, color: Int, ctm: android.graphics.Matrix?) {
        val dx = x1 - x0; val dy = y1 - y0
        val len = kotlin.math.hypot(dx, dy)
        val ext = if (cap == "butt") 0.0 else hw
        val left = max(0, floor(min(x0, x1) - hw - ext).toInt() - 1); val top = max(0, floor(min(y0, y1) - hw - ext).toInt() - 1)
        val right = min(w, ceil(max(x0, x1) + hw + ext).toInt() + 1); val bottom = min(h, ceil(max(y0, y1) + hw + ext).toInt() + 1)
        if (right <= left || bottom <= top) return
        val ux = if (len > 0) dx / len else 1.0; val uy = if (len > 0) dy / len else 0.0
        val hw2 = hw * hw
        val round = cap == "round"
        val solidSrc = if (solid) premulColor(color) else 0
        val bpx = if (solid) batchPixels(bitmap) else null
        if (bpx != null) { // batched: composite each covered pixel as it is found
            segmentCoverage(left, top, right - left, bottom - top, x0, y0, dx, dy, len, ux, uy, hw, hw2, ext, round) { i, j, bits ->
                composite(bpx, left + i, top + j, bits, solidSrc)
            }
            if (left < bl) bl = left; if (top < bt) bt = top; if (right > br) br = right; if (bottom > bb) bb = bottom
            return
        }
        fillBox(bitmap, c, sp, solid, solidSrc, ctm, left, top, right, bottom) { cov, l, t, mw, mh ->
            segmentCoverage(l, t, mw, mh, x0, y0, dx, dy, len, ux, uy, hw, hw2, ext, round) { i, j, bits -> cov[j * mw + i] = bits }
        }
    }

    /**
     * The covered samples of one segment over the box at [l], [t] of [mw] x [mh]: [emit] (column,
     * row, bits; bit k = sample k) for each pixel with any.
     */
    private inline fun segmentCoverage(
        l: Int, t: Int, mw: Int, mh: Int, x0: Double, y0: Double, dx: Double, dy: Double, len: Double,
        ux: Double, uy: Double, hw: Double, hw2: Double, ext: Double, round: Boolean, emit: (Int, Int, Int) -> Unit,
    ) {
        // Every covered sample lies within reach of the segment (round: hw; butt/square: the
        // rectangle's corners). Per row (samples at y + 0.125 .. 0.875), only the columns whose
        // samples (x + 0.125 .. 0.875) can come within reach of the part of the segment near the row
        // are tested; the rest stay 0. For round caps a pixel whose centre is further than
        // hw + 0.531 from the segment has no covered sample (samples are within 0.5303 of the centre)
        // and one nearer than hw - 0.531 has all four: the per-sample test runs only in between.
        val reach = (if (round) hw else 1.5 * hw + ext) + 1e-6
        val far2 = (hw + 0.531) * (hw + 0.531)
        val near = hw - 0.531
        val near2 = if (near > 0) near * near else -1.0
        for (j in 0 until mh) {
            val ya = t + j + 0.125 - reach - y0; val yb = t + j + 0.875 + reach - y0
            var s0 = 0.0; var s1 = 1.0
            if (kotlin.math.abs(dy) < 1e-12) { if (ya > 0.0 || yb < 0.0) continue }
            else {
                var p = ya / dy; var q = yb / dy
                if (p > q) { val tmp = p; p = q; q = tmp }
                if (p > s0) s0 = p; if (q < s1) s1 = q
                if (s0 > s1) continue
            }
            val xa = x0 + dx * s0; val xb = x0 + dx * s1
            val i0 = max(0, ceil(min(xa, xb) - reach - l - 0.875).toInt())
            val i1 = min(mw - 1, floor(max(xa, xb) + reach - l - 0.125).toInt())
            for (i in i0..i1) {
                if (round) {
                    val cx = l + i + 0.5 - x0; val cy = t + j + 0.5 - y0
                    val ca = cx * ux + cy * uy
                    val a = if (ca < 0) 0.0 else if (ca > len) len else ca
                    val ex = cx - a * ux; val ey = cy - a * uy
                    val d2 = ex * ex + ey * ey
                    if (d2 > far2) continue
                    if (d2 < near2) { emit(i, j, 15); continue }
                }
                var bits = 0
                for (k in 0 until 4) {
                    val px = l + i + SX[k] - x0; val py = t + j + SY[k] - y0
                    val along = px * ux + py * uy
                    val inside = if (round) {
                        val a = if (along < 0) 0.0 else if (along > len) len else along
                        val ex = px - a * ux; val ey = py - a * uy
                        ex * ex + ey * ey <= hw2
                    } else {
                        kotlin.math.abs(-px * uy + py * ux) <= hw && along >= -ext && along <= len + ext
                    }
                    if (inside) bits = bits or (1 shl k)
                }
                if (bits != 0) emit(i, j, bits)
            }
        }
    }

    /** Composites a plain colour (premultiplied [src]) at the samples [bits] of pixel x, y of the batch. */
    private fun composite(px: IntArray, x: Int, y: Int, bits: Int, src: Int) {
        val q = y * w + x
        val cur = px[q]
        val ti = (y / T) * tilesX + x / T
        val tile = tiles[ti] ?: IntArray(T * T * 5).also { tiles[ti] = it }
        val o = ((y % T) * T + x % T) * 5
        if (tile[o + 4] != cur) for (k in 0 until 4) tile[o + k] = cur
        for (k in 0 until 4) if ((bits shr k) and 1 != 0) tile[o + k] = srcOver(src, tile[o + k])
        val v = average(tile[o], tile[o + 1], tile[o + 2], tile[o + 3])
        tile[o + 4] = v
        px[q] = v
    }

    /** Composites [sp] onto the box's pixels at the samples [coverage] marks (bit k: sample k). */
    private inline fun fillBox(
        bitmap: Bitmap, c: Canvas?, sp: Paint?, solid: Boolean, solidSrc: Int,
        ctm: android.graphics.Matrix?, left: Int, top: Int, right: Int, bottom: Int,
        coverage: (cov: IntArray, left: Int, top: Int, w: Int, h: Int) -> Unit,
    ) {
        val mw = right - left; val mh = bottom - top; val n = mw * mh
        val cov = ints(1, n) // bit k: sample k covered
        java.util.Arrays.fill(cov, 0, n, 0)
        coverage(cov, left, top, mw, mh)
        if (!cov.any(n)) return
        // a plain colour over a large box: work on a copy of the whole bitmap, as a batch does, and
        // write it back once (fewer passes over the pixels than cropping the box out and back)
        val oneShot = solid && !batching && n.toLong() * BIG_BOX > w.toLong() * h
        if (oneShot) batching = true
        val bpx = if (solid) batchPixels(bitmap) else null
        if (bpx != null) {
            for (j in 0 until mh) for (i in 0 until mw) {
                val cv = cov[j * mw + i]
                if (cv != 0) composite(bpx, left + i, top + j, cv, solidSrc)
            }
            if (left < bl) bl = left; if (top < bt) bt = top; if (right > br) br = right; if (bottom > bb) bb = bottom
            if (oneShot) { flush(bitmap); batching = false }
            return
        }
        if (oneShot) batching = false
        if (batchPx != null) flush(bitmap) // a shader stroke in a batch: write the batch back first
        c!!; sp!!; ctm!!

        // the source colour of every pixel (the paint's colour, or its shader under ctm)
        val src = if (solid) null else {
            val sb = scratch(0, mw, mh)
            scratchCanvas(0, sb).apply { save(); drawColor(0, android.graphics.PorterDuff.Mode.CLEAR); translate(-left.toFloat(), -top.toFloat()); concat(ctm); drawPaint(sp); restore() }
            premul(sb, ints(2, n))
        }

        val crop = scratch(1, mw, mh)
        scratchCanvas(1, crop).drawBitmap(bitmap, -left.toFloat(), -top.toFloat(), copy)
        val cur = premul(crop, ints(3, n))
        val out = ints(4, n)
        System.arraycopy(cur, 0, out, 0, n)
        for (j in 0 until mh) for (i in 0 until mw) {
            val p = j * mw + i
            val cv = cov[p]
            if (cv == 0) continue
            val x = left + i; val y = top + j
            val tile = tiles[(y / T) * tilesX + x / T] ?: IntArray(T * T * 5).also { tiles[(y / T) * tilesX + x / T] = it }
            val o = ((y % T) * T + x % T) * 5
            if (tile[o + 4] != cur[p]) { // fresh tiles hold 0s: consistent with a transparent pixel
                for (k in 0 until 4) tile[o + k] = cur[p]
            }
            val sv = src?.get(p) ?: solidSrc
            for (k in 0 until 4) if ((cv shr k) and 1 != 0) tile[o + k] = srcOver(sv, tile[o + k])
            out[p] = average(tile[o], tile[o + 1], tile[o + 2], tile[o + 3])
            tile[o + 4] = out[p]
        }
        // write back exactly (premultiplied, no conversion): SRC copy of the changed rectangle
        val ob = scratch(2, mw, mh)
        val bb = buffer(n * 4)
        bb.asIntBuffer().put(out, 0, n)
        bb.rewind(); ob.copyPixelsFromBuffer(bb)
        c.save(); c.setMatrix(null)
        c.drawBitmap(ob, left.toFloat(), top.toFloat(), copy)
        c.restore()
    }

    /**
     * The samples that say more than their pixel (not all four equal to the value written from
     * them), as groups of six ints: pixel index, the four samples, that value. Every other pixel
     * behaves as a fresh surface makes it (its samples become the pixel's value when next used),
     * so this is all a checkpoint has to keep (log/EngineGraph.kt).
     */
    fun exportSamples(): IntArray {
        var out = IntArray(0); var n = 0
        for ((ti, tile) in tiles.withIndex()) {
            if (tile == null) continue
            val tx = (ti % tilesX) * T; val ty = (ti / tilesX) * T
            for (p in 0 until T * T) {
                val o = p * 5
                val v = tile[o + 4]
                if (tile[o] == v && tile[o + 1] == v && tile[o + 2] == v && tile[o + 3] == v) continue
                if (n + 6 > out.size) out = out.copyOf(maxOf(64, out.size * 2))
                out[n] = (ty + p / T) * w + tx + p % T
                System.arraycopy(tile, o, out, n + 1, 5)
                n += 6
            }
        }
        return out.copyOf(n)
    }

    /** Puts back an [exportSamples] (into a fresh surface). */
    fun importSamples(a: IntArray) {
        var i = 0
        while (i + 6 <= a.size) {
            val q = a[i]; val x = q % w; val y = q / w
            val ti = (y / T) * tilesX + x / T
            val tile = tiles[ti] ?: IntArray(T * T * 5).also { tiles[ti] = it }
            System.arraycopy(a, i + 1, tile, ((y % T) * T + x % T) * 5, 5)
            i += 6
        }
    }

    companion object {
        private const val T = 32
        /** A box over 1/BIG_BOX of the surface takes the whole-bitmap paths. */
        private const val BIG_BOX = 3
        private val maskByteArrays = arrayOfNulls<ByteArray>(4)
        private fun maskBytes(k: Int, n: Int): ByteArray = maskByteArrays[k]?.takeIf { it.size >= n } ?: ByteArray(n).also { maskByteArrays[k] = it }

        // one set of canvas-sized sample masks for all surfaces (the engine draws on one thread)
        private val maskBitmaps = arrayOfNulls<Bitmap>(4)
        private val maskCanvases = arrayOfNulls<Canvas>(4)
        private fun sampleMask(k: Int, w: Int, h: Int): Pair<Bitmap, Canvas> {
            val b = maskBitmaps[k]
            if (b != null && b.width == w && b.height == h) return b to maskCanvases[k]!!
            val nb = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            maskBitmaps[k] = nb; maskCanvases[k] = Canvas(nb)
            return nb to maskCanvases[k]!!
        }
        // 4x MSAA sample points (standard pattern, y mirrored: the GPU surface is bottom-up)
        private val SX = floatArrayOf(0.375f, 0.875f, 0.125f, 0.625f)
        private val SY = floatArrayOf(0.875f, 0.625f, 0.375f, 0.125f)
        private val solid = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
        private val clear = Paint().apply { blendMode = BlendMode.CLEAR }
        private val maskDirty = Array(4) { android.graphics.Rect() }

        // scratch arrays, bitmaps and a buffer reused across strokes (a tree is thousands of tiny ones)
        private val intPool = arrayOfNulls<IntArray>(5)
        // the batch's copy of the pixels: kept between batches (a fresh 3.4 MB array per tree tap
        // cost page faults and GC); one batch at a time (the engine draws on one thread)
        private var batchArr: IntArray? = null
        private fun batchArray(n: Int): IntArray = batchArr?.takeIf { it.size >= n } ?: IntArray(n).also { batchArr = it }
        private fun ints(slot: Int, n: Int): IntArray {
            val a = intPool[slot]
            return if (a != null && a.size >= n) a else IntArray(maxOf(n, 1024)).also { intPool[slot] = it }
        }
        private val bmpPool = arrayOfNulls<Bitmap>(3)
        private val canvasPool = arrayOfNulls<Canvas>(3)
        private fun scratchCanvas(slot: Int, b: Bitmap): Canvas {
            val cv = canvasPool[slot] ?: Canvas().also { canvasPool[slot] = it }
            cv.setBitmap(b) // after a reconfigure too
            return cv
        }
        private fun IntArray.any(n: Int): Boolean { for (i in 0 until n) if (this[i] != 0) return true; return false }
        /** A mutable ARGB bitmap of exactly w x h (reconfigured in place when it fits). */
        private fun scratch(slot: Int, w: Int, h: Int): Bitmap {
            val b = bmpPool[slot]
            if (b != null && !b.isRecycled && b.allocationByteCount >= w * h * 4) {
                if (b.width != w || b.height != h) b.reconfigure(w, h, Bitmap.Config.ARGB_8888)
                return b
            }
            b?.recycle()
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmpPool[slot] = it }
        }
        private var buf: ByteBuffer = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN)
        private fun buffer(bytes: Int): ByteBuffer {
            if (buf.capacity() < bytes) buf = ByteBuffer.allocate(maxOf(bytes, buf.capacity() * 2)).order(ByteOrder.BIG_ENDIAN)
            buf.clear(); buf.limit(bytes)
            return buf
        }

        /** A colour int as the raw premultiplied packing [premul] gives (bitmap byte order, Skia's rounding). */
        private var lastColor = 0
        private var lastPremul = 0
        private fun premulColor(c: Int): Int {
            if ((c ushr 24) == 255) { // opaque: the channels as they are, in the bitmap's byte order
                val rgb = (c shl 8) or 0xff
                return if (bgra) (rgb and 0x00ff00ff) or ((rgb ushr 16) and 0xff00) or ((rgb and 0xff00) shl 16) else rgb
            }
            return premulSkia(c)
        }
        private fun premulSkia(c: Int): Int {
            if (c == lastColor && c != 0) return lastPremul
            val one = scratch(0, 1, 1)
            one.eraseColor(0)
            Canvas(one).drawColor(c, android.graphics.PorterDuff.Mode.SRC)
            lastPremul = premul(one, IntArray(1))[0]; lastColor = c
            return lastPremul
        }
        /** N32 bitmaps hold B, G, R, A in memory here (x86 hosts) rather than R, G, B, A (ARM devices). */
        private val bgra: Boolean by lazy {
            lastColor = 0
            premulSkia(0xff0000ff.toInt()) == 0xff0000ff.toInt() // opaque blue: B first in memory
        }
        private val copy = Paint().apply { blendMode = BlendMode.SRC; isFilterBitmap = false }

        /** Raw premultiplied pixels as RGBA-packed ints (byte order R, G, B, A), into [out]. */
        private fun premul(b: Bitmap, out: IntArray): IntArray {
            val n = b.width * b.height
            val bb = buffer(b.rowBytes * b.height)
            b.copyPixelsToBuffer(bb); bb.rewind()
            val stride = b.rowBytes / 4
            val ib = bb.asIntBuffer()
            if (stride == b.width) { ib.get(out, 0, n); return out }
            for (y in 0 until b.height) { ib.position(y * stride); ib.get(out, y * b.width, b.width) }
            return out
        }

        private fun srcOver(s: Int, d: Int): Int {
            val sa = s and 0xff
            if (sa == 255) return s
            if (sa == 0) return d
            val inv = 255 - sa
            fun ch(sh: Int): Int {
                val v = ((s ushr sh) and 0xff) + (((d ushr sh) and 0xff) * inv + 127) / 255
                return min(255, v) shl sh
            }
            return ch(24) or ch(16) or ch(8) or ch(0)
        }

        private fun average(a: Int, b: Int, c: Int, d: Int): Int {
            fun ch(sh: Int): Int = ((((a ushr sh) and 0xff) + ((b ushr sh) and 0xff) + ((c ushr sh) and 0xff) + ((d ushr sh) and 0xff) + 2) / 4) shl sh
            return ch(24) or ch(16) or ch(8) or ch(0)
        }
    }
}
