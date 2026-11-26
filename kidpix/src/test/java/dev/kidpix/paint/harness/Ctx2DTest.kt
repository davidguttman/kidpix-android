package dev.kidpix.paint.harness

import dev.kidpix.paint.History
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.canvas.CanvasPattern
import dev.kidpix.paint.canvas.CssColor
import dev.kidpix.paint.canvas.Ctx2D
import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.canvas.Uint8Clamped
import dev.kidpix.paint.engine.KRandom
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.VirtualClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Ctx2DTest {
    private fun px(c: KCanvas, x: Int, y: Int): IntArray {
        val d = c.getContext().getImageData(x, y, 1, 1).data
        return intArrayOf(d[0], d[1], d[2], d[3])
    }

    @Test fun getImageDataTruncatesFractionalArguments() {
        val c = KCanvas(20, 10)
        val ctx = c.getContext()
        ctx.fillStyle = "red"
        ctx.fillRect(10, 0, 1, 10)
        val d = ctx.getImageData(10.7, 0.9, 5.9, 2.5)
        assertEquals(5, d.width)
        assertEquals(2, d.height)
        assertEquals(255, d.data[0]) // starts at x 10 (truncated), not 11
        val n = ctx.getImageData(-1.5, 0, 2.5, 1)
        assertEquals(2, n.width) // x -1, w 2
        assertEquals(0, n.data[3])
    }

    @Test fun cssColors() {
        assertEquals(0xffff0000.toInt(), CssColor.parse("red"))
        assertEquals(0xff112233.toInt(), CssColor.parse("#123"))
        assertEquals(0x80ff0000.toInt(), CssColor.parse("rgba(255, 0, 0, 0.5)"))
        assertEquals(0xff000000.toInt(), CssColor.parse("rgb(0,    0,     0)"))
        assertEquals(0xff222034.toInt(), CssColor.parse("rgb( 34 , 32 ,52 )"))
        assertEquals(0xffff1a1a.toInt(), CssColor.parse("hsl(0, 100%, 55%)"))
        assertEquals(0, CssColor.parse("transparent"))
        assertNull(CssColor.parse("nonsense"))
    }

    @Test fun clampedArray() {
        val a = Uint8Clamped(4)
        a[0] = 300; a[1] = -5; a[2] = 2.5; a[3] = 3.5
        assertEquals(listOf(255, 0, 2, 4), (0..3).map { a[it] })
        a[0] = Double.NaN
        assertEquals(0, a[0])
    }

    @Test fun mulberry32MatchesReference() {
        KRandom.seed(1234)
        assertEquals(0.07329497812315822, KRandom.random(), 0.0)
        assertEquals(0.7034119898453355, KRandom.random(), 0.0)
        assertEquals(0.9028560190927237, KRandom.random(), 0.0)
    }

    @Test fun widthResetClears() {
        val c = KCanvas(10, 10)
        c.getContext().fillStyle = "blue"
        c.getContext().fillRect(0, 0, 10, 10)
        c.width = c.width
        assertEquals(0, px(c, 5, 5)[3])
        assertEquals("#000000", c.getContext().fillStyle)
    }

    @Test fun imageDataRoundTripIsStraightAlpha() {
        val c = KCanvas(4, 4)
        val ctx = c.getContext()
        val img = ctx.createImageData(4, 4)
        for (i in 0 until 16) { img.data[i * 4] = 200; img.data[i * 4 + 1] = 100; img.data[i * 4 + 2] = 50; img.data[i * 4 + 3] = 255 }
        ctx.putImageData(img, 0, 0)
        assertEquals(listOf(200, 100, 50, 255), px(c, 1, 1).toList())
    }

    @Test fun destinationInIsUnbounded() {
        val c = KCanvas(20, 20)
        val ctx = c.getContext()
        ctx.fillStyle = "red"; ctx.fillRect(0, 0, 20, 20)
        ctx.globalCompositeOperation = "destination-in"
        ctx.fillRect(0, 0, 5, 5)
        assertEquals(255, px(c, 2, 2)[3])
        assertEquals(0, px(c, 15, 15)[3]) // cleared outside the source, like the browser
    }

    @Test fun arcClockwiseFromTwoPiToZeroIsFullCircle() {
        val c = KCanvas(50, 50)
        val ctx = c.getContext()
        ctx.beginPath(); ctx.fillStyle = "black"
        ctx.arc(25, 25, 20, PI * 2, 0, false) // gco.js lightMix does this; Chrome draws a circle
        ctx.fill()
        assertEquals(255, px(c, 25, 25)[3])
        assertEquals(255, px(c, 10, 25)[3])
    }

    @Test fun patternRepeatsFromOrigin() {
        val p = KCanvas(2, 2)
        p.getContext().fillStyle = "black"; p.getContext().fillRect(0, 0, 1, 1)
        val c = KCanvas(8, 8)
        val ctx = c.getContext()
        ctx.fillStyle = ctx.createPattern(p, "repeat")
        ctx.fillRect(0, 0, 8, 8)
        assertEquals(255, px(c, 4, 4)[3])
        assertEquals(0, px(c, 5, 4)[3])
    }

    @Test fun virtualClockOrdersTimersAndFrames() {
        val clock = VirtualClock(); KTime.backend = clock
        val seen = ArrayList<String>()
        KTime.setTimeout({ seen.add("t20") }, 20)
        KTime.requestAnimationFrame { seen.add("raf") }
        val iv = KTime.setInterval({ seen.add("iv") }, 10)
        clock.advance(25)
        KTime.clearInterval(iv)
        clock.advance(100)
        // a rescheduled interval gets a new sequence number (as in ref_capture.py), so t20 runs first
        assertEquals(listOf("iv", "raf", "t20", "iv"), seen)
        assertEquals(VirtualClock.T0 + 125, KTime.now())
    }

    @Test fun undoRedoHistory() {
        KDisplay.init_layers(16, 16)
        val ctx = KDisplay.context
        repeat(35) { i ->
            ctx.fillStyle = "rgb($i, 0, 0)"; ctx.fillRect(0, 0, 16, 16); KDisplay.saveMain()
        }
        assertEquals(34, px(KDisplay.main_canvas, 0, 0)[0])
        var n = 0
        while (KDisplay.undo(true)) n++
        assertEquals(35, n) // no step limit (the frames are stored, log/Frames.kt)
        assertEquals(0, px(KDisplay.main_canvas, 0, 0)[3])
        assertTrue(KDisplay.redo(true))
        assertEquals(0, px(KDisplay.main_canvas, 0, 0)[0])
        ctx.fillStyle = "blue"; ctx.fillRect(0, 0, 4, 4); KDisplay.saveMain()
        assertFalse(KDisplay.redo(true))
    }

    /** Chrome draws characters Roboto and Symbols lack but NotoColorEmoji has (♠, 🐾) with the emoji font. */
    @Test fun emojiFallbackLikeChrome() {
        HarnessFonts.install()
        assertTrue(dev.kidpix.paint.canvas.EmojiFallback.useEmoji(0x2660)) // ♠: emoji font in Chrome
        assertTrue(dev.kidpix.paint.canvas.EmojiFallback.useEmoji(0x1F43E)) // paw prints
        assertFalse(dev.kidpix.paint.canvas.EmojiFallback.useEmoji(0x2717)) // ✗: NotoSansSymbols
        assertFalse(dev.kidpix.paint.canvas.EmojiFallback.useEmoji(0x2716)) // ✖: Symbols comes before emoji
        assertFalse(dev.kidpix.paint.canvas.EmojiFallback.useEmoji('A'.code))
        val emoji = android.graphics.Paint().apply { textSize = 36f; typeface = dev.kidpix.paint.canvas.EmojiFallback.emojiTypeface }
        val ctx = KCanvas(200, 100).getContext()
        ctx.font = "36px sans-serif"
        assertEquals(emoji.measureText("\u2660").toDouble(), ctx.measureText("\u2660").width, 0.01)
        assertEquals(emoji.measureText("\u2660").toDouble() * 2, ctx.measureText("\u2660\u2660").width, 0.01)
        // mixed text: the Latin part keeps Roboto's advance
        val plain = ctx.measureText("AB").width
        assertEquals(plain + emoji.measureText("\u2660").toDouble(), ctx.measureText("A\u2660B").width, 0.01)
        // centred on x: ink of an emoji spade spans both sides of the anchor
        ctx.textAlign = "center"; ctx.textBaseline = "middle"; ctx.fillStyle = "black"
        ctx.fillText("\u2660", 100, 50)
        assertTrue(px(ctx.canvas, 92, 50)[3] > 0 && px(ctx.canvas, 108, 50)[3] > 0)
    }

    /** Chrome: middle = middle of the em box, unrounded (paw prints: 0.24% vs 0.68% rounded); glyphs at sub-pixel x. */
    @Test fun textBaselineAndSubpixelLikeChrome() {
        val c = KCanvas(200, 100)
        val ctx = c.getContext()
        ctx.font = "36px sans-serif"; ctx.fillStyle = "black"; ctx.textBaseline = "middle"
        ctx.fillText("H", 20, 50) // Roboto 36px: ascent 33.4, descent 8.79: em box 28.5 / 7.5, baseline at 50 + 10.5
        assertTrue(px(c, 24, 60)[3] > 200) // the H stands on y 60.5 (Skia rounds a vertical text origin: 61)
        assertTrue(px(c, 24, 61)[3] < 10)  // hhea metrics (old code) put the baseline at 62 and ink on row 61
        val a = KCanvas(60, 60); val b = KCanvas(60, 60)
        for ((cv, x) in listOf(a to 10.0, b to 10.25)) {
            val k = cv.getContext(); k.font = "36px sans-serif"; k.fillStyle = "black"; k.fillText("H", x, 45)
        }
        // a quarter pixel to the right moves coverage; snapped origins would both land on x 10
        assertTrue((0 until 60).any { x -> px(a, x, 30)[3] != px(b, x, 30)[3] })
    }

    /**
     * One straight stroke is anti-aliased like Chrome's canvas (AaStroke): butt caps get a linear
     * ramp of |nx|+|ny| px per edge, edges multiplied; a 1 px line at 53 degrees peaks at 0.71 of
     * the colour over three pixels (Skia's hairline would give 0.97 over two).
     */
    @Test fun thinStrokeLikeChrome() {
        val c = KCanvas(200, 100) // GPU-sized (small canvases are CPU-rastered in Chrome)
        val ctx = c.getContext()
        ctx.strokeStyle = "black"; ctx.lineWidth = 1.0
        ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(30, 40); ctx.stroke() // 3:4 slope, |nx|+|ny| = 1.4
        // row 20: the line's centre is at x 15.375 (pixel centre y 20.5); pixel 15 is 0.15 px off it
        val row = (12..18).map { px(c, it, 20)[3] }
        assertEquals(0, row[0]); assertEquals(0, row[6])
        val peak = row.max()
        assertTrue("peak $peak in $row", peak in 160..195) // 0.63..0.76 coverage, not ~250
        assertTrue("spread $row", row.count { it > 20 } == 3)
        // a small canvas keeps Skia's CPU hairline, as Chrome's unaccelerated canvases do
        val small = KCanvas(60, 60)
        small.getContext().apply { strokeStyle = "black"; lineWidth = 1.0; beginPath(); moveTo(0, 0); lineTo(30, 40); stroke() }
        val srow = (12..18).map { px(small, it, 20)[3] }
        assertTrue("small $srow", srow.max() > 210 && srow.count { it > 20 } <= 2)
    }

    /** Round caps (and strokes under 1 px) are sampled at 4 points per pixel: coverage in quarters. */
    @Test fun roundCapStrokeIsFourSampleMsaa() {
        val c = KCanvas(200, 100)
        val ctx = c.getContext()
        ctx.strokeStyle = "black"; ctx.lineWidth = 9.0; ctx.lineCap = "round"
        ctx.beginPath(); ctx.moveTo(10, 10); ctx.lineTo(38, 48); ctx.stroke()
        var edge = 0
        for (y in 0 until 60) for (x in 0 until 60) {
            val a = px(c, x, y)[3]
            assertTrue("alpha $a at $x,$y", a in listOf(0, 64, 128, 191, 255) || a in listOf(63, 127, 192))
            if (a in 1..254) edge++
        }
        assertTrue(edge > 20)
    }

    /** A pattern stroke keeps the pattern anchored at the canvas origin (not at the stroke's box). */
    @Test fun patternStrokeStaysAnchored() {
        val tile = KCanvas(2, 1)
        tile.getContext().apply { fillStyle = "red"; fillRect(0, 0, 1, 1); fillStyle = "blue"; fillRect(1, 0, 1, 1) }
        val c = KCanvas(200, 100)
        val ctx = c.getContext()
        ctx.strokeStyle = ctx.createPattern(tile, "repeat"); ctx.lineWidth = 8.0
        ctx.beginPath(); ctx.moveTo(13, 10); ctx.lineTo(51, 10); ctx.stroke()
        for (x in 20..40) {
            val p = px(c, x, 10)
            if (x % 2 == 0) assertTrue("x $x red", p[0] == 255 && p[2] == 0) else assertTrue("x $x blue", p[2] == 255 && p[0] == 0)
        }
    }

    /** Chrome's canvas is a 4x MSAA surface: stroking the same polyline again does not darken its edges. */
    @Test fun msaaRestrokeDoesNotAccumulate() {
        fun draw(times: Int): KCanvas {
            val c = KCanvas(200, 100)
            val ctx = c.getContext()
            ctx.strokeStyle = "blue"; ctx.lineWidth = 1.0
            ctx.beginPath(); ctx.moveTo(10, 10)
            ctx.lineTo(60, 40); ctx.lineTo(120, 20); ctx.lineTo(190, 90)
            repeat(times) { ctx.stroke() }
            return c
        }
        val once = draw(1); val many = draw(12)
        var edge = 0
        for (y in 0 until 100) for (x in 0 until 200) {
            val a = px(once, x, y)[3]
            assertEquals("pixel $x,$y", a, px(many, x, y)[3])
            assertTrue("alpha $a at $x,$y", a in listOf(0, 64, 128, 191, 255))
            if (a in 1..254) edge++
        }
        assertTrue(edge > 50)
    }

    /**
     * A growing path restroked on every move (zig-zag: butt caps, miter joins, a new 1x1 pattern
     * each time; kaleidoscope: round caps/joins, 4 subpaths a move under a translate) gives the
     * same pixels whether each restroke strokes the whole path or only its new part (apptest).
     */
    @Test fun msaaIncrementalRestrokeIsExact() {
        fun pattern(color: String): CanvasPattern {
            val t = KCanvas(1, 1); t.getContext().apply { fillStyle = color; fillRect(0, 0, 1, 1) }
            return KCanvas(1, 1).getContext().createPattern(t, "repeat")!!
        }
        fun zigzag(incremental: Boolean): KCanvas {
            Ctx2D.msaaIncremental = incremental
            val c = KCanvas(400, 300); val ctx = c.getContext()
            val r = java.util.Random(7)
            ctx.beginPath(); ctx.moveTo(20, 20)
            for (k in 0 until 120) {
                ctx.lineTo(20 + r.nextDouble() * 360, 20 + r.nextDouble() * 260)
                ctx.strokeStyle = pattern("#2050c0"); ctx.lineWidth = 1.0; ctx.stroke()
            }
            return c
        }
        fun kaleido(incremental: Boolean, color: String): KCanvas {
            Ctx2D.msaaIncremental = incremental
            val c = KCanvas(400, 300); val ctx = c.getContext()
            ctx.strokeStyle = pattern(color); ctx.lineWidth = 2.0
            ctx.beginPath(); ctx.save(); ctx.lineCap = "round"; ctx.lineJoin = "round"
            ctx.translate(200.1, 150.1); ctx.moveTo(0, 0)
            var px = 0.0; var py = 0.0
            for (k in 0 until 80) {
                val x = 120 * kotlin.math.sin(k / 9.0); val y = 100 * kotlin.math.cos(k / 13.0)
                ctx.moveTo(px, py); ctx.lineTo(x, y); ctx.moveTo(-px, py); ctx.lineTo(-x, y)
                ctx.moveTo(px, -py); ctx.lineTo(x, -y); ctx.moveTo(-px, -py); ctx.lineTo(-x, -y)
                ctx.stroke(); px = x; py = y
            }
            ctx.restore()
            return c
        }
        try {
            // Skia clips the outline's edges to the window it rasterises, which can move a sample
            // that sits within float rounding of an edge: allow a rare one-sample difference.
            fun same(a: KCanvas, b: KCanvas, what: String) {
                var ink = 0; var diff = 0
                for (y in 0 until a.height) for (x in 0 until a.width) {
                    val p = a.bitmap.getPixel(x, y); val q = b.bitmap.getPixel(x, y)
                    if (p != 0) ink++
                    if (p != q) {
                        diff++
                        val d = maxOf(kotlin.math.abs((p ushr 24) - (q ushr 24)), kotlin.math.abs(((p shr 16) and 255) - ((q shr 16) and 255)))
                        assertTrue("$what at $x,$y: ${Integer.toHexString(p)} vs ${Integer.toHexString(q)}", d <= 65)
                    }
                }
                var more = 0; var less = 0; val ex = ArrayList<String>()
                for (y in 0 until a.height) for (x in 0 until a.width) {
                    val p = a.bitmap.getPixel(x, y) ushr 24; val q = b.bitmap.getPixel(x, y) ushr 24
                    if (q > p) { more++; if (ex.size < 12) ex.add("$x,$y $p->$q") } else if (q < p) less++
                }
                println("$what: ink $ink, differing $diff, incremental more $more less $less e.g. $ex")
                assertTrue("$what has ink", ink > 500)
                assertTrue("$what: $diff of $ink differ", diff * 1000 <= ink)
            }
            same(zigzag(false), zigzag(true), "zig-zag")
            same(kaleido(false, "red"), kaleido(true, "red"), "kaleidoscope")
            // a translucent colour accumulates on restroke (as in Chrome): never incremental
            same(kaleido(false, "rgba(255,0,0,0.5)"), kaleido(true, "rgba(255,0,0,0.5)"), "translucent")
        } finally { Ctx2D.msaaIncremental = true }
    }

    /**
     * Thin round-capped twigs (the tree brush: some 57,000 per tap) are sampled directly instead
     * of through Skia's outline: still exact quarters, still no build-up when drawn again.
     */
    @Test fun thinSegmentsSampledDirectly() {
        fun twigs(on: Boolean): KCanvas {
            val c = KCanvas(300, 200); val ctx = c.getContext()
            ctx.lineCap = "round"; ctx.strokeStyle = "rgb(0,150,0)"
            val r = java.util.Random(5)
            repeat(400) {
                val x = 20 + r.nextDouble() * 260; val y = 20 + r.nextDouble() * 160; val a = r.nextDouble() * 6.28
                ctx.lineWidth = 0.3 + r.nextDouble() * 3.0
                ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x + 9 * kotlin.math.cos(a), y + 9 * kotlin.math.sin(a))
                if (on) ctx.stroke() else { ctx.stroke(); ctx.stroke() }
            }
            return c
        }
        val once = twigs(true); val twice = twigs(false)
        var ink = 0
        for (y in 0 until 200) for (x in 0 until 300) {
            val a = px(once, x, y)[3]
            assertTrue("alpha $a at $x,$y", a in listOf(0, 64, 128, 191, 255))
            assertEquals("restroke at $x,$y", a, px(twice, x, y)[3])
            if (a != 0) ink++
        }
        assertTrue(ink > 2000)
    }

    /** batchStrokes gives the same pixels as stroking one by one; reads and other draws inside see the strokes. */
    @Test fun batchedStrokesAreExact() {
        fun draw(c: KCanvas, batched: Boolean, mid: (Ctx2D) -> Unit = {}) {
            val ctx = c.getContext()
            ctx.fillStyle = "rgb(200,30,30)"; ctx.fillRect(40, 40, 60, 50)
            val body = {
                val r = java.util.Random(7)
                repeat(3000) { n ->
                    val x = 20 + r.nextDouble() * 260; val y = 20 + r.nextDouble() * 160; val a = r.nextDouble() * 6.28
                    ctx.lineCap = if (n % 7 == 0) "butt" else "round"
                    ctx.lineWidth = if (n % 50 == 0) 9.0 else 0.2 + r.nextDouble() * 3.5
                    ctx.strokeStyle = if (n % 11 == 0) "rgba(0,0,200,0.5)" else "rgb(0," + (64 + r.nextInt(128)) + ",20)"
                    ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x + 7 * kotlin.math.cos(a), y + 7 * kotlin.math.sin(a)); ctx.stroke()
                    if (n == 1500) mid(ctx)
                }
            }
            if (batched) ctx.batchStrokes(body) else body()
        }
        val plain = KCanvas(300, 200).also { draw(it, false) }
        val batched = KCanvas(300, 200).also { draw(it, true) }
        assertTrue(plain.bitmap.sameAs(batched.bitmap))
        // a read and a fill in the middle of a batch
        var seen: IntArray? = null; var seenPlain: IntArray? = null
        val mid = { into: (IntArray) -> Unit -> { ctx: Ctx2D ->
            val d = ctx.getImageData(0, 0, 300, 200).data; into(IntArray(300 * 200 * 4) { d[it] })
            ctx.fillStyle = "rgba(255,255,0,0.5)"; ctx.fillRect(100, 60, 80, 70)
        } }
        val p2 = KCanvas(300, 200).also { draw(it, false, mid { seenPlain = it }) }
        val b2 = KCanvas(300, 200).also { draw(it, true, mid { seen = it }) }
        assertTrue(seenPlain!!.contentEquals(seen!!))
        assertTrue(p2.bitmap.sameAs(b2.bitmap))
        assertFalse(p2.bitmap.sameAs(plain.bitmap))
    }

    /** AaStroke over bands of its covered spans gives what the whole bounding box gave (a long diagonal). */
    @Test fun aaStrokeLongDiagonalCoverage() {
        val c = KCanvas(1136, 798); val ctx = c.getContext()
        ctx.strokeStyle = "black"; ctx.lineWidth = 3.0
        ctx.beginPath(); ctx.moveTo(5.3, 7.7); ctx.lineTo(1100.2, 790.4); ctx.stroke()
        // every pixel whose centre is within 0.5 px of the line's middle is nearly full; far ones empty
        val nx = -(790.4 - 7.7); val ny = 1100.2 - 5.3; val l = kotlin.math.hypot(nx, ny)
        var full = 0
        for (y in 0 until 798 step 3) for (x in 0 until 1136) {
            val d = kotlin.math.abs(((x + 0.5) - 5.3) * nx + ((y + 0.5) - 7.7) * ny) / l
            val a = px(c, x, y)[3]
            if (x in 10..1095 && d < 0.4) { assertTrue("core $x,$y a=$a", a > 200); full++ }
            if (d > 3.5) assertEquals("outside $x,$y", 0, a)
        }
        assertTrue(full > 200)
    }

    /** First row with ink of "H" at 100px serif drawn at (20, y) with [baseline]. */
    private fun firstInkRow(baseline: String, y: Double): Int {
        val c = KCanvas(200, 200)
        val ctx = c.getContext()
        ctx.font = "100px serif"; ctx.fillStyle = "black"; ctx.textBaseline = baseline
        ctx.fillText("H", 20, y)
        val d = ctx.getImageData(0, 0, 200, 200).data
        for (row in 0 until 200) for (col in 0 until 200) if (d[(row * 200 + col) * 4 + 3] > 128) return row
        return -1
    }

    /** Blink puts textBaseline 'top' at the em box top: ascent * size / (ascent + descent) (audit finding). */
    @Test fun textBaselineTopIsEmBoxTop() {
        val p = android.graphics.Paint().apply { textSize = 100f; typeface = android.graphics.Typeface.SERIF }
        val fm = p.fontMetrics
        val emAscent = 100.0 * -fm.ascent / (fm.descent - fm.ascent)
        assertEquals(firstInkRow("alphabetic", emAscent), firstInkRow("top", 0.0))
    }

    /** middle = em box middle, hanging = 80% of the ascent, bottom = em box bottom, ideographic = descent. */
    @Test fun textBaselinesOtherThanTop() {
        val p = android.graphics.Paint().apply { textSize = 100f; typeface = android.graphics.Typeface.SERIF }
        val fm = p.fontMetrics
        val emAscent = 100.0 * -fm.ascent / (fm.descent - fm.ascent)
        val emDescent = 100.0 - emAscent
        val y = 100.0
        assertEquals(firstInkRow("alphabetic", y + (emAscent - emDescent) / 2), firstInkRow("middle", y))
        assertEquals(firstInkRow("alphabetic", y - fm.ascent * 0.8), firstInkRow("hanging", y))
        assertEquals(firstInkRow("alphabetic", y - emDescent), firstInkRow("bottom", y))
        assertEquals(firstInkRow("alphabetic", y - fm.descent), firstInkRow("ideographic", y))
        // they are distinct (the old code grouped top/hanging and bottom/ideographic)
        assertTrue(firstInkRow("top", y) != firstInkRow("hanging", y))
        assertTrue(firstInkRow("bottom", y) != firstInkRow("ideographic", y))
    }
}
