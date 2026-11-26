package dev.kidpix.glfx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/**
 * Every glfx filter, native vs the real glfx.js in a WebView on the same device, on the committed
 * test picture (1136x798, colourful, with transparency). Writes native/ref/diff PNGs and
 * results.tsv to the additional test output dir (pulled into glfx/build/renders/).
 */
@RunWith(AndroidJUnit4::class)
class FilterComparisonTest {

    /**
     * One comparison. [args] are formatted into the JS call; the native call uses the method with
     * the same name and arity (all-double) unless [native] is given. Limits: mean channel distance
     * (0-255, premultiplied RGBA) and share of pixels whose worst channel is off by more than 16.
     */
    class Case(
        val id: String, val name: String, val args: List<Double>, val crop: IntArray? = null,
        val js: String = "$name(${args.joinToString(",") { fmt(it) }})",
        val maxMean: Double = 0.1, val maxBadPct: Double = 0.01,
        val native: ((FxCanvas) -> FxCanvas)? = null,
    )

    companion object {
        const val TAG = "GlfxCompare"
        const val CX = 568.0
        const val CY = 399.0
        const val X2 = 300.0
        const val Y2 = 250.0
        fun fmt(d: Double): String = if (d == Math.floor(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()
        fun c(id: String, name: String, vararg a: Double, mean: Double = 0.1, bad: Double = 0.01) =
            Case(id, name, a.toList(), maxMean = mean, maxBadPct = bad)

        // wholefx.js parameters: drawDistance d from the first click (CX, CY) or (X2, Y2).
        val CASES = listOf(
            c("bulgePinch_pinch", "bulgePinch", CX, CY, 200.0, -0.6),     // remap(0,500,-1,1,d=100)
            c("bulgePinch_bulge", "bulgePinch", X2, Y2, 200.0, 0.6),      // d=400
            c("bulgePinch_full", "bulgePinch", CX, CY, 200.0, 1.0),       // d>=500
            c("swirl_half", "swirl", CX, CY, 150.0, PI),                  // vert 150 -> pi
            c("swirl_back", "swirl", X2, Y2, 300.0, -2 * PI),             // vert -300 -> -2pi
            c("lensBlur_10", "lensBlur", 10.0, 0.88, 0.70841),   // d=100
            c("lensBlur_30", "lensBlur", 30.0, 0.88, 0.70841),   // d=300
            c("triangleBlur_10", "triangleBlur", 10.0),          // d=50
            c("triangleBlur_40", "triangleBlur", 40.0),          // d=200
            c("zoomBlur_03", "zoomBlur", CX, CY, 0.3),           // remap(0,250,0,1,d=75)
            c("zoomBlur_08", "zoomBlur", X2, Y2, 0.8),           // d=200
            c("hexagonalPixelate_5", "hexagonalPixelate", CX, CY, 5.0),   // d/10
            c("hexagonalPixelate_20", "hexagonalPixelate", X2, Y2, 20.0),
            c("ink_neg", "ink", -0.5, mean = 0.2, bad = 0.05),                                    // remap(0,250,-1,1,d=62)
            c("ink_025", "ink", 0.25, mean = 0.2, bad = 0.05),
            c("ink_06", "ink", 0.6, mean = 0.2, bad = 0.05),
            c("hue_neg", "hueSaturation", -0.6, 0.0),                     // remap(0,1000,-1,1,d=200)
            c("hue_pos", "hueSaturation", 0.4, 0.0),
            c("sat_neg", "hueSaturation", 0.0, -0.6),                     // remap(0,500,-1,1,d=100)
            c("sat_pos", "hueSaturation", 0.0, 0.6),
            c("edgeWork_5", "edgeWork", 5.0, mean = 1.5, bad = 1.0),      // d/10
            c("edgeWork_20", "edgeWork", 20.0, mean = 1.5, bad = 1.0),
            c("pancake", "brightnessContrast", 0.0, 0.0),                 // pancake + pixelate pass-through
            // partialfx.js: edgeWork(size/2) on a 72x72 getImageData crop around the pen.
            Case("partial_edgeWork", "edgeWork", listOf(18.0), crop = intArrayOf(CX.toInt() - 36, CY.toInt() - 36 - 150, 72, 72), maxMean = 0.1, maxBadPct = 0.01),
            Case("partial_edgeWork_edge", "edgeWork", listOf(18.0), crop = intArrayOf(1100, 400, 72, 72), maxMean = 0.1, maxBadPct = 0.01),
            // The rest of glfx.js.
            c("brightnessContrast", "brightnessContrast", 0.2, 0.4),
            c("brightnessContrast_neg", "brightnessContrast", -0.3, -0.5, mean = 0.3),
            c("denoise", "denoise", 20.0),
            c("noise", "noise", 0.5, mean = 0.3),
            c("sepia", "sepia", 1.0),
            c("unsharpMask", "unsharpMask", 20.0, 2.0),
            c("vibrance", "vibrance", 0.7),
            c("vignette", "vignette", 0.5, 0.6),
            c("tiltShift", "tiltShift", 0.0, 400.0, 1136.0, 380.0, 15.0, 200.0),
            c("colorHalftone", "colorHalftone", CX, CY, 0.25, 4.0, mean = 0.3),
            c("dotScreen", "dotScreen", CX, CY, 1.1, 3.0, mean = 0.5),
            Case("curves", "curves", emptyList(), js = "curves([[0,0],[0.25,0.15],[0.5,0.6],[1,1]],[[0,0.1],[1,0.9]],[[0,0],[0.5,0.3],[1,1]])",
                native = { it.curves(listOf(d(0.0, 0.0), d(0.25, 0.15), d(0.5, 0.6), d(1.0, 1.0)), listOf(d(0.0, 0.1), d(1.0, 0.9)), listOf(d(0.0, 0.0), d(0.5, 0.3), d(1.0, 1.0))) }),
            Case("curves_single", "curves", emptyList(), js = "curves([[0,1],[0.5,0.3],[1,0]])",
                native = { it.curves(listOf(d(0.0, 1.0), d(0.5, 0.3), d(1.0, 0.0))) }),
            Case("matrixWarp", "matrixWarp", emptyList(), js = "matrixWarp([[1,0.2],[-0.1,0.9]],false,true)",
                native = { it.matrixWarp(arrayOf(d(1.0, 0.2), d(-0.1, 0.9)), false, true) }),
            Case("matrixWarp_inv3", "matrixWarp", emptyList(), js = "matrixWarp([0.9,0.1,30,0.05,1.1,-20,0,0.0002,1],true,false)",
                native = { it.matrixWarp(doubleArrayOf(0.9, 0.1, 30.0, 0.05, 1.1, -20.0, 0.0, 0.0002, 1.0), true, false) }),
            Case("perspective", "perspective", emptyList(), js = "perspective([175,156,496,55,161,279,504,330],[175,156,496,55,121,329,524,290])",
                native = { it.perspective(doubleArrayOf(175.0, 156.0, 496.0, 55.0, 161.0, 279.0, 504.0, 330.0), doubleArrayOf(175.0, 156.0, 496.0, 55.0, 121.0, 329.0, 524.0, 290.0)) }),
        )

        /** The cases with wholefx.js / partialfx.js parameters. */
        val KIDPIX_IDS = setOf("bulgePinch_pinch", "bulgePinch_bulge", "bulgePinch_full", "swirl_half", "swirl_back", "lensBlur_10", "lensBlur_30",
            "triangleBlur_10", "triangleBlur_40", "zoomBlur_03", "zoomBlur_08", "hexagonalPixelate_5", "hexagonalPixelate_20", "ink_neg", "ink_025",
            "ink_06", "hue_neg", "hue_pos", "sat_neg", "sat_pos", "edgeWork_5", "edgeWork_20", "pancake", "partial_edgeWork", "partial_edgeWork_edge")

        private fun d(x: Double, y: Double) = doubleArrayOf(x, y)
    }

    data class Stats(val mean: Double, val max: Int, val badPct: Double)

    @Test
    fun everyFilterMatchesGlfxJs() = compareAll(CASES, allowFloat = true, prefix = "")

    /**
     * The 8-bit fallback (GPUs without float render targets) for the filters Kid Pix uses, against
     * glfx.js on its own 8-bit path (OES_texture_float hidden from it).
     */
    @Test
    fun byteFallbackMatchesGlfxJsByte() = compareAll(CASES.filter { it.id in KIDPIX_IDS }, allowFloat = false, prefix = "byte_")

    private fun compareAll(cases: List<Case>, allowFloat: Boolean, prefix: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val outDir = outputDir()
        val pic = ctx.assets.open("testpic.png").use { BitmapFactory.decodeStream(it) }
        val gfx = Fx.canvas().also { it.allowFloat = allowFloat }
        val failures = ArrayList<String>()
        val rows = ArrayList<String>()
        GlfxJsReference("testpic.png", noFloat = !allowFloat).use { ref ->
            Log.i(TAG, "WebView: ${ref.renderer}; native: ${gfx.glVersion}")
            File(outDir, "${prefix}env.txt").writeText("webview: ${ref.renderer}\nnative: ${gfx.glVersion}\n")
            for (case in cases) {
                val tex = if (case.crop == null) gfx.texture(pic) else gfx.texture(cropRgba(pic, case.crop), case.crop[2], case.crop[3])
                val chain: (FxCanvas) -> FxCanvas = case.native ?: reflective(case)
                // timing: draw + filter + update, then glFinish; readback separately
                val iters = if (case.crop == null) 8 else 30
                chain(gfx.draw(tex)).update(); GLES20.glFinish()
                var t0 = System.nanoTime()
                repeat(iters) { chain(gfx.draw(tex)).update() }
                GLES20.glFinish()
                val ms = (System.nanoTime() - t0) / 1e6 / iters
                var out = chain(gfx.draw(tex)).update().toBitmap()
                t0 = System.nanoTime()
                repeat(iters) { out = gfx.toBitmap(out) }
                val readMs = (System.nanoTime() - t0) / 1e6 / iters
                val nativeFloat = gfx.usesFloatTextures
                tex.destroy()

                val r = ref.run(case.id, case.js, case.crop)
                val st = compare(out, r.bitmap, File(outDir, "$prefix${case.id}.diff.png"))
                save(out, File(outDir, "$prefix${case.id}.native.png"))
                save(r.bitmap, File(outDir, "$prefix${case.id}.ref.png"))
                val ok = st.mean <= case.maxMean && st.badPct <= case.maxBadPct
                val row = listOf(case.id, case.js, "%.3f".format(st.mean), st.max, "%.3f".format(st.badPct),
                    "%.2f".format(ms), "%.2f".format(readMs), "%.2f".format(r.ms), nativeFloat, r.floatTextures, if (ok) "ok" else "FAIL").joinToString("\t")
                rows += row
                Log.i(TAG, row)
                if (!ok) failures += "${case.id}: mean ${st.mean} (limit ${case.maxMean}), >16 ${st.badPct}% (limit ${case.maxBadPct}%), max ${st.max}"
            }
        }
        gfx.destroy()
        File(outDir, "${prefix}results.tsv").writeText(
            "id\tjs\tmean\tmax\tpct>16\tnative_ms\treadback_ms\tjs_ms\tnative_float\tjs_float\tresult\n" + rows.joinToString("\n") + "\n")
        assertTrue("Filters off from glfx.js:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun reflective(case: Case): (FxCanvas) -> FxCanvas {
        val m = FxCanvas::class.java.methods.first { m ->
            m.name == case.name && m.parameterTypes.size == case.args.size && m.parameterTypes.all { it == java.lang.Double.TYPE }
        }
        val args = case.args.map { it as Any }.toTypedArray()
        return { g -> m.invoke(g, *args) as FxCanvas }
    }

    /** `getImageData(x, y, w, h)`: straight RGBA bytes, transparent outside the picture. */
    private fun cropRgba(b: Bitmap, crop: IntArray): ByteArray {
        val (x0, y0, w, h) = crop.toList()
        val out = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = x0 + x
            val sy = y0 + y
            if (sx < 0 || sy < 0 || sx >= b.width || sy >= b.height) continue
            val p = b.getPixel(sx, sy)
            val i = (y * w + x) * 4
            out[i] = (p shr 16).toByte(); out[i + 1] = (p shr 8).toByte(); out[i + 2] = p.toByte(); out[i + 3] = (p ushr 24).toByte()
        }
        return out
    }

    private fun premul(b: Bitmap): ByteArray {
        val buf = ByteBuffer.allocate(b.byteCount)
        b.copyPixelsToBuffer(buf)
        return buf.array()
    }

    private fun compare(a: Bitmap, b: Bitmap, diffFile: File): Stats {
        check(a.width == b.width && a.height == b.height) { "size ${a.width}x${a.height} vs ${b.width}x${b.height}" }
        val pa = premul(a)
        val pb = premul(b)
        var sum = 0L
        var mx = 0
        var bad = 0
        val diff = IntArray(a.width * a.height)
        for (i in diff.indices) {
            var worst = 0
            for (ch in 0 until 4) {
                val d = abs((pa[i * 4 + ch].toInt() and 0xFF) - (pb[i * 4 + ch].toInt() and 0xFF))
                sum += d
                worst = max(worst, d)
            }
            mx = max(mx, worst)
            if (worst > 16) bad++
            val v = (worst * 4).coerceAtMost(255)
            diff[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        save(Bitmap.createBitmap(diff, a.width, a.height, Bitmap.Config.ARGB_8888), diffFile)
        return Stats(sum.toDouble() / (diff.size * 4), mx, 100.0 * bad / diff.size)
    }

    private fun save(b: Bitmap, f: File) = f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun outputDir(): File {
        val arg = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val dir = if (arg != null) File(arg, "renders") else File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "renders")
        dir.mkdirs()
        return dir
    }
}
