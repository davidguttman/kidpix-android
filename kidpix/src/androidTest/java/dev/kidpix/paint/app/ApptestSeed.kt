package dev.kidpix.paint.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * apptest helper, not a test: with `-e seedTransparent true` it saves an old-style (pre-0.3.0,
 * transparent background) picture through the app's own Pictures, so the picker can be checked by
 * hand on a device. Skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
class ApptestSeed {
    @Test
    fun seedTransparentPicture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seedTransparent") == "true")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val b = Bitmap.createBitmap(1136, 798, Bitmap.Config.ARGB_8888) // transparent paper
        Canvas(b).apply {
            drawRect(100f, 100f, 500f, 400f, Paint().apply { color = Color.RED })
            drawCircle(750f, 350f, 150f, Paint().apply { color = Color.BLUE; isAntiAlias = true })
        }
        val bytes = java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        assertTrue(Pictures(ctx).savePng(bytes))
    }
}
