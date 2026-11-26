package dev.kidpix.paint.app

/**
 * Pen latency without a camera: for each MotionEvent that reached the canvas, nanoseconds from
 * its event time (the newest sample in it) until the picture was handed to the compositor.
 * - front: the front-buffered piece's transaction (FrontInk render complete).
 * - view: end of the View frame that drew it (FrameMetrics: intended vsync + total duration).
 * Neither includes the compositor's latch and the panel's scanout after that; see NOTES/report.
 */
object LatencyStats {
    private const val CAP = 8192
    private val frontNs = LongArray(CAP)
    private val viewNs = LongArray(CAP)
    private val inputNs = LongArray(CAP)
    private val composeNs = LongArray(CAP)
    private val renderNs = LongArray(CAP)
    @Volatile private var nFront = 0
    @Volatile private var nView = 0
    @Volatile private var nInput = 0
    @Volatile private var nCompose = 0
    @Volatile private var nRender = 0

    @Synchronized fun front(ns: Long) { if (nFront < CAP) frontNs[nFront++] = ns }
    @Synchronized fun view(ns: Long) { if (nView < CAP) viewNs[nView++] = ns }
    /** Event time -> PaintView.onTouchEvent (the input pipeline, same for both paths). */
    @Synchronized fun input(ns: Long) { if (nInput < CAP) inputNs[nInput++] = ns }
    /** Main thread: dirty boxes + composite of one piece. */
    @Synchronized fun compose(ns: Long) { if (nCompose < CAP) composeNs[nCompose++] = ns }
    /** Piece handed to the renderer -> its transaction (render thread + GPU). */
    @Synchronized fun render(ns: Long) { if (nRender < CAP) renderNs[nRender++] = ns }

    @Synchronized fun reset() { nFront = 0; nView = 0; nInput = 0; nCompose = 0; nRender = 0 }

    data class Summary(val n: Int, val p50: Double, val p90: Double, val p99: Double, val mean: Double) {
        override fun toString() = if (n == 0) "n=0" else "n=$n p50=%.2fms p90=%.2fms p99=%.2fms mean=%.2fms".format(p50, p90, p99, mean)
    }

    private fun sum(a: LongArray, n: Int): Summary {
        if (n == 0) return Summary(0, 0.0, 0.0, 0.0, 0.0)
        val s = a.copyOf(n).also { it.sort() }
        fun q(p: Double) = s[((n - 1) * p).toInt()] / 1e6
        return Summary(n, q(0.5), q(0.9), q(0.99), s.average() / 1e6)
    }

    @Synchronized fun front() = sum(frontNs, nFront)
    @Synchronized fun view() = sum(viewNs, nView)
    @Synchronized fun input() = sum(inputNs, nInput)
    @Synchronized fun compose() = sum(composeNs, nCompose)
    @Synchronized fun render() = sum(renderNs, nRender)
}
