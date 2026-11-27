package dev.kidpix.paint.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.kidpix.paint.engine.VirtualClock
import dev.kidpix.paint.log.KLog

/**
 * The engine's clock in the app: a [VirtualClock], as in the headless harness, pumped to the wall
 * clock. A Handler wakes at the next timer's due time and the clock runs everything due (each
 * timer at its own due time); every input entry first moves the clock to its time too
 * (KLog.entry). Timers and input then interleave exactly as a replay of the action log runs them.
 * requestAnimationFrame is a 16 ms timer, as in the reference capture (nothing in the engine uses it).
 */
class AppTime(val clock: VirtualClock) {
    private val handler = Handler(Looper.getMainLooper())
    /** Date.now(): the wall clock at process start, then monotonic (uptime) so it never jumps back. */
    private val base = System.currentTimeMillis() - SystemClock.uptimeMillis()
    private var armedFor = Long.MAX_VALUE
    private val pumpNow = Runnable { armedFor = Long.MAX_VALUE; pump() }

    init {
        clock.time = maxOf(clock.time, wallNow())
        clock.onScheduled = { arm() }
        clock.wrap = { f -> safe(f) }
    }

    fun wallNow(): Long = maxOf(clock.time, base + SystemClock.uptimeMillis())

    /** Wakes the pump for the earliest pending timer. */
    fun arm() {
        if (KLog.playing) return // the play button's replay runs the clock itself; armed again after it
        val nd = clock.nextDue() ?: return
        if (nd >= armedFor) return
        handler.removeCallbacks(pumpNow)
        armedFor = nd
        handler.postDelayed(pumpNow, (nd - (base + SystemClock.uptimeMillis())).coerceAtLeast(0))
    }

    fun pump() {
        KLog.pump(wallNow())
        arm()
    }

    /** A throwing timer is a JS exception in a timer: logged, the page goes on. */
    private inline fun safe(fn: () -> Unit) {
        val t0 = System.nanoTime()
        try { fn() } catch (t: Throwable) { android.util.Log.e("KidPix", "timer failed", t) }
        if (StrokePerf.enabled) StrokePerf.timer(System.nanoTime() - t0)
    }
}
