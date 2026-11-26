package dev.kidpix.paint.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.WindowInsets
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KCurrent
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KiddoPaint
import dev.kidpix.paint.Pencil
import dev.kidpix.paint.Tool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The shell on a device: real MotionEvents, MediaStore, the picker, the window. */
@RunWith(AndroidJUnit4::class)
class ShellDeviceTest {
    private lateinit var scenario: ActivityScenario<PaintActivity>
    private lateinit var act: PaintActivity
    private val inst = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(PaintActivity::class.java)
        scenario.onActivity { act = it }
        waitFor("engine") { onMain { KDisplay.isReady } }
        onMain {
            KDisplay.clearMain()
            KiddoPaint.Display.saveToLocalStorage()
            dev.kidpix.paint.History.clear()
            // the engine outlives the Activity in one process: back to the boot tool
            KAndroid.pressMain("pencil")
            KiddoPaint.Tools.Pencil.size = 9.0
            KAndroid.subSelection["pencil"] = mutableMapOf(0 to 2, 1 to 0)
            KAndroid.paintSubSelection("pencil")
        }
    }

    @After
    fun tearDown() = scenario.close()

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
            Thread.sleep(50)
        }
    }

    private val d get() = act.resources.displayMetrics.density

    /** A MotionEvent at canvas pixel (cx, cy) of PaintView. */
    private fun ev(action: Int, cx: Int, cy: Int, tool: Int, buttons: Int = 0, pressure: Float = 0.5f, down: Long): MotionEvent {
        val pp = MotionEvent.PointerProperties().apply { id = 0; toolType = tool }
        val pc = MotionEvent.PointerCoords().apply {
            x = (cx + 1) * d; y = (cy + 1) * d; this.pressure = pressure
        }
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(pp), arrayOf(pc),
            0, buttons, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
    }

    private fun stroke(tool: Int, buttons: Int, y: Int, pressure: Float = 0.5f, during: (() -> Unit)? = null) {
        val t0 = SystemClock.uptimeMillis()
        onMain { act.paintView.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 100, y, tool, buttons, pressure, t0)) }
        for (x in 120..400 step 20) onMain { act.paintView.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x, y, tool, buttons, pressure, t0)) }
        during?.invoke()
        onMain { act.paintView.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 400, y, tool, buttons, pressure, t0)) }
    }

    private fun alphaAt(x: Int, y: Int) = onMain { Color.alpha(KDisplay.main_canvas.bitmap.getPixel(x, y)) }

    @Test
    fun stylusDrawsWithPressure() {
        stroke(MotionEvent.TOOL_TYPE_STYLUS, 0, 200, pressure = 1f)
        assertTrue(alphaAt(250, 200) > 0)
        assertTrue("2x at full pressure", alphaAt(250, 207) > 0)
        assertEquals(1.0, onMain { KCurrent.scaling }, 0.0)
    }

    @Test
    fun barrelButtonAndEraserEndUseTheTempEraser() {
        var during: Tool? = null
        stroke(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY, 200) { during = onMain { KCurrent.tool } }
        assertSame(onMain { KAndroid.penEraser }, during)
        assertSame(onMain { KiddoPaint.Tools.Pencil }, onMain { KCurrent.tool })
        during = null
        stroke(MotionEvent.TOOL_TYPE_ERASER, 0, 300) { during = onMain { KCurrent.tool } }
        assertSame(onMain { KAndroid.penEraser }, during)
        assertSame(onMain { KiddoPaint.Tools.Pencil }, onMain { KCurrent.tool })
    }

    @Test
    fun hoverShowsBadgeAndHoverExitBeforeDownIsNotALeave() {
        val t0 = SystemClock.uptimeMillis()
        onMain { act.paintView.dispatchGenericMotionEvent(ev(MotionEvent.ACTION_HOVER_ENTER, 300, 300, MotionEvent.TOOL_TYPE_STYLUS, 0, 0f, t0)) }
        onMain { act.paintView.dispatchGenericMotionEvent(ev(MotionEvent.ACTION_HOVER_MOVE, 310, 300, MotionEvent.TOOL_TYPE_STYLUS, 0, 0f, t0)) }
        assertTrue(onMain { act.badge.shown })
        assertTrue(onMain { KAndroid.state.penHovering })
        // Android ends the hover right before the pen touches
        onMain { act.paintView.dispatchGenericMotionEvent(ev(MotionEvent.ACTION_HOVER_EXIT, 310, 300, MotionEvent.TOOL_TYPE_STYLUS, 0, 0f, t0)) }
        onMain { act.paintView.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 310, 300, MotionEvent.TOOL_TYPE_STYLUS, 0, 0.5f, t0)) }
        Thread.sleep(200)
        assertTrue("no pointerleave", onMain { KAndroid.state.penHovering })
        assertFalse(onMain { act.badge.shown })
        onMain { act.paintView.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 310, 300, MotionEvent.TOOL_TYPE_STYLUS, 0, 0.5f, t0)) }
        // a real exit (nothing follows) is a leave
        onMain { act.paintView.dispatchGenericMotionEvent(ev(MotionEvent.ACTION_HOVER_EXIT, 310, 300, MotionEvent.TOOL_TYPE_STYLUS, 0, 0f, t0)) }
        waitFor("leave", 2000) { !onMain { KAndroid.state.penHovering } }
    }

    @Test
    fun undoGuyTapAndDoubleTap() {
        stroke(MotionEvent.TOOL_TYPE_FINGER, 0, 200)
        assertTrue(alphaAt(250, 200) > 0)
        val guy = act.toolButtons["undo"]!!
        onMain { guy.performClick() }
        waitFor("undo", 3000) { alphaAt(250, 200) == 0 }
        onMain { guy.performClick(); guy.performClick() }
        waitFor("redo", 3000) { alphaAt(250, 200) > 0 }
    }

    @Test
    fun saveListsInPicturesKidPixAndOpensFromThePicker() {
        val before = Pictures(act).list().size
        stroke(MotionEvent.TOOL_TYPE_STYLUS, 0, 200)
        onMain { act.toolButtons["save"]!!.performClick() }
        waitFor("saved", 10_000) { Pictures(act).list().size == before + 1 }
        val newest = Pictures(act).list().first()
        act.contentResolver.query(
            android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, newest.id),
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH), null, null, null,
        )!!.use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.getString(0), c.getString(0).matches(Regex("kidpix-\\d+\\.png")))
            assertEquals("Pictures/Kid Pix/", c.getString(1))
        }
        val full = Pictures(act).full(newest.id)
        assertEquals("white paper", Color.WHITE, full.getPixel(5, 5))

        onMain { KDisplay.clearMain() }
        assertEquals(0, alphaAt(250, 200))
        onMain { act.toolButtons["open"]!!.performClick() }
        waitFor("thumbs") { onMain { act.picker?.thumbs?.isNotEmpty() == true } }
        val first = onMain { act.picker!!.thumbs.first() }
        assertEquals(newest.id, first.id)
        waitFor("thumb decoded") { onMain { first.bitmap != null } }
        onMain { first.performClick() }
        waitFor("opened") { alphaAt(250, 200) > 0 }
        assertEquals(null, onMain { act.picker })
        // undo brings back what was there (blank)
        onMain { act.toolButtons["undo"]!!.performClick() }
        waitFor("undo open", 3000) { alphaAt(250, 200) == 0 }
        act.contentResolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, newest.id), null, null)
    }

    @Test
    fun oldTransparentPicturesThumbOnWhite() {
        val b = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888) // transparent like pre-0.3.0 saves
        b.setPixel(1, 1, Color.RED)
        val bytes = java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        assertTrue(Pictures(act).savePng(bytes))
        val pic = Pictures(act).list().first()
        val t = Pictures(act).thumb(pic.id)
        assertEquals(Color.WHITE, t.getPixel(30, 30))
        act.contentResolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pic.id), null, null)
    }

    @Test
    fun fullscreenAndNeverFittedToInsets() {
        val insets = onMain { act.window.decorView.rootWindowInsets }
        assertNotNull(insets)
        assertFalse(insets.isVisible(WindowInsets.Type.statusBars()))
        assertFalse(insets.isVisible(WindowInsets.Type.navigationBars()))
        assertEquals(0, onMain { act.window.attributes.fitInsetsTypes })
        val loc = IntArray(2)
        onMain { act.root.getLocationOnScreen(loc) }
        assertEquals(0, loc[0]); assertEquals(0, loc[1])
        assertEquals(onMain { act.window.decorView.height }, onMain { act.root.height })
    }

    @Test
    fun selectedToolInvertedAndOptionFramed() {
        onMain { act.toolButtons["line"]!!.performClick() }
        assertTrue(act.toolButtons["line"]!!.picked)
        assertFalse(act.toolButtons["pencil"]!!.picked)
        onMain { act.toolButtons["pencil"]!!.performClick() }
        assertTrue(act.toolButtons["pencil"]!!.picked)
        assertTrue("boot size 10 lit", act.optionButtons[2].picked)
        onMain { act.optionButtons[0].performClick() }
        assertTrue(act.optionButtons[0].picked)
        assertFalse(act.optionButtons[2].picked)
        assertEquals(1.0, onMain { KiddoPaint.Tools.Pencil.size }, 0.0)
    }
}
