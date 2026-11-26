package dev.kidpix.paint.app

import android.util.Log

/**
 * Per-stroke cost of the engine on the main thread, for finding strokes that get slow as they
 * grow (`am start ... --ez perfLog true`; logcat tag KidPixPerf, one line per stroke):
 * engine time per move event (p50, p90, max), mean of the first and last quarter of the stroke's
 * moves (growth), onDraw time, and the engine time of the down and up events (a tap draws a tree
 * on down and merges + snapshots the picture on up). Off by default; costs two nanoTime calls per event when on.
 */
object StrokePerf {
    @JvmStatic var enabled = false
    private val moves = LongArray(16384)
    private var n = 0
    private val draws = LongArray(4096)
    private var nd = 0
    private var live = false
    private var downNs = 0L
    private var upNs = 0L

    fun begin() { if (!enabled) return; n = 0; nd = 0; live = true }
    fun move(ns: Long) { if (live && n < moves.size) moves[n++] = ns }
    fun draw(ns: Long) { if (live && nd < draws.size) draws[nd++] = ns }
    fun down(ns: Long) { if (live) downNs = ns }
    fun up(ns: Long) { if (live) upNs = ns }

    /** A toolbar press: engine time of its handler on the main thread. */
    fun button(what: String, ns: Long) { Log.i("KidPixPerf", "button %s %.2f".format(what, ns / 1e6)) }

    /** A timer or animation-frame callback (engine work outside events): logged when over 2 ms. */
    fun timer(ns: Long) { if (ns > 2_000_000) Log.i("KidPixPerf", "timer %.2f".format(ns / 1e6)) }

    fun end(tool: String, front: Boolean) {
        if (!enabled || !live) return
        live = false
        val d = draws.copyOf(nd).sortedArray()
        if (n == 0) {
            Log.i("KidPixPerf", "tap tool=%s down=%.2f up=%.2f draw_n=%d draw_max=%.2f".format(tool, downNs / 1e6, upNs / 1e6, nd, if (nd > 0) d[nd - 1] / 1e6 else 0.0))
            return
        }
        val m = moves.copyOf(n); val s = m.sortedArray()
        fun q(a: LongArray, p: Double) = a[((a.size - 1) * p).toInt()] / 1e6
        val k = maxOf(1, n / 4)
        val first = m.copyOfRange(0, k).average() / 1e6
        val last = m.copyOfRange(n - k, n).average() / 1e6
        Log.i("KidPixPerf", "stroke tool=%s front=%b moves=%d move_p50=%.2f p90=%.2f max=%.2f firstQ=%.2f lastQ=%.2f draw_n=%d draw_p50=%.2f draw_max=%.2f down=%.2f up=%.2f"
            .format(tool, front, n, q(s, 0.5), q(s, 0.9), q(s, 1.0), first, last, nd,
                if (nd > 0) q(d, 0.5) else 0.0, if (nd > 0) q(d, 1.0) else 0.0, downNs / 1e6, upNs / 1e6))
    }
}
