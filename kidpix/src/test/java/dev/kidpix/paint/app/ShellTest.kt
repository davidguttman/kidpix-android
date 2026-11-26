package dev.kidpix.paint.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import dev.kidpix.paint.Ev
import dev.kidpix.paint.History
import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KColors
import dev.kidpix.paint.KCurrent
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KUi
import dev.kidpix.paint.KiddoPaint
import dev.kidpix.paint.PEvent
import dev.kidpix.paint.SubmenuItem
import dev.kidpix.paint.Pencil
import dev.kidpix.paint.Line
import dev.kidpix.paint.Eraser
import dev.kidpix.paint.Ui
import dev.kidpix.paint.canvas.KAssets
import dev.kidpix.paint.colorSelect
import dev.kidpix.paint.engine.Engine
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.VirtualClock
import dev.kidpix.paint.kidpix_android_boot
import dev.kidpix.paint.show_generic_submenu
import dev.kidpix.paint.sprites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The overlay's behaviour (init/Android.kt) as the app drives it: pen, palm, Undo Guy, option memory, state. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ShellTest {
    private lateinit var clock: VirtualClock
    private val nudges = ArrayList<String>()
    private var badge: Triple<String?, Double, Double>? = null

    private val ui = object : Ui {
        override fun nudge(id: String) { nudges.add(id) }
        override fun penBadge(toolId: String?, clientX: Double, clientY: Double) { badge = Triple(toolId, clientX, clientY) }
    }

    @Before
    fun setUp() {
        System.getProperty("kidpix.assets")?.let { KAssets.useDirectory(File(it)) }
        clock = VirtualClock()
        KTime.backend = clock
        KTime.origin = VirtualClock.T0
        KUi.ui = ui
        Engine.start(400, 300, null)
        kidpix_android_boot()
        clock.runDue()
        clock.advance(5000) // no pen seen recently
    }

    private var nextId = 10
    private fun pen(x: Double, y: Double, buttons: Int = 1, pressure: Double = 0.5, id: Int = nextId) =
        PEvent(id, "pen", x, y, pressure, buttons)
    private fun touch(x: Double, y: Double, id: Int = nextId, primary: Boolean = true) =
        PEvent(id, "touch", x, y, 0.5, 1, primary)

    private fun penStroke(x0: Double, y0: Double, x1: Double, y1: Double, buttons: Int = 1, pressure: Double = 0.5) {
        val id = nextId++
        KAndroid.pointerdown(pen(x0, y0, buttons, pressure, id))
        clock.advance(16)
        KAndroid.pointermove(pen((x0 + x1) / 2, (y0 + y1) / 2, buttons, pressure, id))
        clock.advance(16)
        KAndroid.pointermove(pen(x1, y1, buttons, pressure, id))
        KAndroid.pointerup(PEvent(id, "pen", x1, y1, 0.0, buttons and 1.inv()))
    }

    private fun touchStroke(x0: Double, y0: Double, x1: Double, y1: Double) {
        val id = nextId++
        KAndroid.pointerdown(touch(x0, y0, id))
        KAndroid.pointermove(touch(x1, y1, id))
        KAndroid.pointerup(PEvent(id, "touch", x1, y1, 0.0, 0))
    }

    private fun alpha(x: Int, y: Int) = Color.alpha(KDisplay.main_canvas.bitmap.getPixel(x, y))
    private fun inked(): Int {
        val b = KDisplay.main_canvas.bitmap
        var n = 0
        for (y in 0 until b.height) for (x in 0 until b.width) if (Color.alpha(b.getPixel(x, y)) > 0) n++
        return n
    }

    // ---------------------------------------------------------------- boot

    @Test
    fun bootStateMatchesOverlay() {
        assertEquals(9.0, KiddoPaint.Tools.Pencil.size, 0.0)
        assertEquals("pencil", KAndroid.currentToolId)
        val items = KiddoPaint.Submenu["pencil"]!!
        val groups = KAndroid.submenuGroups(items)
        assertEquals(setOf(groups[0][2], groups[1][0]), KAndroid.selectedOptions())
    }

    // ---------------------------------------------------------------- pen input

    @Test
    fun penPressureScalesPencilAndRestores() {
        penStroke(51.0, 101.0, 251.0, 101.0, pressure = 1.0) // 0.5 + 1.5 -> 2x: 18 px wide
        assertTrue("thick at +7", alpha(150, 107) > 0)
        assertEquals(1.0, KCurrent.scaling, 0.0)
        penStroke(51.0, 201.0, 251.0, 201.0, pressure = 0.1) // 0.35x: ~3 px
        assertEquals(0, alpha(150, 200 + 4))
        assertTrue(alpha(150, 200) > 0)
    }

    @Test
    fun wholePixelsClampedInsideCanvas() {
        val e = KAndroid.fakeEvent("mousedown", pen(0.2, 5000.0))
        assertEquals(0.0, e.offsetX, 0.0)
        assertEquals(299.0, e.offsetY, 0.0)
        val f = KAndroid.fakeEvent("mousedown", pen(10.6, 20.4))
        assertEquals(10.0, f.offsetX, 0.0) // round(10.6 - 1) = 10
        assertEquals(19.0, f.offsetY, 0.0)
    }

    @Test
    fun palmIgnoredWhilePenNearThenAllowed() {
        KAndroid.pointermove(PEvent(1, "pen", 300.0, 250.0, 0.0, 0)) // hovering pen
        clock.advance(500)
        touchStroke(20.0, 20.0, 200.0, 20.0)
        assertEquals(0, inked())
        clock.advance(900)
        touchStroke(20.0, 20.0, 200.0, 20.0)
        assertTrue(inked() > 0)
    }

    @Test
    fun fingerIgnoredWhilePenDrawsAndPenEndsFingerStroke() {
        val pid = nextId++
        KAndroid.pointerdown(touch(20.0, 50.0, pid))
        KAndroid.pointermove(touch(60.0, 50.0, pid))
        KAndroid.pointerdown(pen(100.0, 100.0, id = 99)) // pen beats finger
        assertEquals("pen", KAndroid.state.activeType)
        assertTrue(alpha(40, 49) > 0) // the finger stroke was committed
        val f = nextId++
        KAndroid.pointerdown(touch(20.0, 250.0, f, primary = false))
        KAndroid.pointermove(touch(200.0, 250.0, f))
        assertEquals(0, alpha(100, 249))
        KAndroid.pointerup(PEvent(99, "pen", 100.0, 100.0, 0.0, 0))
        assertNull(KAndroid.state.activeId)
    }

    @Test
    fun sideButtonAndEraserEndSwapInTheEraserAndBack() {
        KAndroid.pointerdown(pen(50.0, 50.0, buttons = 3, id = 40)) // contact + barrel
        val pe = KAndroid.penEraser!!
        assertSame(pe, KCurrent.tool)
        assertEquals(14.0, pe.size, 0.0)
        assertFalse(pe.isSquareEraser)
        assertNotSame(KiddoPaint.Tools.Eraser, pe)
        assertEquals("", KAndroid.toolName(pe)) // not in KiddoPaint.Tools: no pressure
        KAndroid.pointerup(PEvent(40, "pen", 50.0, 50.0, 0.0, 2))
        assertSame(KiddoPaint.Tools.Pencil, KCurrent.tool)

        KAndroid.pointerdown(pen(50.0, 50.0, buttons = 32, id = 41)) // eraser end
        assertSame(pe, KCurrent.tool)
        KAndroid.pointerup(PEvent(41, "pen", 50.0, 50.0, 0.0, 0))
        assertSame(KiddoPaint.Tools.Pencil, KCurrent.tool)
    }

    @Test
    fun eraserStrokesFillGaps() {
        KAndroid.pointerdown(pen(11.0, 11.0, buttons = 32, id = 50))
        val before = KDisplay.step
        KAndroid.pointermove(pen(111.0, 11.0, buttons = 32, id = 50))
        // 100 px at a 7 px step (14 px eraser): 13 filled in + the sample itself
        assertEquals(14, KDisplay.step - before)
        KAndroid.pointerup(PEvent(50, "pen", 111.0, 11.0, 0.0, 0))
    }

    @Test
    fun pencilFollowsEveryCoalescedPenSample() {
        KAndroid.pointerdown(pen(11.0, 11.0, id = 60))
        val before = KDisplay.step
        val samples = (1..5).map { pen(11.0 + it * 10, 11.0, id = 60) }
        KAndroid.pointermove(PEvent(60, "pen", 61.0, 11.0, 0.5, 1, coalesced = samples))
        assertEquals(5, KDisplay.step - before)
        // a finger stroke on the pencil uses only the latest sample
        KAndroid.pointerup(PEvent(60, "pen", 61.0, 11.0, 0.0, 0))
        clock.advance(2000)
        KAndroid.pointerdown(touch(11.0, 100.0, 61))
        val b2 = KDisplay.step
        KAndroid.pointermove(PEvent(61, "touch", 61.0, 100.0, 0.5, 1, coalesced = samples))
        assertEquals(1, KDisplay.step - b2)
    }

    @Test
    fun lostPenUpIsRecoveredByHover() {
        KAndroid.pointerdown(pen(20.0, 20.0, id = 70))
        KAndroid.pointermove(pen(200.0, 20.0, id = 70))
        assertEquals(0, alpha(100, 19)) // still on tmp
        KAndroid.pointermove(PEvent(1, "pen", 250.0, 150.0, 0.0, 0)) // hover: the up was lost
        assertNull(KAndroid.state.activeId)
        assertFalse(KiddoPaint.Tools.Pencil.isDown)
        assertTrue(alpha(100, 19) > 0)
    }

    @Test
    fun newPrimaryDownClosesAStuckStroke() {
        KAndroid.pointerdown(touch(20.0, 20.0, 80))
        KAndroid.pointermove(touch(200.0, 20.0, 80))
        KAndroid.pointerdown(touch(20.0, 200.0, 81, primary = true))
        assertEquals(81, KAndroid.state.activeId)
        assertTrue(alpha(100, 19) > 0)
    }

    @Test
    fun penUndoButtonIsEdgeTriggered() {
        penStroke(20.0, 20.0, 200.0, 20.0)
        clock.advance(1000)
        KAndroid.pointermove(PEvent(1, "pen", 250.0, 150.0, 0.0, 4))
        KAndroid.pointermove(PEvent(1, "pen", 251.0, 150.0, 0.0, 4)) // held: no second tap
        clock.advance(400)
        assertEquals(0, inked())
        assertFalse(History.canUndo)
        // press twice quickly: redo
        KAndroid.pointermove(PEvent(1, "pen", 251.0, 150.0, 0.0, 0))
        KAndroid.pointermove(PEvent(1, "pen", 251.0, 150.0, 0.0, 4))
        clock.advance(100)
        KAndroid.pointermove(PEvent(1, "pen", 251.0, 150.0, 0.0, 0))
        KAndroid.pointermove(PEvent(1, "pen", 251.0, 150.0, 0.0, 4))
        clock.advance(400)
        assertTrue(inked() > 0) // back again (first press undid nothing yet: the double tap redid)
    }

    @Test
    fun hoverShowsBadgeAndPreviewLeaveClears() {
        KAndroid.pointermove(PEvent(1, "pen", 120.0, 130.0, 0.0, 0))
        assertEquals(Triple<String?, Double, Double>("pencil", 70.0, 80.0), badge)
        KAndroid.pointermove(PEvent(1, "pen", 120.0, 130.0, 0.0, 2))
        assertEquals("eraser", badge!!.first)
        KAndroid.pointerdown(pen(120.0, 130.0, id = 90))
        assertNull(badge!!.first)
        KAndroid.pointerup(PEvent(90, "pen", 120.0, 130.0, 0.0, 0))
        KDisplay.previewContext.fillRect(0, 0, 10, 10)
        KAndroid.pointerleave(PEvent(1, "pen", 120.0, 130.0, 0.0, 0))
        assertEquals(0, Color.alpha(KDisplay.previewCanvas.bitmap.getPixel(5, 5)))
        assertFalse(KAndroid.state.penHovering)
    }

    @Test
    fun toolbarGuardedFromPalmsWhilePenDraws() {
        KAndroid.pointerdown(pen(50.0, 50.0, id = 91))
        assertTrue(KAndroid.toolbarBlocked("touch"))
        assertFalse(KAndroid.toolbarBlocked("pen"))
        KAndroid.pointerup(PEvent(91, "pen", 50.0, 50.0, 0.0, 0))
        assertFalse(KAndroid.toolbarBlocked("touch"))
    }

    // ---------------------------------------------------------------- Undo Guy

    @Test
    fun undoGuyTapUndoesAtOnceAndADoubleTapGoesForward() {
        touchStroke(20.0, 20.0, 200.0, 20.0)
        touchStroke(20.0, 120.0, 200.0, 120.0)
        KAndroid.pressMain("undo") // no wait for a possible second tap
        assertEquals(0, alpha(100, 119))
        assertTrue(alpha(100, 19) > 0)
        clock.advance(400)
        // double tap: forward again
        KAndroid.pressMain("undo"); KAndroid.pressMain("undo")
        assertTrue(alpha(100, 119) > 0)
        clock.advance(1000)
        assertTrue(alpha(100, 119) > 0)
        assertTrue(nudges.isEmpty())
        // a double tap with nothing to redo leaves the picture as it was, with a nudge
        KAndroid.pressMain("undo"); KAndroid.pressMain("undo")
        assertTrue(alpha(100, 119) > 0)
        assertTrue(alpha(100, 19) > 0)
        assertEquals(listOf("undo"), nudges)
        clock.advance(1000)
        assertEquals(listOf("undo"), nudges)
    }

    @Test
    fun noStepLimitAndNudgeWhenNothingLeft() {
        repeat(32) { i -> touchStroke(5.0, 5.0 + i * 8, 300.0, 5.0 + i * 8) }
        repeat(31) { KAndroid.pressMain("undo"); clock.advance(400) }
        assertTrue("1 stroke survives 31 undos", inked() > 0)
        assertTrue(nudges.isEmpty())
        KAndroid.pressMain("undo"); clock.advance(400)
        assertEquals("all 32 undone (no 30-step limit)", 0, inked())
        assertTrue(nudges.isEmpty())
        KAndroid.pressMain("undo"); clock.advance(400)
        assertEquals(listOf("undo"), nudges)
        KAndroid.pressMain("undo"); KAndroid.pressMain("undo") // redo
        KAndroid.pressMain("undo"); KAndroid.pressMain("undo")
        assertEquals(listOf("undo"), nudges)
    }

    @Test
    fun newStrokeDropsRedo() {
        touchStroke(20.0, 20.0, 200.0, 20.0)
        KAndroid.pressMain("undo"); clock.advance(400)
        touchStroke(20.0, 120.0, 200.0, 120.0)
        KAndroid.pressMain("undo"); KAndroid.pressMain("undo")
        assertEquals(listOf("undo"), nudges)
    }

    // ---------------------------------------------------------------- option memory

    @Test
    fun toolReappliesRememberedOption() {
        KAndroid.pressMain("pencil")
        KAndroid.pressSubmenu(0) // Size 1
        assertEquals(1.0, KiddoPaint.Tools.Pencil.size, 0.0)
        KAndroid.pressMain("line")
        assertEquals("line", KAndroid.currentToolId)
        KiddoPaint.Tools.Pencil.size = 13.0 // something reset it
        KAndroid.pressMain("pencil")
        assertEquals(1.0, KiddoPaint.Tools.Pencil.size, 0.0)
        assertTrue(0 in KAndroid.selectedOptions())
    }

    @Test
    fun bootOptionTapBeforeAnyToolIsTheWebViewQuirk() {
        // overlay: currentSubmenu is null until the first show_generic_submenu after boot
        KAndroid.pressSubmenu(0)
        assertEquals(1.0, KiddoPaint.Tools.Pencil.size, 0.0)
        assertEquals(setOf(0), KAndroid.selectedOptions())
        KAndroid.pressMain("pencil")
        assertEquals(9.0, KiddoPaint.Tools.Pencil.size, 0.0)
    }

    @Test
    fun pageArrowsAreNotPicks() {
        var page = 0
        KiddoPaint.Submenu.sprites = mutableListOf(
            SubmenuItem("A", text = "A", handler = { }),
            SubmenuItem("B", text = "B", handler = { }),
            SubmenuItem("spacer", invisible = true),
            SubmenuItem("Next Page", text = ">", handler = { page++; show_generic_submenu("sprites") }),
        )
        show_generic_submenu("sprites")
        assertEquals(setOf(0), KAndroid.selectedOptions()) // upstream picks the first itself
        KAndroid.pressSubmenu(1)
        assertEquals(setOf(1), KAndroid.selectedOptions())
        KAndroid.pressSubmenu(3)
        assertEquals(1, page)
        assertEquals(emptySet<Int>(), KAndroid.selectedOptions())
    }

    @Test
    fun lettersLightTheTappedOne() {
        KAndroid.pressMain("alphabet")
        assertEquals("alphabet", KAndroid.currentToolId)
        KAndroid.pressAlphabet(3)
        assertEquals(3, KAndroid.selectedLetter())
    }

    // ---------------------------------------------------------------- open, save, state

    @Test
    fun openedPictureIsFittedAndUndoable() {
        touchStroke(20.0, 20.0, 200.0, 20.0)
        val red = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        KAndroid.loadPicture(red)
        // 400x300 canvas: scale 2, 400x200 centered at y 50
        assertEquals(Color.RED, KDisplay.main_canvas.bitmap.getPixel(200, 150))
        assertEquals(0, alpha(200, 20))
        KAndroid.pressMain("undo"); clock.advance(400)
        assertTrue(alpha(100, 19) > 0)
        assertEquals(0, alpha(200, 150))
    }

    @Test
    fun savedPngHasWhitePaper() {
        val b = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        b.setPixel(1, 1, Color.BLUE)
        val bytes = Pictures.flatten(b)
        val back = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(Color.WHITE, back.getPixel(5, 5))
        assertEquals(Color.BLUE, back.getPixel(1, 1))
    }

    @Test
    fun stateSurvivesARestart() {
        KAndroid.pressMain("pencil")
        KAndroid.pressSubmenu(0) // size 1
        KiddoPaint.Colors.nextPalette(); KiddoPaint.Colors.nextPalette()
        colorSelect(5)
        val color = KCurrent.color
        KAndroid.pressMain("line")
        val json = AppState.save()

        Engine.start(400, 300, null) // process killed, app started again
        kidpix_android_boot()
        assertEquals(0, KColors.Current.PaletteNumber)
        AppState.restore(json)
        assertEquals("line", KAndroid.currentToolId)
        assertSame(KiddoPaint.Tools.Line, KCurrent.tool)
        assertEquals(2, KColors.Current.PaletteNumber)
        assertEquals(color, KCurrent.color)
        KAndroid.pressMain("pencil")
        assertEquals(1.0, KiddoPaint.Tools.Pencil.size, 0.0)
    }

    @Test
    fun pictureSurvivesARestartThroughTheStore() {
        val dir = File(System.getProperty("java.io.tmpdir"), "kp-store-" + System.nanoTime()).apply { mkdirs() }
        val store = FilePictureStore(dir)
        Engine.start(400, 300, store)
        kidpix_android_boot()
        touchStroke(20.0, 20.0, 200.0, 20.0)
        store.flush()
        Engine.start(400, 300, FilePictureStore(dir))
        assertTrue(alpha(100, 19) > 0)
        dir.deleteRecursively()
    }

    @Test
    fun canvasKeepsItsLandscapeSizeInAPortraitWindow() {
        // Lenovo TB336FU, density 2: bars 50+50+104 dp = 408 px beside the view
        val land = PaintView.canvasSizeFor(2560 - 408, 1600, 2560, 1600, 2f)
        assertEquals(1074 to 798, land)
        assertEquals(land, PaintView.canvasSizeFor(1600 - 408, 2560, 1600, 2560, 2f))
        // the emulator shape used so far (1920x1200 at 1.5): unchanged in landscape
        assertEquals(1074 to 798, PaintView.canvasSizeFor(1920 - 306, 1200, 1920, 1200, 1.5f))
        assertEquals(1074 to 798, PaintView.canvasSizeFor(1200 - 306, 1920, 1200, 1920, 1.5f))
    }

    @Test
    fun aStoredPictureLargerThanTheCanvasIsShrunkNotCut() {
        val dir = File(System.getProperty("java.io.tmpdir"), "kp-store-" + System.nanoTime()).apply { mkdirs() }
        // saved by an older build on a portrait canvas: 200x600, a black mark at the very bottom
        val tall = Bitmap.createBitmap(200, 600, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(tall).drawRect(0f, 560f, 200f, 600f, android.graphics.Paint().apply { color = Color.BLACK })
        File(dir, "picture.png").outputStream().use { tall.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Engine.start(400, 300, FilePictureStore(dir))
        // fitted: scale 0.5, 100x300 centred at x 150; the mark lands at the bottom rows 280..300
        assertTrue(alpha(200, 295) > 200)
        assertEquals(0, alpha(200, 200))
        assertEquals(0, alpha(120, 295))
        // a picture that fits is still drawn at 0,0 unscaled
        val small = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888).apply { setPixel(99, 49, Color.BLACK) }
        File(dir, "picture.png").outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Engine.start(400, 300, FilePictureStore(dir))
        assertEquals(255, alpha(99, 49))
        dir.deleteRecursively()
    }

    @Test
    fun portraitWindowTurnsTheLandscapeUiAQuarterClockwise() {
        // landscape window: as is
        val land = LandscapeFrame.Turn.of(2560, 1600, android.view.Surface.ROTATION_90)
        assertFalse(land.turned)
        assertEquals(2560 to 1600, land.childW to land.childH)
        // portrait window (ROTATION_0 and ROTATION_180): laid out 2560x1600, turned +90 about 0,0, moved right by 1600
        for (rot in listOf(android.view.Surface.ROTATION_0, android.view.Surface.ROTATION_180)) {
            val t = LandscapeFrame.Turn.of(1600, 2560, rot)
            assertTrue(t.turned)
            assertEquals(2560 to 1600, t.childW to t.childH)
            assertEquals(90f, t.degrees, 0f)
            // the child's corners land on the window's corners: top-left -> top-right, bottom-right -> bottom-left
            assertEquals(1600f to 0f, t.toWindow(0f, 0f))
            assertEquals(0f to 2560f, t.toWindow(2560f, 1600f))
            assertEquals(0f to 0f, t.toWindow(0f, 1600f))
            assertEquals(1600f to 2560f, t.toWindow(2560f, 0f))
        }
        // and the canvas has the same size either way (PaintView inside the turned child is landscape)
        assertEquals(PaintView.canvasSizeFor(2560 - 408, 1600, 2560, 1600, 2f), PaintView.canvasSizeFor(2560 - 408, 1600, 1600, 2560, 2f))
    }

    @Test
    fun turnMatchesTheViewTransform() {
        // what View does with pivot 0,0, rotation and translation must be what Turn.toWindow says
        val t = LandscapeFrame.Turn.of(1600, 2560, android.view.Surface.ROTATION_0)
        val m = android.graphics.Matrix().apply { setRotate(t.degrees); postTranslate(t.tx, t.ty) }
        for ((x, y) in listOf(0f to 0f, 300f to 200f, 2152f to 1599f, 1280f to 800f)) {
            val p = floatArrayOf(x, y); m.mapPoints(p)
            val (wx, wy) = t.toWindow(x, y)
            assertEquals(p[0], wx, 0.01f); assertEquals(p[1], wy, 0.01f)
        }
    }

    @Suppress("unused")
    private fun unused(e: Ev) = e
}
