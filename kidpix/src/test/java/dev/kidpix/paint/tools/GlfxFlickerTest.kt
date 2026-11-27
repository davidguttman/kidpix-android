package dev.kidpix.paint.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.kidpix.paint.GfxOp
import dev.kidpix.paint.GlfxBridge
import dev.kidpix.paint.JumbleFx
import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KGlfx
import dev.kidpix.paint.KUi
import dev.kidpix.paint.KiddoPaint
import dev.kidpix.paint.PEvent
import dev.kidpix.paint.Ui
import dev.kidpix.paint.WholeCanvasEffect
import dev.kidpix.paint.canvas.KAssets
import dev.kidpix.paint.engine.Engine
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.VirtualClock
import dev.kidpix.paint.harness.LogSession.Companion.crc
import dev.kidpix.paint.kidpix_android_boot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The glfx mixers (Swirl, Pancake Stack, ...) render on the GL thread: the first rendered picture
 * arrives a frame or more after the press. Until it does, the screen (main + tmp, as PaintView and
 * the front layer composite them) must still show the picture, never a blank canvas; the same at
 * every move, the release and a cancel. A fake bridge holds the asynchronous pictures back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GlfxFlickerTest {
    private lateinit var clock: VirtualClock
    private val pending = ArrayList<() -> Unit>()

    /** Identity "effects": the picture uploaded comes back; asynchronous renders wait in [pending]. */
    private val bridge = object : GlfxBridge {
        override fun texture(bitmap: Bitmap): Any = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        override fun destroyTexture(handle: Any) {}
        override fun render(texture: Any, ops: List<GfxOp>, reuse: Bitmap?): Bitmap = (texture as Bitmap).copy(Bitmap.Config.ARGB_8888, true)
        override fun renderAsync(texture: Any, ops: List<GfxOp>, done: (Bitmap) -> Unit) {
            val out = render(texture, ops, null)
            pending.add { done(out) }
        }
    }

    @Before fun setUp() {
        System.getProperty("kidpix.assets")?.let { KAssets.useDirectory(File(it)) }
        clock = VirtualClock()
        KTime.backend = clock
        KTime.origin = VirtualClock.T0
        KUi.ui = object : Ui {}
        Engine.start(400, 300, null)
        kidpix_android_boot()
        clock.runDue()
        clock.advance(5000)
        KGlfx.bridge = bridge
    }

    @After fun tearDown() { KGlfx.bridge = dev.kidpix.paint.HeadlessGlfx }

    /** What the screen shows: white, main, tmp (preview/anim layers are blank here). */
    private fun screen(): Long {
        val b = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        for (l in listOf(KDisplay.bnimCanvas, KDisplay.main_canvas, KDisplay.canvas, KDisplay.animCanvas, KDisplay.previewCanvas)) c.drawBitmap(l.bitmap, 0f, 0f, null)
        return crc(b)
    }

    private fun blank(): Long {
        val b = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888); b.eraseColor(Color.WHITE); return crc(b)
    }

    private fun touch(x: Double, y: Double, buttons: Int = 1) = PEvent(7, "touch", x, y, 0.5, buttons)

    private fun drawSomething() {
        val c = KDisplay.main_context
        c.fillStyle = "rgb(200, 30, 30)"; c.fillRect(40, 40, 200, 120)
        c.fillStyle = "rgb(20, 30, 200)"; c.fillRect(150, 100, 200, 150)
    }

    private val effects = listOf(JumbleFx.SWIRL, JumbleFx.PANCAKE, JumbleFx.PINCH, JumbleFx.LENSBLUR, JumbleFx.TRIBLUR, JumbleFx.ZOOM,
        JumbleFx.HEXAGON, JumbleFx.INK, JumbleFx.HUE, JumbleFx.SAT, JumbleFx.EDGE)

    private fun choose(effect: String) {
        KAndroid.pressMain("jumble", false)
        KiddoPaint.Tools.WholeCanvasEffect.effect = effect
        KiddoPaint.Current.tool = KiddoPaint.Tools.WholeCanvasEffect
    }

    @Test fun theOldPictureStaysUntilTheFirstRenderedFrameArrives() {
        drawSomething()
        val picture = screen()
        assertNotEquals(blank(), picture)
        for (effect in effects) {
            choose(effect)
            pending.clear()
            KAndroid.pointerdown(touch(200.0, 150.0))
            assertEquals("$effect press, before the first GL frame: the picture, not blank", picture, screen())
            clock.advance(16)
            KAndroid.pointermove(touch(260.0, 190.0))
            assertEquals("$effect drag, GL frames still on the way", picture, screen())
            pending.removeAt(0).invoke() // the first frame arrives (identity effect: the same picture)
            assertEquals("$effect drag, first frame shown", picture, screen())
            clock.advance(16)
            KAndroid.pointermove(touch(280.0, 200.0))
            assertEquals("$effect drag", picture, screen())
            clock.advance(16)
            KAndroid.pointerup(touch(280.0, 200.0, 0))
            assertEquals("$effect release", picture, screen())
            pending.forEach { it() } // late frames of the ended drag change nothing
            assertEquals("$effect after late frames", picture, screen())
            clock.advance(400)
        }
    }

    @Test fun aCancelBeforeAnyFrameKeepsThePicture() {
        drawSomething()
        val picture = screen()
        for (effect in effects) {
            choose(effect)
            pending.clear()
            KAndroid.pointerdown(touch(100.0, 100.0))
            assertEquals("$effect press", picture, screen())
            clock.advance(16)
            KAndroid.pointercancel(touch(100.0, 100.0, 0))
            assertEquals("$effect cancel", picture, screen())
            pending.forEach { it() }
            assertEquals("$effect cancel, late frames", picture, screen())
            clock.advance(400)
        }
    }
}
