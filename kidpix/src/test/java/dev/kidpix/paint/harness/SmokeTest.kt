package dev.kidpix.paint.harness

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SmokeTest {
    @Test
    fun nativeCanvasDraws() {
        val b = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        Canvas(b).drawRect(0f, 0f, 5f, 5f, Paint().apply { color = 0xffff0000.toInt() })
        assertEquals(0xffff0000.toInt(), b.getPixel(2, 2))
        assertEquals(0, b.getPixel(7, 7))
    }
}
