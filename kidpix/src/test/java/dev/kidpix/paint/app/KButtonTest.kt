package dev.kidpix.paint.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The WebView app's buttons, measured on the emulator at density 1.5 (apptest). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1920dp-h1200dp-hdpi")
class KButtonTest {
    private fun render(setup: KButton.() -> Unit): Bitmap {
        val b = KButton(RuntimeEnvironment.getApplication()).apply(setup)
        val s = 75
        b.measure(View.MeasureSpec.makeMeasureSpec(s, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(s, View.MeasureSpec.EXACTLY))
        b.layout(0, 0, s, s)
        return Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888).also { b.draw(Canvas(it)) }
    }

    private fun grey(bm: Bitmap, x: Int, y: Int) = Color.red(bm.getPixel(x, y))

    @Test
    fun outsetDarkLikeBlink() {
        assertEquals(Color.rgb(33, 33, 33), KButton.outsetDark(Color.rgb(118, 118, 118)))
        assertEquals(Color.rgb(171, 171, 171), KButton.outsetDark(Color.WHITE))
        assertEquals(3f, KButton.borderWidthPx(1.5f))
        assertEquals(2f, KButton.borderWidthPx(1f))
    }

    /** img rects inside buttons as the WebView app lays them out (getBoundingClientRect, CSS px). */
    @Test
    fun imagesWhereChromePutsThem() {
        assertEquals(8.5f, KButton.imgLeftDp(50f, 33f), 0.01f); assertEquals(6.833f, KButton.imgTopDp(50f, 33f), 0.01f) // save
        assertEquals(2f, KButton.imgLeftDp(50f, 48f), 0.01f); assertEquals(2f, KButton.imgTopDp(50f, 48f), 0.01f)       // tools
        assertEquals(8f, KButton.imgLeftDp(52f, 36f), 0.01f); assertEquals(6.333f, KButton.imgTopDp(52f, 36f), 0.01f)  // options
        assertEquals(2f, KButton.imgLeftDp(52f, 50f), 0.01f); assertEquals(2f, KButton.imgTopDp(52f, 50f), 0.01f)      // textures
    }

    @Test
    fun plainButtonHasThreePixelOutsetBorder() {
        val bm = render { }
        // left edge: 118 for 3 px, then the 239 face (WebView: 118 118 118 239)
        assertEquals(listOf(118, 118, 118, 239), (0..3).map { grey(bm, it, 37) })
        // right edge: face then 33 for 3 px
        assertEquals(listOf(239, 33, 33, 33), (71..74).map { grey(bm, it, 37) })
        assertEquals(listOf(118, 118, 118, 239), (0..3).map { grey(bm, 37, it) })
        assertEquals(listOf(239, 33, 33, 33), (71..74).map { grey(bm, 37, it) })
    }

    @Test
    fun pickedOptionFrameSitsInsideWhiteBorder() {
        val bm = render { picked = true; frameWhenPicked = true }
        // WebView: 3 px white border, 6 px black inset shadow, then the white face
        assertEquals(listOf(255, 255, 255, 0, 0, 0, 0, 0, 0, 255), (0..9).map { grey(bm, it, 37) })
        // bottom/right: dark of white
        assertEquals(listOf(255, 0, 0, 0, 0, 0, 0, 171, 171, 171), (65..74).map { grey(bm, 37, it) })
    }
}
