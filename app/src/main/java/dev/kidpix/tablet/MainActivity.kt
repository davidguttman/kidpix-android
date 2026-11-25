package dev.kidpix.tablet

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader

class MainActivity : Activity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/pictures/", PicturesHandler(this))
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.textZoom = 100
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isHapticFeedbackEnabled = false
            isLongClickable = false
            setOnLongClickListener { true }
            if (Build.VERSION.SDK_INT >= 33) setAutoHandwritingEnabled(false)

            // Pens: deliver every sample immediately instead of batching to vsync.
            setOnTouchListener { v, ev ->
                if (Build.VERSION.SDK_INT >= 30 && ev.actionMasked == MotionEvent.ACTION_DOWN &&
                    isPen(ev)
                ) {
                    v.requestUnbufferedDispatch(ev)
                }
                watchEdgeSwipes(v, ev)
                false
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                // A runaway tool can take the renderer down; reload instead of crashing the app.
                // The drawing comes back from localStorage.
                override fun onRenderProcessGone(
                    view: WebView,
                    detail: android.webkit.RenderProcessGoneDetail,
                ): Boolean {
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                    recreate() // onDestroy() destroys the dead WebView
                    return true
                }

                // Keep kids inside the app: no external pages.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.host != "appassets.androidplatform.net"
            }
            addJavascriptInterface(Bridge(), "KidPixAndroid")
        }
        // The page lays itself out; window insets (bars, cutouts, Lenovo's window-button strip) never pad it.
        webView.setOnApplyWindowInsetsListener { _, _ -> WindowInsets.CONSUMED }
        setContentView(webView)
        hideSystemBars()
        // A swipe from an edge brings the bars back. If they don't slide away on their own (some Lenovo
        // builds leave an empty bar up, and under the kiosk lock there's nothing in it), hide them again.
        if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.setOnApplyWindowInsetsListener { v, insets ->
                val shown = insets.isVisible(WindowInsets.Type.statusBars()) ||
                    insets.isVisible(WindowInsets.Type.navigationBars()) ||
                    insets.isVisible(WindowInsets.Type.captionBar())
                v.removeCallbacks(rehide)
                if (shown) v.postDelayed(rehide, 1500)
                v.onApplyWindowInsets(insets)
            }
        }
        webView.loadUrl("https://appassets.androidplatform.net/assets/kidpix/index.html")
    }

    private val rehide = Runnable { if (!isFinishing) hideSystemBars() }
    private var edgeDownY = -1f
    private var peekShown = false
    private val dismissPeek = Runnable { dismissPeekBars() }

    /**
     * A swipe in from the top or bottom edge "peeks" the system bars over the app without telling it.
     * Most devices slide them away after a moment; the Lenovo leaves them up (an empty gray strip with
     * its window-button dots under the kiosk lock). The swipe itself reaches the page too, so watch for
     * one, then push the bars away on the next touch, or 1.5 s after the swipe if nothing else happens.
     */
    private fun watchEdgeSwipes(v: View, ev: MotionEvent) {
        val edge = 40 * resources.displayMetrics.density
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (peekShown) dismissPeekBars()
                edgeDownY = if (ev.y < edge || ev.y > v.height - edge) ev.y else -1f
            }
            MotionEvent.ACTION_MOVE -> if (edgeDownY >= 0 && Math.abs(ev.y - edgeDownY) > edge) peekShown = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                edgeDownY = -1f
                if (peekShown) { v.removeCallbacks(dismissPeek); v.postDelayed(dismissPeek, 1500) }
            }
        }
    }

    /** Showing then hiding again ends the peek at once (hiding alone doesn't; the bars are still "hidden"). */
    private fun dismissPeekBars() {
        peekShown = false
        webView.removeCallbacks(dismissPeek)
        if (Build.VERSION.SDK_INT < 30 || isFinishing) return
        window.insetsController?.let {
            it.show(WindowInsets.Type.systemBars())
            it.hide(WindowInsets.Type.systemBars())
        }
    }

    private fun isPen(ev: MotionEvent): Boolean {
        val t = ev.getToolType(ev.actionIndex)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT < 30) {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
            return
        }
        window.setDecorFitsSystemWindows(false)
        // Lenovo ZUI (One Vision "full screen window button") keeps a strip at the top that hiding the
        // system bars doesn't remove, and the window gets fitted below it, leaving a gray band. Don't fit
        // the window to any insets and lay it out over everything, the way games and video players do.
        window.attributes = window.attributes.also {
            it.fitInsetsTypes = 0
            it.fitInsetsSides = 0
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onPause() {
        webView.evaluateJavascript(
            "try{KiddoPaint.Display.saveToLocalStorage()}catch(e){}", null,
        )
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface
        fun exit() {
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun savePng(base64: String) {
            val ok = try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "kidpix-${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, PicturesHandler.SAVE_DIR)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("insert failed")
                resolver.openOutputStream(uri)!!.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "Saved to Pictures/Kid Pix" else "Couldn't save picture",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}
