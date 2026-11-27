package dev.kidpix.paint.app

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import dev.kidpix.paint.History
import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KCurrent
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KUi
import dev.kidpix.paint.KiddoPaint
import dev.kidpix.paint.MAIN_TOOLBAR
import dev.kidpix.paint.Ui
import dev.kidpix.paint.canvas.CssColor
import dev.kidpix.paint.canvas.KAssets
import dev.kidpix.paint.color_next
import dev.kidpix.paint.color_prev
import dev.kidpix.paint.colorSelect
import dev.kidpix.paint.engine.Engine
import dev.kidpix.paint.log.Action
import dev.kidpix.paint.log.FrameStore
import dev.kidpix.paint.log.KLog
import dev.kidpix.paint.log.Watch
import dev.kidpix.paint.log.SavePoints
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.key_down
import dev.kidpix.paint.key_up
import dev.kidpix.paint.kidpix_android_boot
import dev.kidpix.paint.submenuoption
import dev.kidpix.paint.trimAndFlattenCanvas
import java.util.concurrent.Executors

/**
 * Fullscreen landscape Kid Pix: colors and main tools on the left (the WebView build's 100 px
 * #toolbar), the drawing in the middle, the option bar (104 px, two 52 px columns) on the right.
 * Sizes are CSS px = dp. The Activity builds Views, forwards presses, and does what the WebView
 * shell (app/.../MainActivity.kt) did around the page: kiosk fullscreen, the Lenovo edge-swipe
 * fix, saving to the gallery, the picker, the door. The engine does everything else.
 */
class PaintActivity : Activity(), Ui {
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Number) = (v.toFloat() * d + 0.5f).toInt()

    lateinit var paintView: PaintView
        private set
    lateinit var root: FrameLayout
        private set
    lateinit var badge: PenBadgeView
        private set
    private lateinit var options: GridLayout
    private lateinit var current: KButton
    /** The play button (under the palette arrows) and the replay it is playing. */
    lateinit var playButton: KButton
        private set
    var watch: Watch? = null
        private set
    private val colorButtons = ArrayList<KButton>()
    val toolButtons = LinkedHashMap<String, KButton>()
    var optionButtons = ArrayList<KButton>()
        private set
    var picker: PickerView? = null
        private set
    private lateinit var pictures: Pictures
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "kidpix-io") }
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        KAssets.opener = { p -> runCatching { assets.open(p) }.getOrNull() }
        KTime.backend = appTime.clock
        KTime.origin = 0
        installSounds(this) // follows the Activity lifecycle: stops in onPause, released in onDestroy
        if (History.loader == null) {
            History.loader = Executors.newSingleThreadExecutor { r -> Thread(r, "kidpix-undo").apply { priority = Thread.MIN_PRIORITY } }
        }
        KUi.ui = this
        pictures = Pictures(this)

        paintView = PaintView(this) { w, h -> startEngine(w, h) }
        paintView.onTouchWhilePlaying = { stopWatch() }
        KTime.afterCallback = { paintView.postInvalidateOnAnimation() }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xffffffff.toInt()) }
        row.addView(buildColorBar(), LinearLayout.LayoutParams(dp(50), ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(buildMainBar(), LinearLayout.LayoutParams(dp(50), ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(paintView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        row.addView(buildOptionBar(), LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.MATCH_PARENT))
        badge = PenBadgeView(this)
        root = FrameLayout(this)
        root.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The app lays itself out; window insets (bars, cutouts, Lenovo's window-button strip) never pad it.
        root.setOnApplyWindowInsetsListener { _, _ -> WindowInsets.CONSUMED }
        // always landscape, also where the system turns the window with the tablet (LandscapeFrame)
        val frame = LandscapeFrame(this) { turned -> paintView.post { paintView.turned = turned } }
        frame.addView(root)
        setContentView(frame)
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
    }

    private fun prefs() = getSharedPreferences("kidpix", Context.MODE_PRIVATE)

    private fun startEngine(w: Int, h: Int) {
        if (Engine.started && KDisplay.isReady && KDisplay.main_canvas.width == w && KDisplay.main_canvas.height == h) {
            resyncUi() // a new Activity in the same process: keep the picture, tool and history
            return
        }
        // A new size while running (split screen, another display): checkpoint the picture, tool
        // and colour as they are now; the start below finds no checkpoint of this size and starts
        // a new segment from the current picture (as a new picture of this size).
        val carry = if (Engine.started && KDisplay.isReady) { persist(); KDisplay.main_canvas.snapshot() } else null
        val prefs = prefs()
        KLog.versionCode = versionCode()
        KLog.liveNow = { appTime.wallNow() }
        KLog.replaySounds = { SoundPoolPlayer(object : SoundBackend { override fun play(path: String) = 0; override fun stop(stream: Int) {}; override fun release() {} }) }
        KLog.requestIdleFlush = { main.removeCallbacks(idleFlush); main.postDelayed(idleFlush, 300) }
        KLog.requestRetry = { main.removeCallbacks(retryCheckpoint); main.postDelayed(retryCheckpoint, 400) }
        KLog.log = { Log.i("KidPixLog", it) }
        FrameStore.logError = { m, t -> Log.e("KidPixLog", m, t) }
        KLog.start(java.io.File(filesDir, "kplog"), w, h, logIo) {
            // First start with the action log (or a new size): the picture saved by the older
            // autosave (picture.kpx / picture.png), or the one on screen, becomes checkpoint zero.
            val legacy = carry ?: runCatching { FilePictureStore.readLegacy(filesDir) }.getOrNull()
            KDisplay.store = object : dev.kidpix.paint.PictureStore {
                override fun save(picture: android.graphics.Bitmap) {}
                override fun load() = legacy
                override fun clear() {}
            }
            KDisplay.loadFromLocalStorage() // the legacy picture, or the opening page
            KDisplay.store = null
            AppState.restore(prefs.getString(STATE_KEY, null))
        }
        appTime.arm()
        resyncUi()
        sweepSavePoints()
        Warmup.start { paintView.contactDown }
        if (intent?.getBooleanExtra("perfLog", false) == true) Log.i("KidPixPerf", "restore ${KLog.lastRestore}")
    }

    private val idleFlush = Runnable { KLog.flushIdle() }
    private val retryCheckpoint = Runnable { appTime.pump() }

    private fun versionCode(): Int = runCatching {
        packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)

    /** Puts the views back in line with the engine (after a start or a new Activity). */
    private fun resyncUi() {
        if (KUi.currentSubmenu == "alphabet") showAlphabet(KUi.alphabetLetters)
        else if (KUi.currentSubmenu.isNotEmpty()) showSubmenu(KUi.currentSubmenu)
        paletteChanged()
        currentColorChanged(KCurrent.color)
        selectionChanged()
        paintView.invalidate()
    }

    /** The action log writes everything and takes a checkpoint (if nothing is in progress). */
    private fun persist() {
        if (!KDisplay.isReady) return
        runCatching { KLog.pause() }.onFailure { Log.e("KidPix", "log pause failed", it) }
        runCatching { prefs().edit().putString(STATE_KEY, AppState.save()).apply() }
    }

    // keep the drawing if Android kills the app in the background
    override fun onPause() {
        stopWatch() // the real picture and history are back before anything is written
        persist()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        runCatching { KLog.maybeCheckpoint(force = true) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ------------------------------------------------------------------ kiosk

    private val rehide = Runnable { if (!isFinishing) hideSystemBars() }
    private var edgeDownY = -1f
    private var peekShown = false
    private val dismissPeek = Runnable { dismissPeekBars() }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        watchEdgeSwipes(ev)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * A swipe in from the top or bottom edge "peeks" the system bars over the app without telling it.
     * Most devices slide them away after a moment; the Lenovo leaves them up (an empty gray strip with
     * its window-button dots under the kiosk lock). The swipe itself reaches the app too, so watch for
     * one, then push the bars away on the next touch, or 1.5 s after the swipe if nothing else happens.
     */
    private fun watchEdgeSwipes(ev: MotionEvent) {
        val v = window.decorView
        val edge = 40 * d
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
        window.decorView.removeCallbacks(dismissPeek)
        if (Build.VERSION.SDK_INT < 30 || isFinishing) return
        window.insetsController?.let {
            it.show(WindowInsets.Type.systemBars())
            it.hide(WindowInsets.Type.systemBars())
        }
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

    // ------------------------------------------------------------------ keyboard (Lenovo keyboard covers)

    private fun jsKeyCode(k: Int): Int? = when (k) {
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> 16
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> 17
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> 18
        KeyEvent.KEYCODE_META_LEFT -> 91
        KeyEvent.KEYCODE_META_RIGHT -> 93
        KeyEvent.KEYCODE_GRAVE -> 192
        KeyEvent.KEYCODE_SPACE -> 32
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 65 + (k - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> 48 + (k - KeyEvent.KEYCODE_0)
        else -> null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val code = jsKeyCode(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (!KDisplay.isReady || picker != null) return true
        val key = if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            ('a' + (keyCode - KeyEvent.KEYCODE_A)).let { if (event.isShiftPressed) it.uppercaseChar() else it }.toString()
        } else ""
        safe("keydown") { KLog.entry(Action.Key(true, code, event.isCtrlPressed, key)) }
        paintView.invalidate()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val code = jsKeyCode(keyCode) ?: return super.onKeyUp(keyCode, event)
        if (KDisplay.isReady) safe("keyup") { KLog.entry(Action.Key(false, code, false, "")) }
        return true
    }

    // ------------------------------------------------------------------ bars

    private inline fun safe(what: String, f: () -> Unit) {
        try { f() } catch (t: Throwable) { Log.e("KidPix", "$what failed", t) }
    }

    private fun button(sizeW: Int, sizeH: Int): KButton = KButton(this).apply {
        layoutParams = ViewGroup.LayoutParams(dp(sizeW), dp(sizeH))
    }

    /** A toolbar press, unless a palm hit it while the pen draws (guardToolbarFromPalms). */
    private fun tap(b: KButton, what: String, f: () -> Unit) {
        if (KLog.playing) { stopWatch(); return } // a tap anywhere stops the replay; the button itself does nothing
        if (!KDisplay.isReady || KLog.replaying) return
        KLog.liveAdvance(appTime.wallNow()) // the palm check below reads the engine's clock
        if (KAndroid.toolbarBlocked(b.lastPointerType)) return
        val t0 = System.nanoTime()
        safe(what, f)
        if (StrokePerf.enabled) StrokePerf.button(what, System.nanoTime() - t0)
        paintView.invalidate()
    }

    private fun buildMainBar(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ids = MAIN_TOOLBAR.toMutableList().apply { add(1, "open"); add("exit") }
        for (id in ids) {
            val b = button(50, 50)
            b.bitmap = KAssets.loadBitmap(MAIN_ICONS[id] ?: continue)
            b.bitmapDp = if (id == "save") 33f else 48f // as the WebView app lays them out
            b.invertWhilePressed = id in KAndroid.ACTION_IDS
            b.setOnClickListener { tap(b, "tool $id") { KLog.entry(Action.Tool(id, false)) } }
            toolButtons[id] = b
            col.addView(b)
        }
        return ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
    }

    /** A CSS px edge as Chrome snaps it to device pixels. */
    private fun edge(cssPx: Double): Int = Math.round(cssPx * resources.displayMetrics.density).toInt()

    /**
     * Current colour (50 x 50) at the top, the 32 colours (25 x 25 each, two columns) from CSS y 51,
     * the two arrows below: at the device pixels Chrome snaps those CSS edges to (at 1.5x the
     * cells alternate 38/37 px), as in the WebView app.
     */
    private fun buildColorBar(): View {
        val bar = FrameLayout(this)
        fun place(v: View, x: Double, y: Double, w: Double, h: Double) {
            bar.addView(v, FrameLayout.LayoutParams(edge(x + w) - edge(x), edge(y + h) - edge(y)).apply {
                leftMargin = edge(x); topMargin = edge(y)
            })
        }
        current = KButton(this)
        place(current, 0.0, 0.0, 50.0, 50.0)
        for (i in 0 until 32) {
            val b = KButton(this)
            b.setOnClickListener { tap(b, "color") { KLog.entry(Action.Color(i, 1)) } }
            colorButtons.add(b)
            place(b, 25.0 * (i % 2), 51.0 + 25.0 * (i / 2), 25.0, 25.0)
        }
        val navY = 51.0 + 25.0 * 16
        place(KButton(this).apply { text = "←"; textSp = 40f / 3; textDyDp = -0.8f; setOnClickListener { tap(this, "palette") { KLog.entry(Action.Palette(false)) } } }, 0.0, navY, 25.0, 25.0)
        place(KButton(this).apply { text = "→"; textSp = 40f / 3; textDyDp = -0.8f; setOnClickListener { tap(this, "palette") { KLog.entry(Action.Palette(true)) } } }, 25.0, navY, 25.0, 25.0)
        // the play button: the picture draws itself again (the free space under the arrows)
        playButton = KButton(this).apply {
            bitmap = KAssets.loadBitmap("android/img/play.png")
            bitmapDp = 48f
            setOnClickListener { playPressed() }
        }
        place(playButton, 0.0, navY + 25.0 + PLAY_GAP, 50.0, 50.0)
        return bar
    }

    private fun buildOptionBar(): View {
        options = GridLayout(this).apply { columnCount = 2 }
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(FrameLayout(this@PaintActivity).apply { addView(options) })
        }
    }

    // ------------------------------------------------------------------ Ui (engine -> views)

    override fun showSubmenu(name: String) {
        options.removeAllViews(); optionButtons = ArrayList()
        val list = KiddoPaint.Submenu[name] ?: return
        list.forEachIndexed { i, item ->
            val b = button(52, 52)
            when {
                item.invisible -> b.invisibleSpacer = true
                item.imgSrc != null -> { b.bitmap = KAssets.loadBitmap(item.imgSrc); b.bitmapDp = 36f }
                item.imgJs != null -> { b.bitmap = runCatching { item.imgJs.invoke().sourceBitmap() }.getOrNull(); b.bitmapDp = 50f }
                item.text != null -> { b.text = item.text; b.textSp = 13f }
                item.emoji != null -> { b.text = item.emoji; b.textSp = 26f }
                item.spriteSheet != null -> {
                    // as the WebView app shows it: 52 x 52 of the sheet from the stamp's corner
                    val sheet = KAssets.loadBitmap(item.spriteSheet)
                    b.bitmap = sheet
                    val x0 = item.spriteCol * 32; val y0 = item.spriteRow * 32
                    b.src = Rect(x0, y0, minOf(sheet?.width ?: (x0 + 52), x0 + 52), minOf(sheet?.height ?: (y0 + 52), y0 + 52))
                    b.spriteWindow = true
                }
            }
            b.frameWhenPicked = true
            if (!item.invisible) {
                b.setOnClickListener { tap(b, "option $i") { KLog.entry(Action.Sub(i, false, false)) } }
            }
            optionButtons.add(b)
            options.addView(b)
        }
        selectionChanged()
    }

    override fun showAlphabet(letters: List<String>) {
        options.removeAllViews(); optionButtons = ArrayList()
        (letters + "↪").forEachIndexed { i, l ->
            // <h1> in the button: bold 40px (WebView app)
            val b = button(52, 52).apply { text = l; textSp = 40f; textBold = true; frameWhenPicked = true }
            b.setOnClickListener { tap(b, "letter $i") { KLog.entry(Action.Letter(i)) } }
            optionButtons.add(b); options.addView(b)
        }
        selectionChanged()
    }

    override fun selectionChanged() {
        for ((k, b) in toolButtons) b.picked = k in KAndroid.TOOL_IDS && k == KAndroid.currentToolId
        if (KUi.currentSubmenu == "alphabet") {
            val sel = KAndroid.selectedLetter()
            optionButtons.forEachIndexed { j, b -> b.picked = j == sel }
        } else {
            val sel = KAndroid.selectedOptions()
            optionButtons.forEachIndexed { j, b -> b.picked = j in sel }
        }
    }

    override fun paletteChanged() {
        val pal = KiddoPaint.Colors.currentPalette()
        colorButtons.forEachIndexed { i, b -> b.swatch = pal.getOrNull(i)?.let { CssColor.parse(it) } }
    }

    override fun currentColorChanged(color: String) { current.swatch = CssColor.parse(color) }

    override fun invalidate() { paintView.postInvalidateOnAnimation() }

    override fun nudge(id: String) { toolButtons[id]?.nudge() }

    override fun penBadge(toolId: String?, clientX: Double, clientY: Double) {
        if (toolId == null) { badge.hide(); return }
        val icon = MAIN_ICONS[toolId]?.let { KAssets.loadBitmap(it) }
        badge.show(icon, paintView.left + paintView.viewPx(clientX), paintView.top + paintView.viewPx(clientY))
    }

    /** Saved pictures deleted from the gallery (outside the app) free their histories. */
    private fun sweepSavePoints() {
        io.execute {
            val ids = runCatching { pictures.listOrNull() }.getOrNull() ?: return@execute
            main.post { runCatching { SavePoints.retainMedia(ids.map { it.id }.toSet()) } }
        }
    }

    // ---------- save to the Android gallery ----------
    override fun save() {
        val t0 = System.nanoTime()
        val canvasToSave = if (KCurrent.modifiedAlt) trimAndFlattenCanvas(KDisplay.main_canvas) else KDisplay.main_canvas
        val snap = canvasToSave.snapshot()
        // the picture's history goes with it (app-private, keyed to the saved file)
        val sp = runCatching { SavePoints.saving() }.onFailure { Log.e("KidPix", "save point failed", it) }.getOrNull()
        if (StrokePerf.enabled) Log.i("KidPixPerf", "save tap main thread %.1f ms".format((System.nanoTime() - t0) / 1e6))
        io.execute {
            val bytes = runCatching { Pictures.flatten(snap) }.getOrNull()
            val id = bytes?.let { runCatching { pictures.savePng(it) }.getOrNull() }
            if (sp != null) {
                if (id != null && bytes != null) runCatching { SavePoints.commitSave(sp, id, Pictures.hash(bytes)) }
                else main.post { SavePoints.discard(sp) }
            }
            val ok = id != null
            main.post {
                Toast.makeText(this, if (ok) "Saved to Pictures/Kid Pix" else "Couldn't save picture", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- open a saved picture ----------
    override fun open() {
        closePicker()
        val drafts = runCatching { SavePoints.drafts().map { it.id } }.getOrDefault(emptyList())
        val p = PickerView(this, pictures, KAssets.loadBitmap("android/img/open.png"),
            onClose = { KiddoPaint.Sounds.submenuoption(); closePicker() },
            onPick = { id -> closePicker(); loadPicture(id) },
            drafts = drafts,
            draftThumb = { id -> SavePoints.get(id)?.let { SavePoints.picture(it) }?.let { Pictures.thumbOf(it) } },
            draftBadge = KAssets.loadBitmap(MAIN_ICONS.getValue("pencil")),
            onPickDraft = { id -> closePicker(); loadDraft(id) })
        picker = p
        root.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun closePicker() {
        picker?.let { it.close(); root.removeView(it) }
        picker = null
    }

    /**
     * A gallery picture: with its history if it has a save point and the file is the one saved,
     * else flat. Read and decoded off the main thread; on the main thread the drawing on the
     * canvas is parked (if unsaved) and the picture opened.
     */
    private fun loadPicture(id: Long) {
        io.execute {
            val (bmp, hash) = runCatching { pictures.fullWithHash(id) }.getOrNull() ?: return@execute
            val sp = runCatching { SavePoints.forFile(id, hash) }.getOrNull()
            val pic = sp?.let { runCatching { SavePoints.picture(it) }.getOrNull() }
            val flat = if (sp == null || pic == null) runCatching { SavePoints.fit(bmp) }.getOrNull() ?: bmp else null
            main.post {
                openWhenQuiet("open-picture") {
                    if (sp != null && pic != null) SavePoints.open(sp, pic) else SavePoints.openFlat(flat!!)
                }
            }
        }
    }

    /** A draft (app-private): its picture and history. */
    private fun loadDraft(id: Long) {
        io.execute {
            val sp = SavePoints.get(id) ?: return@execute
            val pic = runCatching { SavePoints.picture(sp) }.getOrNull() ?: return@execute
            main.post { openWhenQuiet("open-draft") { SavePoints.open(sp, pic) } }
        }
    }

    /**
     * Runs [open] (it returns false while something is still in progress: a sound a tool waits
     * on, a timer) now or a moment later. After 1.5 s the sounds are stopped (as when the app
     * pauses); after 4 s it gives up (nothing changed: the drawing stays as it was).
     */
    private fun openWhenQuiet(what: String, tries: Int = 0, open: () -> Boolean) {
        if (!KDisplay.isReady || isFinishing) return
        KLog.liveAdvance(appTime.wallNow())
        val t0 = System.nanoTime()
        val ok = runCatching { open() }.onFailure { Log.e("KidPix", "$what failed", it) }.getOrDefault(true)
        if (ok) {
            if (StrokePerf.enabled) { StrokePerf.button(what, System.nanoTime() - t0); Log.i("KidPixPerf", "$what main thread %.1f ms".format((System.nanoTime() - t0) / 1e6)) }
            Log.i("KidPixLog", "$what after $tries retries")
            resyncUi()
            return
        }
        if (tries == 30) safe("pause sounds") { KLog.entry(Action.Pause()) }
        if (tries >= 80) { Log.w("KidPixLog", "$what: never quiet, not opened"); return }
        main.postDelayed({ openWhenQuiet(what, tries + 1, open) }, 50)
    }

    // ------------------------------------------------------------------ the play button

    /** Play: the picture draws itself from its beginning (log/Watch.kt); pressed again: stop. */
    private fun playPressed(tries: Int = 0) {
        if (!KDisplay.isReady || isFinishing || picker != null) return
        watch?.let { stopWatch(); return }
        KLog.liveAdvance(appTime.wallNow())
        val replaySounds = liveSoundBackend?.let { ReplaySoundBackend(it) }
        val t0 = System.nanoTime()
        val r = runCatching {
            Watch.start(
                schedule = { d, f -> main.postDelayed(f, d) },
                nowMs = { SystemClock.uptimeMillis() },
                sounds = replaySounds?.let { b -> { SoundPoolPlayer(b) } },
                onFrame = { paintView.postInvalidateOnAnimation() },
                onEnd = { w -> watchEnded(w, replaySounds) },
            )
        }.onFailure { Log.e("KidPix", "play failed", it) }.getOrNull()
        when (r) {
            is Watch -> {
                watch = r
                r.keepEnd = intent?.getBooleanExtra("perfLog", false) == true
                playButton.picked = true // pressed: a tap stops it
                badge.hide()
                Log.i("KidPixLog", "play: planned %d ms (engine %d ms), start %.1f ms".format(r.plannedMs, r.engineMs, (System.nanoTime() - t0) / 1e6))
            }
            Watch.Companion.No.BUSY -> if (tries < 30) main.postDelayed({ playPressed(tries + 1) }, 50) else { Log.i("KidPixLog", "play: busy, nudge"); playButton.nudge() }
            else -> { Log.i("KidPixLog", "play: nothing to replay, nudge"); playButton.nudge() } // a shake, like the Undo Guy with nothing to undo
        }
    }

    /** Stops the replay at once: the finished picture, the app exactly as it was. */
    fun stopWatch() { watch?.let { w -> val t0 = System.nanoTime(); w.stop(); Log.i("KidPixLog", "play: stopped in %.1f ms".format((System.nanoTime() - t0) / 1e6)) } }

    private fun watchEnded(w: Watch, sounds: ReplaySoundBackend?) {
        sounds?.stopAll()
        watch = null
        playButton.picked = false
        appTime.arm()
        resyncUi()
        val end = w.replayEnd
        val same = end?.let { it.sameAs(KDisplay.main_canvas.bitmap) }
        Log.i("KidPixLog", "play: ${if (w.stopped) "stopped" else "ended"} after ${w.endedAt - w.startedAt} ms (planned ${w.plannedMs}), " +
            "${w.recordsPlayed} records, pieces picture-only ${w.piecesPictureOnly}, cut ${w.piecesCut}, longest step %.1f ms".format(w.maxStepMs) +
            (if (same != null) ", replay end identical to live: $same" else "") + "; ${KLog.describe()}")
    }

    // Exit: keep the drawing, then close the app (back to the launcher / ES-DE).
    override fun exit() {
        persist()
        finish()
    }

    override fun onDestroy() {
        if (KUi.ui === this) KUi.ui = object : Ui {}
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        /** The engine clock and its pump, one per process (Activities come and go). */
        val appTime by lazy { AppTime(dev.kidpix.paint.engine.VirtualClock()) }
        /** The action log's writer thread: log blocks, frames, checkpoints, in order. */
        val logIo: java.util.concurrent.ExecutorService by lazy {
            Executors.newSingleThreadExecutor { r -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "kidpix-log") }
        }
        const val STATE_KEY = "state"
        /** CSS px between the palette arrows and the play button. */
        const val PLAY_GAP = 6.0
        val MAIN_ICONS = mapOf(
            "save" to "img/kp-m_27.png", "open" to "android/img/open.png", "pencil" to "img/kp-m_28.png",
            "line" to "img/kp-m_29.png", "square" to "img/kp-m_30.png", "circle" to "img/kp-m_31.png",
            "brush" to "img/kp-m_32.png", "jumble" to "img/kp-m_33.png", "flood" to "img/kp-m_34.png",
            "eraser" to "img/kp-m_35.png", "alphabet" to "img/kp-m_36.png", "stamp" to "img/kp-m_37.png",
            "truck" to "img/kp-m_38.png", "undo" to "img/kp-m_39.png", "exit" to "android/img/exit.png",
        )
    }
}
