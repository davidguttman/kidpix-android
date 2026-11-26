package dev.kidpix.paint.canvas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * EmojiFallback with the device's own /system/fonts (headless runs use a bundled copy): the cards
 * brush's ♠♣♥♦, the shapes brush's ❄ and the paw prints 🐾 must come out as the same glyphs as in
 * the WebView app. glyphs-webview.png was drawn by the WebView app on the Android 15 emulator
 * (canvas 600x120, '48px sans-serif', fillText at x = 20 + 95 i, y = 80), apptest piece.
 */
@RunWith(AndroidJUnit4::class)
class EmojiGlyphsDeviceTest {
    @Test
    fun cardsSnowflakePawLikeTheWebView() {
        val ref = InstrumentationRegistry.getInstrumentation().context.assets.open("glyphs-webview.png").use { BitmapFactory.decodeStream(it) }
        val c = KCanvas(600, 120)
        val ctx = c.getContext("2d")
        ctx.fillStyle = "#000"; ctx.font = "48px sans-serif"
        val s = listOf("♠", "♣", "♥", "♦", "❄", "🐾")
        s.forEachIndexed { i, g -> ctx.fillText(g, 20 + i * 95, 80) }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        File(target.filesDir, "glyphs-native.png").outputStream().use { c.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val report = StringBuilder()
        var ok = true
        for (i in s.indices) {
            val x0 = 20 + i * 95 - 10; val x1 = x0 + 95
            val a = inkBox(c.bitmap, x0, x1); val b = inkBox(ref, x0, x1)
            var diff = 0; var ink = 0
            for (y in 0 until 120) for (x in maxOf(0, x0) until minOf(600, x1)) {
                val p = c.bitmap.getPixel(x, y); val q = ref.getPixel(x, y)
                if (Color.alpha(p) > 24 || Color.alpha(q) > 24) ink++
                if (maxOf(kotlin.math.abs(Color.alpha(p) - Color.alpha(q)), kotlin.math.abs(Color.red(p) - Color.red(q)), kotlin.math.abs(Color.blue(p) - Color.blue(q))) > 48) diff++
            }
            val boxOk = a != null && b != null && (0 until 4).all { kotlin.math.abs(a[it] - b[it]) <= 1 }
            val shapeOk = diff * 100 <= ink * 6
            report.append("glyph $i ${s[i]}: native ${a?.toList()} WebView ${b?.toList()} differing $diff of $ink\n")
            ok = ok && boxOk && shapeOk
        }
        println(report)
        assertTrue(report.toString(), ok)
    }

    /** [left, top, right, bottom] of pixels with alpha > 24 between columns x0 and x1, or null. */
    private fun inkBox(b: Bitmap, x0: Int, x1: Int): IntArray? {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var bt = -1
        for (y in 0 until b.height) for (x in maxOf(0, x0) until minOf(b.width, x1)) {
            if (Color.alpha(b.getPixel(x, y)) > 24) { l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); bt = maxOf(bt, y) }
        }
        return if (r < 0) null else intArrayOf(l, t, r, bt)
    }
}
