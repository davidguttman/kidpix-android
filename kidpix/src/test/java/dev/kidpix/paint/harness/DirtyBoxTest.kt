package dev.kidpix.paint.harness

import android.graphics.Rect
import dev.kidpix.paint.canvas.KCanvas
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** KCanvas.takeDirty: the box the app's low-latency pen layer redraws. Must cover every change. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DirtyBoxTest {
    @Before fun on() { KCanvas.trackDirty = true }
    @After fun off() { KCanvas.trackDirty = false }

    private val r = Rect()

    /** Every pixel that differs between [before] and now lies inside the reported box. */
    private fun assertCovers(c: KCanvas, before: IntArray) {
        val now = IntArray(c.width * c.height)
        c.bitmap.getPixels(now, 0, c.width, 0, 0, c.width, c.height)
        val any = c.takeDirty(r)
        for (i in now.indices) if (now[i] != before[i]) {
            assertTrue("changed pixel ${i % c.width},${i / c.width} outside $r", any && r.contains(i % c.width, i / c.width))
        }
    }

    private fun pixels(c: KCanvas) = IntArray(c.width * c.height).also { c.bitmap.getPixels(it, 0, c.width, 0, 0, c.width, c.height) }

    @Test fun nothingDrawnNothingDirty() {
        val c = KCanvas(100, 80)
        c.takeDirty(r)
        assertFalse(c.takeDirty(r))
        assertTrue(r.isEmpty)
    }

    @Test fun strokesRectsImagesTextAndTransformsAreCovered() {
        val c = KCanvas(200, 160)
        val ctx = c.getContext()
        c.takeDirty(r)
        fun step(f: () -> Unit) { val b = pixels(c); f(); assertCovers(c, b) }
        step { ctx.fillStyle = "red"; ctx.fillRect(10, 10, 5, 5) }
        step { ctx.lineWidth = 9.0; ctx.lineJoin = "miter"; ctx.beginPath(); ctx.moveTo(40, 40); ctx.lineTo(60, 41); ctx.lineTo(41, 44); ctx.stroke() }
        step { ctx.save(); ctx.translate(100, 80); ctx.rotate(0.7); ctx.scale(2, 3); ctx.fillRect(-5, -5, 10, 10); ctx.restore() }
        step { ctx.shadowColor = "black"; ctx.shadowBlur = 6.0; ctx.shadowOffsetX = 8.0; ctx.fillRect(120, 20, 10, 10); ctx.shadowColor = "transparent" }
        step { val s = KCanvas(20, 20); s.getContext().fillStyle = "blue"; s.getContext().fillRect(0, 0, 20, 20); ctx.drawImage(s, 150, 100, 30, 25) }
        step { ctx.font = "30px sans-serif"; ctx.fillText("Wg", 20, 140) }
        step { ctx.clearRect(10, 10, 3, 3) }
        step { val d = ctx.getImageData(0, 0, 4, 4); for (i in 0 until 64) d.data[i] = 200; ctx.putImageData(d, 70, 120) }
        step { ctx.beginPath(); ctx.arc(30, 100, 12, 0, 6.3); ctx.fill() }
    }

    /** Chrome-like paths (AaStroke, MsaaSurface, EmojiFallback) write the bitmap their own way. */
    @Test fun analyticMsaaAndEmojiDrawsAreCovered() {
        HarnessFonts.install()
        val c = KCanvas(300, 200) // >= 128 x 129: the accelerated-canvas paths run
        val ctx = c.getContext()
        c.takeDirty(r)
        fun step(tight: Boolean = true, f: () -> Unit) { val b = pixels(c); f(); assertCovers(c, b); if (tight) assertTrue("box $r", r.width() < 120 && r.height() < 120) }
        step { ctx.lineWidth = 3.0; ctx.lineCap = "square"; ctx.beginPath(); ctx.moveTo(20.3, 30.7); ctx.lineTo(80.1, 55.2); ctx.stroke() }
        step { ctx.lineWidth = 7.0; ctx.lineCap = "round"; ctx.beginPath(); ctx.moveTo(150, 40); ctx.lineTo(190, 90); ctx.lineTo(170, 20); ctx.stroke() }
        step { ctx.lineWidth = 0.4; ctx.lineCap = "butt"; ctx.beginPath(); ctx.moveTo(30, 150); ctx.lineTo(90, 190); ctx.stroke() }
        step(tight = false) { ctx.font = "40px sans-serif"; ctx.textBaseline = "middle"; ctx.fillText("a♠♣", 200, 150) }
        assertEquals(false, c.takeDirty(r))
    }

    @Test fun drawBoxIsTight() {
        val c = KCanvas(200, 160)
        c.takeDirty(r)
        c.getContext().fillRect(50, 60, 10, 10)
        assertTrue(c.takeDirty(r))
        assertTrue("box $r is small", r.width() < 20 && r.height() < 20)
    }

    @Test fun clearingTheWholeLayerDirtiesOnlyWhereInkWas() {
        val c = KCanvas(400, 300)
        val ctx = c.getContext()
        c.takeDirty(r)
        ctx.fillRect(100, 100, 10, 10)
        c.takeDirty(r)
        ctx.clearRect(0, 0, 400, 300) // what clearTmp/clearPreview do on every move
        assertTrue(c.takeDirty(r))
        assertTrue("box $r is the old ink, not the canvas", r.width() < 20 && r.height() < 20 && r.contains(100, 100, 110, 110))
        ctx.clearRect(0, 0, 400, 300)
        assertFalse("nothing left to clear", c.takeDirty(r))
        ctx.fillRect(200, 50, 4, 4)
        c.width = 400 // reset: only the ink changed
        assertTrue(c.takeDirty(r)); assertTrue(r.width() < 10)
    }

    @Test fun clearsUnderAClipOrFractionalKeepTheInk() {
        val c = KCanvas(400, 300)
        val ctx = c.getContext()
        ctx.fillRect(10, 10, 100, 100)
        c.takeDirty(r)
        ctx.save(); ctx.beginPath(); ctx.rect(0, 0, 50, 50); ctx.clip()
        ctx.clearRect(0, 0, 400, 300)
        ctx.restore()
        c.takeDirty(r)
        val b = pixels(c)
        ctx.clearRect(0, 0, 400, 300) // the rest of the square goes now: must be dirty
        assertCovers(c, b)
        ctx.fillRect(10, 10, 100, 100)
        c.takeDirty(r)
        ctx.clearRect(0.5, 0.5, 399, 299) // leaves half-cleared edges... no ink there, but ink stays known
        c.takeDirty(r)
        val b2 = pixels(c)
        ctx.fillRect(5, 5, 1, 1); ctx.clearRect(0, 0, 400, 300)
        assertCovers(c, b2)
    }

    @Test fun unboundedCompositeAndResizeAreWholeCanvas() {
        val c = KCanvas(200, 160)
        c.takeDirty(r)
        c.getContext().globalCompositeOperation = "copy"
        c.getContext().fillRect(5, 5, 2, 2)
        assertTrue(c.takeDirty(r)); assertEquals(Rect(0, 0, 200, 160), r)
        c.width = 200
        assertTrue(c.takeDirty(r)); assertEquals(Rect(0, 0, 200, 160), r)
    }

    @Test fun writesBehindCtx2DAreWholeCanvas() {
        val c = KCanvas(200, 160)
        c.takeDirty(r)
        c.bitmap.setPixel(3, 3, 0xff00ff00.toInt())
        assertTrue(c.takeDirty(r)); assertEquals(Rect(0, 0, 200, 160), r)
        // ... also when a tracked draw follows the untracked write
        c.bitmap.setPixel(4, 4, 0xff00ff00.toInt())
        c.getContext().fillRect(150, 150, 2, 2)
        assertTrue(c.takeDirty(r)); assertEquals(Rect(0, 0, 200, 160), r)
    }
}
