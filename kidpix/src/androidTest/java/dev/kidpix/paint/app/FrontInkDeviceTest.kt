package dev.kidpix.paint.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KiddoPaint
import dev.kidpix.paint.Pencil
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.KTimeBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The front-buffered pen layer on a device: what the screen shows (a real screenshot of the
 * composed display, so the SurfaceView layer is in it) against the engine's own composite.
 *
 * Mid-stroke the View path is frozen to the picture from before the stroke ([PaintView.testFrozen]),
 * so whatever the stroke added on screen can only have come from the front layer: the screen
 * matching the engine then proves the layer shows the engine's pixels and covers every change.
 */
@RunWith(AndroidJUnit4::class)
class FrontInkDeviceTest {
    private lateinit var scenario: ActivityScenario<PaintActivity>
    private lateinit var act: PaintActivity
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun launch(lowLatency: Boolean) {
        PaintView.lowLatency = lowLatency
        scenario = ActivityScenario.launch(PaintActivity::class.java)
        scenario.onActivity { act = it }
        waitFor("engine") { onMain { KDisplay.isReady && act.paintView.width > 0 } }
        onMain {
            KDisplay.clearMain()
            for (l in listOf(KDisplay.bnimCanvas, KDisplay.animCanvas, KDisplay.previewCanvas, KDisplay.canvas)) l.getContext().clearRect(0, 0, l.width, l.height)
            dev.kidpix.paint.History.clear()
            KAndroid.pressMain("pencil")
            KiddoPaint.Tools.Pencil.size = 9.0
            KAndroid.subSelection["pencil"] = mutableMapOf(0 to 2, 1 to 0)
            KAndroid.paintSubSelection("pencil")
            act.paintView.invalidate()
        }
        Thread.sleep(300)
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) runCatching { scenario.close() }
        PaintView.lowLatency = null
    }

    private fun <T> onMain(f: () -> T): T {
        var r: T? = null
        inst.runOnMainSync { r = f() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private fun waitFor(what: String, ms: Long = 10_000, f: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + ms
        while (!f()) {
            if (SystemClock.uptimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private val d get() = act.resources.displayMetrics.density

    private fun ev(action: Int, cx: Int, cy: Int, down: Long, tool: Int = MotionEvent.TOOL_TYPE_STYLUS): MotionEvent {
        val pp = MotionEvent.PointerProperties().apply { id = 0; toolType = tool }
        val pc = MotionEvent.PointerCoords().apply { x = (cx + 1) * d; y = (cy + 1) * d; pressure = 0.5f }
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(pp), arrayOf(pc),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
    }

    private var downAt = 0L
    private fun down(x: Int, y: Int) { downAt = SystemClock.uptimeMillis(); onMain { act.paintView.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x, y, downAt)) } }
    private fun move(x: Int, y: Int) = onMain { act.paintView.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x, y, downAt)) }
    private fun up(x: Int, y: Int) = onMain { act.paintView.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, x, y, downAt)) }

    private fun path(from: Pair<Int, Int>, to: Pair<Int, Int>, steps: Int = 12) = (1..steps).map { i ->
        (from.first + (to.first - from.first) * i / steps) to (from.second + (to.second - from.second) * i / steps)
    }

    /** White + main, bnim, anim, preview, tmp: what PaintView.onDraw shows, in canvas pixels. */
    private fun composite(): Bitmap = onMain {
        val m = KDisplay.main_canvas
        val b = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        for (l in listOf(m, KDisplay.bnimCanvas, KDisplay.animCanvas, KDisplay.previewCanvas, KDisplay.canvas)) c.drawBitmap(l.bitmap, 0f, 0f, null)
        b
    }

    /** Freeze the View path on the current picture: from now on only the front layer can change the screen. */
    private fun freezeView() {
        val pic = composite()
        onMain { act.paintView.testFrozen = pic; act.paintView.invalidate() }
        Thread.sleep(150)
    }

    private fun unfreezeView() = onMain { act.paintView.testFrozen = null; act.paintView.invalidate() }

    /** Screen pixels of the canvas area vs the engine composite: fraction that differ by more than 4 per channel. */
    private fun screenVsEngine(label: String): Double {
        Thread.sleep(250) // let the render thread and the compositor settle
        val engine = composite()
        val shot = inst.uiAutomation.takeScreenshot()!!
        val engine2 = composite()
        val changed = !engine.sameAs(engine2) // timers (drips, animations) moved on during the shot
        val loc = IntArray(2)
        onMain { act.paintView.getLocationOnScreen(loc) }
        // the canvas sits right inside the 1 CSS px border, floored to device px like Chrome (PaintView.borderPx)
        val b = act.paintView.borderPx
        val left = loc[0] + b; val top = loc[1] + b
        val w = Math.round(engine.width * d); val h = Math.round(engine.height * d)
        assertTrue("screenshot ${shot.width}x${shot.height} holds the canvas at $left,$top ${w}x$h", left + w <= shot.width && top + h <= shot.height)
        val sx = w.toFloat() / engine.width; val sy = h.toFloat() / engine.height
        val row = IntArray(w)
        var bad = 0; var inked = 0
        var badBox: Rect? = null
        for (y in 0 until h) {
            shot.getPixels(row, 0, w, left, top + y, w, 1)
            // nearest-neighbour at 1.5x: a pixel centre exactly on a canvas pixel edge may round either way
            val ey0 = ((y + 0.5f) / sy - 1e-3f).toInt().coerceIn(0, engine.height - 1)
            val ey1 = ((y + 0.5f) / sy + 1e-3f).toInt().coerceIn(0, engine.height - 1)
            for (x in 0 until w) {
                val ex0 = ((x + 0.5f) / sx - 1e-3f).toInt().coerceIn(0, engine.width - 1)
                val ex1 = ((x + 0.5f) / sx + 1e-3f).toInt().coerceIn(0, engine.width - 1)
                val s = row[x]
                fun near(e: Int) = abs(Color.red(e) - Color.red(s)) <= 4 && abs(Color.green(e) - Color.green(s)) <= 4 && abs(Color.blue(e) - Color.blue(s)) <= 4
                if (engine.getPixel(ex1, ey1) != Color.WHITE) inked++
                fun ok(b: Bitmap) = near(b.getPixel(ex1, ey1)) || near(b.getPixel(ex0, ey1)) || near(b.getPixel(ex1, ey0)) || near(b.getPixel(ex0, ey0))
                if (!(ok(engine) || (changed && ok(engine2)))) {
                    bad++
                    val bb = badBox ?: Rect(x, y, x + 1, y + 1).also { badBox = it }
                    bb.union(x, y, x + 1, y + 1)
                }
            }
        }
        val frac = bad.toDouble() / (w * h)
        Log.i("KidPixFront", "$label: ${"%.4f".format(frac * 100)}% screen px differ ($bad of ${w * h}, box $badBox); engine inked px $inked; engine changed during shot $changed; front shown=${onMain { act.paintView.front?.isShown }}")
        if (bad > 0) {
            // keep the evidence for a look
            val dir = act.getExternalFilesDir(null)!!
            java.io.FileOutputStream(java.io.File(dir, "$label-screen.png")).use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            java.io.FileOutputStream(java.io.File(dir, "$label-engine.png")).use { engine.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return frac
    }

    /** Timers held (the picture stands still), then the screen must be the engine's picture exactly. */
    private fun assertScreenIsEngine(label: String) {
        val gate = pauseEngine()
        onMain { act.paintView.invalidate() } // the last timer change, if any, goes out with this frame
        val frac = try { screenVsEngine(label) } finally { onMain { gate.resume() } }
        assertEquals("$label: screen pixels that differ from the engine", 0.0, frac, 0.0)
    }

    /**
     * Engine timers held while paused (wraps the app's KTime backend), so the picture holds still
     * for an exact comparison; released in order on resume.
     */
    private class Gate(val inner: KTimeBackend) : KTimeBackend {
        @Volatile var paused = false
        val held = ArrayList<() -> Unit>()
        private fun gate(fn: () -> Unit): () -> Unit = { if (paused) held.add(fn) else fn() }
        override fun now() = inner.now()
        override fun schedule(id: Int, delayMs: Long, fn: () -> Unit) = inner.schedule(id, delayMs, gate(fn))
        override fun cancel(id: Int) = inner.cancel(id)
        override fun frame(id: Int, fn: (Double) -> Unit) = inner.frame(id) { t -> gate { fn(t) }() }
        fun resume() { paused = false; val h = held.toList(); held.clear(); h.forEach { it() } }
    }

    private fun pauseEngine(): Gate = onMain {
        val g = (KTime.backend as? Gate) ?: Gate(KTime.backend).also { KTime.backend = it }
        g.paused = true; g
    }

    /** A stroke, checked mid-stroke (front layer alone over the frozen View) and after pen-up (View path). */
    private fun checkStroke(label: String, pts: List<Pair<Int, Int>>) {
        freezeView()
        down(pts[0].first, pts[0].second)
        for ((x, y) in pts.drop(1)) { move(x, y); Thread.sleep(8) }
        val f = onMain { act.paintView.front }
        assertNotNull(f)
        waitFor("$label: front layer shown", 3000) { onMain { f!!.isShown } }
        assertTrue("$label: pieces presented", onMain { f!!.presented } > 0)
        // timers still running: the screen may be a frame behind the engine (logged, not asserted)
        screenVsEngine("$label-mid-live")
        assertScreenIsEngine("$label-mid")
        unfreezeView()
        up(pts.last().first, pts.last().second)
        waitFor("$label: front layer hidden", 3000) { onMain { !f!!.isShown } }
        assertScreenIsEngine("$label-after")
    }

    @Test
    fun pencilBrushEraserStampMatchTheEngineMidStrokeAndAfter() {
        launch(true)
        checkStroke("pencil", listOf(100 to 120) + path(100 to 120, 500 to 300))

        onMain { KAndroid.pressMain("brush"); KAndroid.pressSubmenu(3) } // Bubbly
        checkStroke("brush-bubbly", listOf(150 to 400) + path(150 to 400, 600 to 450, 16))
        onMain { KAndroid.pressMain("brush"); KAndroid.pressSubmenu(0) } // Leaky Pen: drips on timers
        checkStroke("brush-leaky", listOf(650 to 100) + path(650 to 100, 900 to 350, 16))

        onMain { KAndroid.pressMain("eraser"); KAndroid.pressSubmenu(0) }
        checkStroke("eraser", listOf(80 to 140) + path(80 to 140, 700 to 420, 20))

        onMain { KAndroid.pressMain("stamp"); KAndroid.pressSubmenu(0) }
        checkStroke("stamp", listOf(300 to 550) + path(300 to 550, 420 to 600, 6))
    }

    @Test
    fun noStaleInkAfterUndoMixerAndOpen() {
        launch(true)
        checkStroke("before-undo", listOf(100 to 300) + path(100 to 300, 700 to 300))
        onMain { KAndroid.pressMain("undo") }
        waitFor("undone", 3000) { onMain { Color.alpha(KDisplay.main_canvas.bitmap.getPixel(400, 300)) == 0 } }
        assertScreenIsEngine("undo")
        // next stroke: the front buffer must not bring back the undone one
        checkStroke("after-undo", listOf(100 to 500) + path(100 to 500, 700 to 520))

        // whole-picture mixer effect (Invert), then a stroke over it
        onMain { KAndroid.pressMain("jumble"); KAndroid.pressSubmenu(0) }
        down(380, 300); up(380, 300)
        Thread.sleep(500)
        assertScreenIsEngine("invert")
        onMain { KAndroid.pressMain("pencil") }
        checkStroke("after-invert", listOf(200 to 150) + path(200 to 150, 800 to 200))

        // clear by a fresh canvas (what open does), then a stroke
        onMain { KDisplay.clearMain(); act.paintView.invalidate() }
        assertScreenIsEngine("cleared")
        checkStroke("after-clear", listOf(300 to 650) + path(300 to 650, 600 to 700))
    }

    @Test
    fun viewPathOnlyWhenOffOrAfterAFailure() {
        launch(false)
        assertNull(onMain { act.paintView.front })
        down(100, 120); for ((x, y) in path(100 to 120, 500 to 300)) move(x, y); up(500, 300)
        assertScreenIsEngine("off")
        scenario.close()

        launch(true)
        val f = onMain { act.paintView.front }!!
        onMain { f.fail(RuntimeException("test: surface lost")) }
        waitFor("dropped", 3000) { onMain { act.paintView.front == null } }
        down(100, 400); for ((x, y) in path(100 to 400, 500 to 500)) move(x, y)
        assertScreenIsEngine("failed-mid")
        up(500, 500)
        assertScreenIsEngine("failed-after")
    }

    @Test
    fun surviveRecreateAndResume() {
        launch(true)
        checkStroke("first", listOf(100 to 120) + path(100 to 120, 500 to 300))
        scenario.recreate()
        scenario.onActivity { act = it }
        waitFor("engine again") { onMain { KDisplay.isReady && act.paintView.width > 0 && act.paintView.front != null } }
        Thread.sleep(500)
        assertScreenIsEngine("recreated")
        checkStroke("after-recreate", listOf(150 to 500) + path(150 to 500, 650 to 520))
        // pause/resume: the surface goes away and comes back
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        Thread.sleep(800)
        assertScreenIsEngine("resumed")
        checkStroke("after-resume", listOf(600 to 100) + path(600 to 100, 900 to 400))
    }

    /** [n] pen strokes through the real input pipeline (UiAutomation), ~120 samples/s like a pen. */
    private fun injectStrokes(n: Int) {
        val loc = IntArray(2)
        onMain { act.paintView.getLocationOnScreen(loc) }
        fun sev(action: Int, cx: Int, cy: Int, downT: Long): MotionEvent {
            val pp = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val pc = MotionEvent.PointerCoords().apply { x = loc[0] + (cx + 1) * d; y = loc[1] + (cy + 1) * d; pressure = 0.5f }
            return MotionEvent.obtain(downT, SystemClock.uptimeMillis(), action, 1, arrayOf(pp), arrayOf(pc),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        }
        for (s in 0 until n) {
            val t0 = SystemClock.uptimeMillis()
            val y = 100 + s * 90
            inst.uiAutomation.injectInputEvent(sev(MotionEvent.ACTION_DOWN, 80, y, t0), true)
            for (i in 0 until 120) {
                inst.uiAutomation.injectInputEvent(sev(MotionEvent.ACTION_MOVE, 80 + i * 7, y + (i % 9) * 3, t0), false)
                Thread.sleep(8)
            }
            inst.uiAutomation.injectInputEvent(sev(MotionEvent.ACTION_UP, 80 + 120 * 7, y, t0), true)
            Thread.sleep(200)
        }
        Thread.sleep(500)
    }

    /** No forced mode: after 3 strokes the app keeps the layer only if it measured faster. Either way the picture is right. */
    @Test
    fun autoModeJudgesAndStaysCorrect() {
        PaintView.lowLatency = null
        scenario = ActivityScenario.launch(PaintActivity::class.java)
        scenario.onActivity { act = it }
        waitFor("engine") { onMain { KDisplay.isReady && act.paintView.width > 0 } }
        onMain { KDisplay.clearMain(); KAndroid.pressMain("pencil"); act.paintView.invalidate() }
        assertNotNull("layer exists", onMain { act.paintView.front })
        injectStrokes(2)
        assertFalse("baseline strokes: layer idle", onMain { act.paintView.front!!.presented > 0 })
        injectStrokes(3)
        val kept = onMain { act.paintView.front != null }
        assertNotNull("decided", onMain { act.paintView.frontVerdict })
        Log.i("KidPixLatency", "auto mode: front ${LatencyStats.front()}; view ${LatencyStats.view()}; kept=$kept; true front buffer ${FrontInk.trueFrontBuffer}")
        assertScreenIsEngine("auto-after")
        injectStrokes(1)
        assertScreenIsEngine("auto-later")
    }

    /** What the dirty boxes lean on for writes behind Ctx2D: setPixels must change the generation id. */
    @Test
    fun generationIds() {
        val b = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val g0 = b.generationId
        Canvas(b).drawColor(Color.RED)
        val g1 = b.generationId
        b.setPixel(1, 1, Color.BLUE)
        val g2 = b.generationId
        Log.i("KidPixFront", "generation id: software Canvas draw changes it: ${g1 != g0}; setPixel: ${g2 != g1}")
        assertTrue("setPixel changes the generation id", g2 != g1)
    }

    /** Turning the tablet over (sensorLandscape: no new Activity, the surface's transform changes). */
    @Test
    fun turnedOver() {
        launch(true)
        try {
            inst.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_270)
            Thread.sleep(1500)
            val rot = onMain { act.display!!.rotation }
            Log.i("KidPixFront", "display rotation now $rot")
            assertEquals("turned over", android.view.Surface.ROTATION_270, rot)
            checkStroke("turned", listOf(100 to 120) + path(100 to 120, 500 to 300))
            inst.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
            Thread.sleep(1500)
            assertScreenIsEngine("turned-back")
            checkStroke("turned-back", listOf(150 to 400) + path(150 to 400, 600 to 450))
        } finally {
            inst.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
        }
    }

    /**
     * Latency without a camera: real input injection at ~120 Hz, event time -> front piece handed
     * to the compositor, and event time -> end of the View frame that drew it. Logged (tag
     * KidPixLatency); the numbers go into the report. One run with the layer, one without.
     */
    @Test
    fun latencyLog() {
        for (on in listOf(true, false)) {
            launch(on)
            LatencyStats.reset()
            inst.uiAutomation.executeShellCommand("dumpsys gfxinfo ${act.packageName} reset").close()
            injectStrokes(6)
            Log.i("KidPixLatency", "lowLatency=$on front: ${LatencyStats.front()}")
            Log.i("KidPixLatency", "lowLatency=$on view:  ${LatencyStats.view()}")
            Log.i("KidPixLatency", "lowLatency=$on input (event -> onTouchEvent): ${LatencyStats.input()}")
            Log.i("KidPixLatency", "lowLatency=$on compose (main thread per piece): ${LatencyStats.compose()}")
            Log.i("KidPixLatency", "lowLatency=$on render (piece -> transaction): ${LatencyStats.render()}")
            val gfx = inst.uiAutomation.executeShellCommand("dumpsys gfxinfo ${act.packageName}").let { pfd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
            }
            gfx.lines().filter { it.startsWith("Total frames") || it.startsWith("Janky") || it.contains("percentile") || it.startsWith("Number ") }
                .take(12).forEach { Log.i("KidPixLatency", "lowLatency=$on gfxinfo: $it") }
            assertScreenIsEngine("latency-$on")
            if (on) assertTrue("front pieces measured", LatencyStats.front().n > 100)
            else assertEquals(0, LatencyStats.front().n)
            assertTrue("view frames measured", LatencyStats.view().n > 100)
            scenario.close()
        }
    }
}
