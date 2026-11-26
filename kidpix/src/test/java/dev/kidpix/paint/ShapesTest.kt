package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
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

/**
 * Mid-drag previews for line/square/circle. The scenario format cannot stop mid-drag (a stroke
 * always ends in mouseup), so this drives ev_canvas directly.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ShapesTest {
    private lateinit var clock: VirtualClock

    @Before
    fun setUp() {
        clock = VirtualClock()
        KTime.backend = clock
        KTime.origin = VirtualClock.T0
        KiddoPaint.resetWorld()
        KDisplay.store = null
        KDisplay.init_layers(600, 400)
        init_kiddo_defaults()
        init_subtool_bars()
        init_color_selector()
        clock.runDue()
    }

    private fun ev(type: String, x: Int, y: Int) {
        clock.advance(16)
        val e = Ev(x.toDouble(), y.toDouble(), type)
        e.buttons = if (type == "mouseup") 0 else 1
        ev_canvas(e)
        clock.runDue()
    }

    private fun ink(c: KCanvas, x: Int, y: Int) = (c.bitmap.getPixel(x, y) ushr 24) > 0

    @Test
    fun linePreviewStompsAndCommits() {
        press_main_tool("line")
        ev("mousedown", 100, 100)
        ev("mousemove", 200, 200)
        ev("mousemove", 300, 100)
        assertTrue("preview on tmp", ink(KDisplay.canvas, 250, 100))
        assertTrue("old preview stomped", !ink(KDisplay.canvas, 150, 150))
        assertTrue("main untouched mid-drag", !ink(KDisplay.main_canvas, 250, 100))
        ev("mouseup", 300, 100)
        assertTrue("committed", ink(KDisplay.main_canvas, 250, 100))
        assertTrue("tmp cleared", !ink(KDisplay.canvas, 250, 100))
    }

    @Test
    fun lineShiftConstrains() {
        press_main_tool("line")
        KiddoPaint.Current.modified = true
        ev("mousedown", 100, 100)
        ev("mouseup", 300, 140)
        assertTrue(ink(KDisplay.main_canvas, 250, 100))
        assertTrue(!ink(KDisplay.main_canvas, 250, 130))
    }

    @Test
    fun lineRainbowDoesNotStomp() {
        press_main_tool("line")
        press_submenu(KiddoPaint.Submenu.line.size - 1)
        assertEquals(false, KiddoPaint.Tools.Line.stomp)
        ev("mousedown", 100, 100)
        ev("mousemove", 200, 200)
        ev("mousemove", 300, 100)
        assertTrue("trail kept", ink(KDisplay.canvas, 150, 150))
    }

    @Test
    fun squarePreviewOnPreviewLayer() {
        press_main_tool("square")
        ev("mousedown", 100, 100)
        ev("mousemove", 200, 180)
        assertTrue("outline on preview", ink(KDisplay.previewCanvas, 150, 100))
        assertTrue("main untouched mid-drag", !ink(KDisplay.main_canvas, 150, 100))
        ev("mouseup", 300, 250)
        assertTrue("committed", ink(KDisplay.main_canvas, 200, 100))
        assertTrue("empty fill by default", !ink(KDisplay.main_canvas, 200, 175))
    }

    @Test
    fun circlePreviewStomps() {
        press_main_tool("circle")
        press_submenu(1) // solid
        ev("mousedown", 100, 100)
        ev("mousemove", 140, 140)
        ev("mousemove", 300, 300)
        assertTrue("preview on tmp", ink(KDisplay.canvas, 200, 200))
        assertTrue("main untouched mid-drag", !ink(KDisplay.main_canvas, 200, 200))
        ev("mouseup", 300, 300)
        assertTrue("committed", ink(KDisplay.main_canvas, 200, 200))
    }
}
