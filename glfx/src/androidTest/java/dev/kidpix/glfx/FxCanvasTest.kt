package dev.kidpix.glfx

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class FxCanvasTest {

    private fun premul(b: Bitmap): ByteArray = ByteBuffer.allocate(b.byteCount).also { b.copyPixelsToBuffer(it) }.array()

    @Test
    fun passThroughKeepsTransparency() {
        val src = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        for (y in 0 until 48) for (x in 0 until 64) {
            src.setPixel(x, y, Color.argb((x * 4) and 0xFF, (y * 5) and 0xFF, 200, (x * 7 + y) and 0xFF))
        }
        val gfx = Fx.canvas()
        try {
            val tex = gfx.texture(src)
            val out = gfx.draw(tex).brightnessContrast(0.0, 0.0).update().toBitmap()
            tex.destroy()
            assertEquals(64, out.width); assertEquals(48, out.height)
            val a = premul(src); val b = premul(out)
            var worst = 0
            for (i in a.indices) worst = maxOf(worst, abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)))
            assertTrue("premultiplied round trip off by $worst", worst <= 1)
            // orientation: top-left pixel stays top-left
            assertEquals(Color.alpha(src.getPixel(0, 0)), Color.alpha(out.getPixel(0, 0)))
            assertEquals(Color.alpha(src.getPixel(63, 47)), Color.alpha(out.getPixel(63, 47)))
            // fully transparent stays fully transparent
            assertEquals(0, Color.alpha(out.getPixel(0, 0)))
        } finally {
            gfx.destroy()
        }
    }

    @Test
    fun straightRgbaBytesLikeImageData() {
        val w = 4; val h = 2
        val rgba = ByteArray(w * h * 4) { i -> if (i % 4 == 3) 128.toByte() else 255.toByte() }
        val gfx = Fx.canvas()
        try {
            val out = gfx.draw(gfx.texture(rgba, w, h)).update().toBitmap()
            val p = premul(out)
            assertEquals(128, p[0].toInt() and 0xFF) // 255 * 128/255 premultiplied
            assertEquals(128, p[3].toInt() and 0xFF)
            assertTrue(gfx.getPixelArray().all { (it.toInt() and 0xFF) == 255 || (it.toInt() and 0xFF) == 128 })
        } finally {
            gfx.destroy()
        }
    }

    @Test
    fun wrongThreadFailsLoudly() {
        val gfx = Fx.canvas()
        val tex = gfx.texture(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        var error: Throwable? = null
        val t = Thread({ try { gfx.draw(tex) } catch (e: Throwable) { error = e } }, "intruder")
        t.start(); t.join()
        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        assertTrue(error!!.message!!.contains("intruder"))
        // still usable from the owner
        gfx.draw(tex).swirl(4.0, 4.0, 4.0, 1.0).update().toBitmap()
        tex.destroy()
        gfx.destroy()
        try { gfx.update(); fail("used after destroy") } catch (_: IllegalStateException) {}
    }

    @Test
    fun twoCanvasesOnOneThread() {
        val a = Fx.canvas(); val b = Fx.canvas()
        val src = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val ta = a.texture(src); val tb = b.texture(src)
        val ra = a.draw(ta).hueSaturation(0.0, -1.0).update().toBitmap()
        val rb = b.draw(tb).brightnessContrast(0.0, 0.0).update().toBitmap()
        assertEquals(Color.RED, rb.getPixel(8, 8))
        val g = ra.getPixel(8, 8)
        assertTrue("desaturated red should be grey: ${Integer.toHexString(g)}", abs(Color.red(g) - Color.green(g)) <= 1)
        a.destroy(); b.destroy()
    }

    @Test
    fun splineInterpolateIdentity() {
        val v = Fx.splineInterpolate(listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0)))
        assertEquals(0, v[0]); assertEquals(255, v[255]); assertEquals(127, v[127]) // floor(256 * 127/255)
    }
}
