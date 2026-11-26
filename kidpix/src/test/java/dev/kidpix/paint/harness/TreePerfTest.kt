package dev.kidpix.paint.harness

import dev.kidpix.paint.Ev
import dev.kidpix.paint.History
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KiddoPaint
import dev.kidpix.paint.colorSelect
import dev.kidpix.paint.engine.KRandom
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.VirtualClock
import dev.kidpix.paint.ev_canvas
import dev.kidpix.paint.init_color_selector
import dev.kidpix.paint.init_kiddo_defaults
import dev.kidpix.paint.init_subtool_bars
import dev.kidpix.paint.press_main_tool
import dev.kidpix.paint.press_submenu
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tree brush timing on the JVM (KP_PERF=1 ./gradlew :kidpix:testDebugUnitTest --tests '*TreePerfTest*').
 * Prints main-thread CPU time per tap and, with KP_PERF=profile, a histogram of sampled main-thread frames.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TreePerfTest {
    // java.lang.management is not on the Android compile classpath; the test JVM has it
    private val mx = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    private val cpu = Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime")
    private fun cpuNanos(): Long = cpu.invoke(mx) as Long

    private fun tap(x: Double, y: Double) {
        ev_canvas(Ev(x, y, "mousedown").also { it.buttons = 1 })
        ev_canvas(Ev(x, y, "mouseup").also { it.buttons = 0 })
    }

    @Test fun treeTaps() {
        val mode = System.getenv("KP_PERF")
        assumeTrue(mode != null)
        val clock = VirtualClock(); KTime.backend = clock; KTime.origin = VirtualClock.T0
        KiddoPaint.resetWorld(); KDisplay.store = null
        KDisplay.init_layers(1074, 798)
        init_kiddo_defaults(); init_subtool_bars(); init_color_selector()
        History.clear(); KRandom.seed(1234); init_kiddo_defaults()
        press_main_tool("brush"); colorSelect(19); press_submenu(19)

        val main = Thread.currentThread()
        val hist = HashMap<String, Int>()
        val self = HashMap<String, Int>()
        val samplingFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val sampler = Thread {
            while (true) {
                if (samplingFlag.get()) {
                    val st = main.stackTrace
                    val seen = HashSet<String>()
                    for ((i, f) in st.withIndex()) {
                        val k = f.className.substringAfterLast('.') + "." + f.methodName
                        if (i == 0) self.merge(k + ":" + f.lineNumber, 1, Int::plus)
                        if (seen.add(k)) hist.merge(k, 1, Int::plus)
                    }
                }
                try { Thread.sleep(1) } catch (e: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }

        // warm up, then 10 places; each tree drawn 3 times from the same seed, the fastest counts
        for (i in 0 until 6) tap(100.0 + i * 150, 600.0)
        val times = ArrayList<Long>()
        for (i in 0 until 10) {
            val x = 100 + (i * 137) % 870; val y = 300 + (i * 211) % 480
            var best = Long.MAX_VALUE
            repeat(3) {
                KRandom.seed(1000 + i)
                samplingFlag.set(mode == "profile")
                val t0 = cpuNanos()
                tap(x.toDouble(), y.toDouble())
                best = minOf(best, cpuNanos() - t0) // CPU time of this thread (the host is busy)
                samplingFlag.set(false)
            }
            times.add(best / 1_000_000)
            println("tree tap $i at $x,$y: ${best / 1_000_000} ms cpu")
        }
        sampler.interrupt()
        times.sort()
        println("tree taps (10 places, best of 3): min ${times.first()} median ${times[times.size / 2]} max ${times.last()} ms")
        if (mode == "profile") {
            val tot = self.values.sum().coerceAtLeast(1)
            println("--- inclusive (samples $tot)")
            hist.entries.sortedByDescending { it.value }.take(45).forEach { println("%5.1f%% %s".format(100.0 * it.value / tot, it.key)) }
            println("--- self")
            self.entries.sortedByDescending { it.value }.take(25).forEach { println("%5.1f%% %s".format(100.0 * it.value / tot, it.key)) }
        }
    }
}
