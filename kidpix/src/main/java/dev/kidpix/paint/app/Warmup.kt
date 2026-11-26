package dev.kidpix.paint.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.drawTree
import kotlin.math.PI

/**
 * After the app starts, runs the engine's heavy drawing code a few times on a small scratch
 * canvas, in short steps while the main thread is idle and nobody is touching: ART compiles hot
 * code on its JIT thread only after it ran a while, so without this the first big oval, tree or
 * line of a session ran interpreted, 2-3 times slower than later ones (the tablet: first oval
 * release 67 ms against 27, first tree 136 ms against 65). Nothing visible is drawn.
 */
object Warmup {
    private var started = false

    fun start(busy: () -> Boolean) {
        if (started) return
        started = true
        val c = KCanvas(320, 240) // larger than Chrome's CPU-raster limit: the Chrome-like paths run
        val ctx = c.getContext("2d")
        val steps = ArrayList<() -> Unit>()
        repeat(6) {
            steps.add { // big MSAA outline (oval release), whole-box paths
                ctx.clearRect(0, 0, 320, 240)
                ctx.beginPath(); ctx.arc(160, 120, 100 + it, 0, 2 * PI); ctx.lineWidth = 1.5; ctx.strokeStyle = "red"; ctx.stroke()
                ctx.beginPath(); ctx.rect(20, 20, 270, 190); ctx.stroke()
            }
            steps.add { // analytic lines (line tool release, starburst)
                ctx.lineCap = "butt"; ctx.lineWidth = 7.0
                for (k in 0 until 12) { ctx.beginPath(); ctx.moveTo(160, 120); ctx.lineTo(10 + 25 * k, if (k % 2 == 0) 5 else 235); ctx.stroke() }
            }
            steps.add { // the tree's batched thin strokes (drawTree draws on KDisplay.context)
                val keep = KDisplay.context
                KDisplay.context = ctx
                try { ctx.batchStrokes { drawTree(160.0, 230.0, 14.0, -PI / 2, 9, 6.0) } } finally { KDisplay.context = keep }
            }
            steps.add { // the paint can's scanline fill, on scratch layers (no undo step, no save, no sound)
                val mc = KDisplay.main_canvas; val mx = KDisplay.main_context; val tc = KDisplay.canvas; val tx = KDisplay.context
                val store = KDisplay.store; val undo = KDisplay.undoOn; val snd = dev.kidpix.paint.KSounds.enabled
                val main = KCanvas(320, 240); val tmp = KCanvas(320, 240)
                try {
                    KDisplay.main_canvas = main; KDisplay.main_context = main.getContext("2d")
                    KDisplay.canvas = tmp; KDisplay.context = tmp.getContext("2d")
                    KDisplay.store = null; KDisplay.undoOn = false; dev.kidpix.paint.KSounds.enabled = false
                    KDisplay.main_context.fillStyle = "black"; KDisplay.main_context.fillRect(100, 0, 3, 240)
                    dev.kidpix.paint.ToolboxFlood().boundedFill(dev.kidpix.paint.Ev(10.0 + it, 10.0))
                } finally {
                    KDisplay.main_canvas = mc; KDisplay.main_context = mx; KDisplay.canvas = tc; KDisplay.context = tx
                    KDisplay.store = store; KDisplay.undoOn = undo; dev.kidpix.paint.KSounds.enabled = snd
                }
            }
            steps.add { // pixels in and out
                val d = ctx.getImageData(0, 0, 320, 240)
                ctx.putImageData(d, 0, 0)
                d.data[0] = d.data[0]
                ctx.putImageData(d, 0, 0)
            }
        }
        val h = Handler(Looper.getMainLooper())
        var i = 0
        fun next() {
            if (i >= steps.size) return
            Looper.myQueue().addIdleHandler {
                if (busy()) { h.postDelayed({ next() }, 200); return@addIdleHandler false }
                val t0 = SystemClock.uptimeMillis()
                // the tree draws random numbers: the engine's sequence (the action log) must not see them
                val rs = dev.kidpix.paint.engine.KRandom.state
                try { runCatching { steps[i++]() } } finally { dev.kidpix.paint.engine.KRandom.state = rs }
                if (StrokePerf.enabled) android.util.Log.i("KidPixPerf", "warmup step %d %d ms".format(i, SystemClock.uptimeMillis() - t0))
                h.postDelayed({ next() }, 30)
                false
            }
        }
        h.postDelayed({ next() }, 500)
    }
}
