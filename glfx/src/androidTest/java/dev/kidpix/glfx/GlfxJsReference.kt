package dev.kidpix.glfx

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Runs the real glfx.js (test asset copy of upstream/js/util/glfx.js) in a WebView on the device. */
class GlfxJsReference(pictureAsset: String, private val noFloat: Boolean = false) : AutoCloseable {
    data class Result(val bitmap: Bitmap, val ms: Double, val floatTextures: Boolean)

    private class Msg(val kind: String, val id: String, val payload: String)

    private val queue = LinkedBlockingQueue<Msg>()
    private val scenario: ActivityScenario<RefActivity> = ActivityScenario.launch(RefActivity::class.java)
    private lateinit var web: WebView
    val renderer: String

    init {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val glfx = assets.open("glfx.js").bufferedReader().readText()
        val html = assets.open("ref.html").bufferedReader().readText().replace("/*GLFX*/", glfx)
        val bmp = assets.open(pictureAsset).use { BitmapFactory.decodeStream(it) }
        val pic = "'" + Base64.encodeToString(straightRgba(bmp), Base64.NO_WRAP) + "', ${bmp.width}, ${bmp.height}"
        scenario.onActivity { activity -> setUp(activity, html, pic) }
        val ready = take("ready", "", 60)
        renderer = ready.payload
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUp(activity: RefActivity, html: String, pic: String) {
        web = WebView(activity)
        activity.setContentView(web)
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun result(kind: String, id: String, payload: String) { queue.put(Msg(kind, id, payload)) }
        }, "Ref")
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                android.util.Log.i("GlfxRef", "${m.message()} @${m.lineNumber()}")
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript("loadPicture($pic, $noFloat)", null)
            }
        }
        web.loadDataWithBaseURL("https://glfx.test/", html, "text/html", "utf-8", null)
    }

    private fun take(kind: String, id: String, seconds: Long): Msg {
        while (true) {
            val m = queue.poll(seconds, TimeUnit.SECONDS) ?: throw AssertionError("glfx.js: no '$kind' for '$id' in ${seconds}s")
            if (m.kind == "error") throw AssertionError("glfx.js error ($id): ${m.payload}")
            if (m.kind == kind && m.id == id) return m
        }
    }

    fun probe(): String {
        scenario.onActivity { web.evaluateJavascript("probe()", null) }
        return take("probe", "", 30).payload
    }

    /** Runs `gfx.draw(tex).<chain>.update()` on the picture (or a getImageData crop of it). */
    fun run(id: String, chain: String, crop: IntArray? = null, iters: Int = 5): Result {
        val cropJs = crop?.joinToString(",", "[", "]") ?: "null"
        val js = "runCase('$id', $cropJs, '${chain.replace("'", "\\'")}', $iters)"
        scenario.onActivity { web.evaluateJavascript(js, null) }
        val m = take("done", id, 180)
        val (ms, fl, url) = m.payload.split('|', limit = 3)
        val bytes = Base64.decode(url.substringAfter(","), Base64.DEFAULT)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        return Result(bmp, ms.toDouble(), fl.toBoolean())
    }

    override fun close() = scenario.close()

    companion object {
        /** Straight (non-premultiplied) RGBA bytes, top row first, like ImageData. */
        fun straightRgba(b: Bitmap): ByteArray {
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val out = ByteArray(px.size * 4)
            for (i in px.indices) {
                val p = px[i]
                out[i * 4] = (p shr 16).toByte(); out[i * 4 + 1] = (p shr 8).toByte(); out[i * 4 + 2] = p.toByte(); out[i * 4 + 3] = (p ushr 24).toByte()
            }
            return out
        }
    }
}
