package dev.kidpix.paint.tools

import dev.kidpix.paint.Ev
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KiddoPaint
import dev.kidpix.paint.Flood
import dev.kidpix.paint.init_kiddo_defaults
import dev.kidpix.paint.engine.KRandom
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.VirtualClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FloodTest {
    private val w = 1136
    private val h = 798

    @Before
    fun setUp() {
        val clock = VirtualClock()
        KTime.backend = clock
        KTime.origin = VirtualClock.T0
        KiddoPaint.resetWorld()
        KDisplay.store = null
        KDisplay.init_layers(w, h)
        init_kiddo_defaults()
        KRandom.seed(1)
    }

    private fun mainArgb(): IntArray {
        val px = IntArray(w * h)
        KDisplay.main_canvas.bitmap.getPixels(px, 0, w, 0, 0, w, h)
        return px
    }

    @Test
    fun fullCanvasFillIsFastAndComplete() {
        KiddoPaint.Current.color = "rgb(255, 0, 0)"
        // warm up (class loading, JIT) on a small fill, then time a full blank-canvas fill
        KiddoPaint.Tools.Flood.mousedown(Ev(10.0, 10.0))
        KDisplay.main_context.clearRect(0, 0, w, h)
        val t0 = System.nanoTime()
        KiddoPaint.Tools.Flood.mousedown(Ev(500.0, 400.0))
        val ms = (System.nanoTime() - t0) / 1e6
        println("FLOOD full-canvas fill ${w}x$h: ${"%.1f".format(ms)} ms")
        val px = mainArgb()
        assertTrue(px.all { it == 0xffff0000.toInt() })
        assertTrue("full-canvas fill took $ms ms", ms < 2000)
    }

    @Test
    fun fractionalCoordinatesFloorToWholePixels() {
        KiddoPaint.Current.color = "rgb(0, 0, 255)"
        KiddoPaint.Tools.Flood.mousedown(Ev(500.6, 400.3))
        assertTrue(mainArgb().all { it == 0xff0000ff.toInt() })
        // outside the canvas: nothing happens
        KiddoPaint.Current.color = "rgb(0, 255, 0)"
        KiddoPaint.Tools.Flood.mousedown(Ev(-3.0, 4000.0))
        assertTrue(mainArgb().all { it == 0xff0000ff.toInt() })
    }

    /** The scanline fill must cover exactly the 4-connected region of the start color. */
    @Test
    fun matchesFourConnectedRegion() {
        val ctx = KDisplay.main_context
        // a maze of random rectangles, lines and dots in a few opaque colors, plus holes
        val colors = listOf("black", "rgb(0, 0, 255)", "white")
        for (i in 0 until 400) {
            ctx.fillStyle = colors[(KRandom.random() * colors.size).toInt()]
            val x = (KRandom.random() * w).toInt()
            val y = (KRandom.random() * h).toInt()
            val rw = 1 + (KRandom.random() * 120).toInt()
            val rh = 1 + (KRandom.random() * 120).toInt()
            if (i % 3 == 0) ctx.fillRect(x, y, rw, 1) else if (i % 3 == 1) ctx.fillRect(x, y, 1, rh) else ctx.fillRect(x, y, rw / 4 + 1, rh / 4 + 1)
        }
        val before = mainArgb()
        for ((sx, sy) in listOf(5 to 5, 300 to 200, 1000 to 700, 1135 to 797)) {
            val start = before[sy * w + sx]
            // reference: plain BFS over 4 neighbours with exact color match
            val want = BooleanArray(w * h)
            val q = ArrayDeque<Int>()
            want[sy * w + sx] = true; q.add(sy * w + sx)
            while (q.isNotEmpty()) {
                val p = q.removeFirst(); val x = p % w; val y = p / w
                for ((nx, ny) in listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val n = ny * w + nx
                    if (!want[n] && before[n] == start) { want[n] = true; q.add(n) }
                }
            }
            // restore and fill with a color that is nowhere in the picture
            KDisplay.main_canvas.bitmap.setPixels(before, 0, w, 0, 0, w, h)
            KiddoPaint.Current.color = "rgb(12, 200, 34)"
            KiddoPaint.Tools.Flood.mousedown(Ev(sx.toDouble(), sy.toDouble()))
            val after = mainArgb()
            val fill = 0xff0cc822.toInt()
            var bad = 0
            for (i in 0 until w * h) {
                val expected = if (want[i]) fill else before[i]
                if (after[i] != expected) bad++
            }
            assertEquals("pixels differing from the 4-connected fill from ($sx,$sy)", 0, bad)
        }
    }
}
