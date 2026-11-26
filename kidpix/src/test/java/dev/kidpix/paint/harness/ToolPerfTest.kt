package dev.kidpix.paint.harness

import dev.kidpix.paint.Ev
import dev.kidpix.paint.History
import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KUi
import dev.kidpix.paint.KiddoPaint
import dev.kidpix.paint.colorSelect
import dev.kidpix.paint.engine.KRandom
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.VirtualClock
import dev.kidpix.paint.ev_canvas
import dev.kidpix.paint.init_color_selector
import dev.kidpix.paint.init_kiddo_defaults
import dev.kidpix.paint.init_subtool_bars
import dev.kidpix.paint.kidpix_android_boot
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Every tool and option on the JVM: engine time per move of a short and a 2000-point stroke, and of
 * a tap (KP_PERF=1 ./gradlew :kidpix:testDebugUnitTest --tests '*ToolPerfTest*'; KP_ONLY=line,jumble
 * limits the tools; KP_PERF=profile adds a sampled main-thread profile per tool). Host Skia, so
 * absolute numbers are not the tablet's; growth and outliers are what it finds.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolPerfTest {
    private lateinit var clock: VirtualClock

    private fun ev(type: String, x: Double, y: Double, down: Boolean) {
        val e = Ev(x, y, type); e.buttons = if (down) 1 else 0
        e.timeStamp = (clock.time - VirtualClock.T0).toDouble()
        ev_canvas(e)
    }

    private fun scribble(n: Int, seed: Int): List<DoubleArray> = (0 until n).map { k ->
        val u = k / (n - 1.0)
        doubleArrayOf(150 + 750 * u + 40 * sin(k / 3.0 + seed), 400 + 300 * sin(u * PI * 7) + 25 * cos(k / 2.0))
    }

    class Row(val name: String, val n: Int, val p50: Double, val max: Double, val firstQ: Double, val lastQ: Double, val down: Double, val up: Double)

    private fun stroke(pts: List<DoubleArray>): Row {
        val t = LongArray(pts.size - 1)
        clock.advance(8); var t0 = System.nanoTime(); ev("mousedown", pts[0][0], pts[0][1], true); clock.runDue(); val down = System.nanoTime() - t0
        for (i in 1 until pts.size) {
            clock.advance(8)
            t0 = System.nanoTime(); ev("mousemove", pts[i][0], pts[i][1], true); clock.runDue(); t[i - 1] = System.nanoTime() - t0
        }
        clock.advance(8); t0 = System.nanoTime(); ev("mouseup", pts.last()[0], pts.last()[1], false); clock.runDue(); val up = System.nanoTime() - t0
        val s = t.sortedArray(); val k = maxOf(1, t.size / 4)
        return Row("", t.size, s[s.size / 2] / 1e6, s.last() / 1e6, t.copyOfRange(0, k).average() / 1e6, t.copyOfRange(t.size - k, t.size).average() / 1e6, down / 1e6, up / 1e6)
    }

    private fun settle() { repeat(40) { clock.advance(100); clock.runDue() } }

    @Test fun sweep() {
        val mode = System.getenv("KP_PERF")
        assumeTrue(mode != null)
        val only = System.getenv("KP_ONLY")?.split(",")
        val opts = System.getenv("KP_OPTS")?.split(",")?.map { it.toInt() }
        val long = System.getenv("KP_LONG")?.toInt() ?: 2000
        clock = VirtualClock(); KTime.backend = clock; KTime.origin = VirtualClock.T0
        KiddoPaint.resetWorld(); KDisplay.store = null
        KDisplay.init_layers(1074, 798)
        init_kiddo_defaults(); init_subtool_bars(); init_color_selector(); kidpix_android_boot(); clock.runDue()
        History.clear(); KRandom.seed(1234)
        val tools = listOf("pencil", "line", "square", "circle", "brush", "jumble", "flood", "eraser", "alphabet", "stamp", "truck")
        val out = StringBuilder()
        out.append("tool opt | moves p50 max firstQ lastQ down up (long) | short p50 max up | tap down up\n")
        for (tool in tools) {
            if (only != null && tool !in only) continue
            KAndroid.pressMain(tool)
            val items = if (KUi.currentSubmenu == "alphabet") 26 else KiddoPaint.Submenu[KUi.currentSubmenu]?.size ?: 0
            for (i in 0 until maxOf(1, items)) {
                if (opts != null && i !in opts) continue
                KAndroid.pressMain(tool)
                if (items > 0) {
                    if (KUi.currentSubmenu == "alphabet") KAndroid.pressAlphabet(i)
                    else { val it = KiddoPaint.Submenu[KUi.currentSubmenu]!![i]; if (it.invisible) continue; KAndroid.pressSubmenu(i) }
                }
                colorSelect(5 + i % 20, 1)
                // some ink under the mixers, fills and erasers
                if (tool in listOf("jumble", "eraser", "flood", "truck")) seedInk()
                val r = try { stroke(scribble(long, i)) } catch (t: Throwable) { out.append("$tool $i: ${t}\n"); continue }
                settle()
                val s = stroke(scribble(20, i)); settle()
                clock.advance(8); var t0 = System.nanoTime(); ev("mousedown", 500.0, 400.0, true); clock.runDue(); val td = System.nanoTime() - t0
                clock.advance(40); t0 = System.nanoTime(); ev("mouseup", 500.0, 400.0, false); clock.runDue(); val tu = System.nanoTime() - t0
                settle()
                val line = "%-8s %2d | %6.2f %6.2f %6.2f %6.2f %6.2f %6.2f | %6.2f %6.2f %6.2f | %6.2f %6.2f".format(
                    tool, i, r.p50, r.max, r.firstQ, r.lastQ, r.down, r.up, s.p50, s.max, s.up, td / 1e6, tu / 1e6)
                println(line); out.append(line).append('\n')
                // keep the picture from filling up: clear main now and then (no undo)
                KDisplay.clearMain(); KDisplay.clearTmp(); KDisplay.clearPreview(); History.clear()
            }
        }
        val f = java.io.File(System.getenv("KP_OUT") ?: "/tmp/kp-perf/jvm-sweep.txt")
        f.parentFile?.mkdirs(); f.writeText(out.toString())
    }

    private fun seedInk() {
        val c = KDisplay.main_context
        for (k in 0 until 12) {
            c.fillStyle = arrayOf("red", "blue", "green", "orange", "purple", "black")[k % 6]
            c.fillRect(60 + k * 80, 100 + (k % 3) * 180, 70, 300)
        }
    }
}
