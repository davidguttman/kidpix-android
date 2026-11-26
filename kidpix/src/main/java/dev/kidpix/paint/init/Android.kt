@file:JvmName("init_Android")

package dev.kidpix.paint

import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.world
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Port of overlay/android.js, the WebView build's tablet layer, minus the DOM. Same names, same
// order. What the JS did to elements (classes, the picker, the badge <div>) goes through [Ui];
// the Android views live in app/. Pointer Events become [PEvent]s built by app/PaintView.
// Layout (sizeCanvasToScreen) is PaintView.onSizeChanged; the opening page is
// Display.loadFromLocalStorage; the 30-step history is Display.saveUndo/undo/redo (History).

/**
 * A Pointer Event as Chromium on Android delivers it to the canvas. [clientX]/[clientY] are CSS px
 * from the canvas element's border-box corner (the canvas has a 1 px border). [buttons] uses
 * Chromium's bits: 1 contact, 2 BUTTON_STYLUS_PRIMARY, 4 BUTTON_STYLUS_SECONDARY, 32 eraser end.
 */
class PEvent(
    val pointerId: Int,
    val pointerType: String,
    val clientX: Double,
    val clientY: Double,
    val pressure: Double = 0.0,
    val buttons: Int = 0,
    val isPrimary: Boolean = true,
    val button: Int = 0,
    val timeStamp: Double = 0.0,
    private val coalesced: List<PEvent>? = null,
) {
    fun getCoalescedEvents(): List<PEvent> = coalesced ?: listOf(this)

    /** The samples exactly as given (null: none; the action log stores them as they are). */
    val coalescedOrNull: List<PEvent>? get() = coalesced

    fun copy(clientX: Double, clientY: Double) =
        PEvent(pointerId, pointerType, clientX, clientY, pressure, buttons, isPrimary, button, timeStamp)
}

/** The overlay's `state` object. */
class AndroidState {
    var activeId: Int? = null // pointerId of the stroke in progress
    var activeType: String? = null
    var lastPenTs = 0L
    var penHovering = false
    var swappedTool: Tool? = null // tool to restore after a side-button / eraser-tip stroke
    var undoArmed = true
    var lastXY: PEvent? = null
}

object KAndroid {
    const val PALM_GRACE_MS = 800 // ignore finger input this long after the pen was seen
    val PRESSURE_TOOLS = listOf("Pencil", "PlainBrush", "Brush", "SmoothPen", "Scribble",
        "Eraser", "EraserWhiteCircles", "Fuzzer")

    var state: AndroidState by world { AndroidState() }
    var penEraser: ToolboxEraser? by world { null }

    fun now(): Long = KTime.now()

    // ---------- event plumbing ----------
    private val isDownGetters = HashMap<Class<*>, java.lang.reflect.Method?>()

    /** `tool.isDown` (every stroke tool declares one; the base class doesn't). */
    fun toolIsDown(tool: Tool): Boolean {
        val m = isDownGetters.getOrPut(tool.javaClass) {
            tool.javaClass.methods.firstOrNull { it.name == "isDown" && it.parameterCount == 0 }
        }
        return (runCatching { m?.invoke(tool) }.getOrNull() as? Boolean) == true
    }

    /**
     * The key of `KiddoPaint.Tools` holding [tool], from the `Toolbox<Name>` class (CONVENTIONS.md).
     * The pen's own eraser is not in KiddoPaint.Tools, so it has no name (no pressure), as in the JS.
     */
    fun toolName(tool: Tool?): String {
        if (tool == null || tool === penEraser) return ""
        if (tool is NoopTool) return tool.name
        return tool.javaClass.simpleName.removePrefix("Toolbox")
    }

    fun canvasPoint(e: PEvent): Pair<Int, Int> {
        val c = KiddoPaint.Display.main_canvas
        // Whole pixels, inside the canvas: flood fill and friends index ImageData with these
        // and loop forever on fractional or out-of-range coordinates.
        var x = jsRound(e.clientX - 1).toInt()
        var y = jsRound(e.clientY - 1).toInt()
        x = max(0, min(c.width - 1, x))
        y = max(0, min(c.height - 1, y))
        return x to y
    }

    fun fakeEvent(type: String, e: PEvent): Ev {
        val (x, y) = canvasPoint(e)
        return Ev(x, y, type).apply {
            which = 1
            button = 0
            buttons = 1
            pressure = e.pressure
            pointerType = e.pointerType
            timeStamp = e.timeStamp
        }
    }

    // Pressure -> brush size, only for stroke tools, only for pens.
    fun <T> withPressure(e: PEvent, fn: () -> T): T {
        val tool = KiddoPaint.Current.tool
        val usePressure = e.pointerType == "pen" && e.pressure > 0 && toolName(tool) in PRESSURE_TOOLS
        if (!usePressure) return fn()
        val base = KiddoPaint.Current.scaling
        // 0.33 pressure ~ normal size, light = thinner, hard = up to 2x
        KiddoPaint.Current.scaling = base * max(0.35, min(2.0, 0.5 + e.pressure * 1.5))
        try {
            return fn()
        } finally {
            if (!KiddoPaint.Current.velToggle) KiddoPaint.Current.scaling = base
        }
    }

    fun send(type: String, e: PEvent) {
        val fe = fakeEvent(type, e)
        state.lastXY = e
        withPressure(e) { ev_canvas(fe) }
    }

    // Upstream erasers stamp once per event, so fast strokes leave gaps. Fill them in.
    fun fillEraserGap(e: PEvent, tool: ToolboxEraser) {
        val last = state.lastXY ?: return
        val dx = e.clientX - last.clientX
        val dy = e.clientY - last.clientY
        val dist = sqrt(dx * dx + dy * dy)
        val step = max(2.0, (if (tool.size != 0.0) tool.size else 10.0) * 0.5)
        val n = floor(dist / step).toInt()
        for (i in 1 until n) {
            val t = i.toDouble() / n
            send("mousemove", e.copy(last.clientX + dx * t, last.clientY + dy * t))
        }
    }

    fun clearOverlays() {
        KiddoPaint.Display.clearPreview()
        KiddoPaint.Display.clearAnim()
        KiddoPaint.Display.clearBnim()
    }

    // ---------- pen side buttons / eraser tip ----------
    // Chromium maps BUTTON_STYLUS_PRIMARY -> buttons&2, BUTTON_STYLUS_SECONDARY -> buttons&4,
    // the eraser end of a pen -> buttons&32.
    fun isEraserPen(e: PEvent) = e.pointerType == "pen" && ((e.buttons and 32) != 0 || (e.buttons and 2) != 0)

    fun startTempEraser() {
        if (state.swappedTool != null) return
        if (penEraser == null) {
            penEraser = ToolboxEraser().also {
                it.size = 14.0
                it.isSquareEraser = false
            }
        }
        state.swappedTool = KiddoPaint.Current.tool
        KiddoPaint.Current.tool = penEraser!!
    }

    fun endTempEraser() {
        val t = state.swappedTool ?: return
        KiddoPaint.Current.tool = t
        state.swappedTool = null
    }

    fun penUndoButton(e: PEvent) {
        if (e.pointerType != "pen") return
        val pressed = (e.buttons and 4) != 0
        if (pressed && state.undoArmed) {
            state.undoArmed = false
            undoGuyTap()
        } else if (!pressed) {
            state.undoArmed = true
        }
    }

    // ---------- listeners ----------
    fun palmBlocked(e: PEvent): Boolean {
        if (e.pointerType != "touch") return false
        if (state.activeType == "pen") return true
        // hovering pens report moves continuously, so this also covers "pen near the screen"
        return now() - state.lastPenTs < PALM_GRACE_MS
    }

    fun endStroke(e: PEvent) {
        state.activeId = null
        state.activeType = null
        send("mouseup", e)
        endTempEraser()
    }

    fun pointerdown(e: PEvent) {
        if (e.pointerType == "pen") {
            state.lastPenTs = now()
            // pen beats finger: end any finger stroke (likely a palm) first
            if (state.activeId != null && state.activeType != "pen") {
                endStroke(e)
            }
            penUndoButton(e)
        }
        badgeHide()
        if (palmBlocked(e)) return
        // A primary pointer going down means every earlier contact lifted, so a stroke
        // still marked active missed its pointerup. Close it rather than freeze drawing.
        if (state.activeId != null && (e.isPrimary || e.pointerType == "pen")) {
            endStroke(e)
        }
        if (state.activeId != null) return // second finger: one stroke at a time
        if (e.pointerType == "mouse" && e.button != 0) return

        state.activeId = e.pointerId
        state.activeType = e.pointerType
        if (isEraserPen(e)) startTempEraser()
        send("mousedown", e)
    }

    fun pointermove(e: PEvent) {
        input(e)
        badgeMove(e)
    }

    private fun input(e: PEvent) {
        if (e.pointerType == "pen") {
            state.lastPenTs = now()
            penUndoButton(e)
        }
        if (state.activeId == e.pointerId) {
            // Pencil follows every sample the pen reports for smooth lines;
            // other tools keep one dab per frame like the web version.
            val tool = KiddoPaint.Current.tool
            val isEraser = tool === penEraser || tool === KiddoPaint.Tools.Eraser
            var list = listOf(e)
            if (e.pointerType == "pen" && (tool === KiddoPaint.Tools.Pencil || isEraser)) {
                val c = e.getCoalescedEvents()
                if (c.isNotEmpty()) list = c
            }
            for (p in list) {
                if (isEraser) fillEraserGap(p, tool as ToolboxEraser)
                send("mousemove", p)
            }
            return
        }
        // pen hovering while we think it's touching: the pointerup was lost
        if (state.activeType == "pen" && e.pointerType == "pen" && e.buttons == 0 && state.activeId != null) {
            endStroke(e)
        }
        // hovering pen (or mouse) with nothing pressed: show tool previews like a mouse cursor
        if (state.activeId == null && (e.pointerType == "pen" || e.pointerType == "mouse") && e.buttons == 0) {
            if (e.pointerType == "pen") state.penHovering = true
            send("mousemove", e)
        }
    }

    /** pointerup (and, through [pointercancel], pointercancel / lostpointercapture). */
    fun pointerup(e: PEvent) {
        if (e.pointerType == "pen") {
            state.lastPenTs = now()
            penUndoButton(e)
        }
        if (state.activeId != e.pointerId) return
        endStroke(e)
    }

    fun pointercancel(e: PEvent) {
        badgeHide()
        pointerup(e)
    }

    /** pointerleave / pointerout. */
    fun pointerleave(e: PEvent) {
        badgeHide()
        if (e.pointerType == "pen") {
            state.penHovering = false
            state.lastPenTs = now()
        }
        if (state.activeId != null) return // captured stroke continues
        if (toolIsDown(KiddoPaint.Current.tool)) return
        clearOverlays()
    }

    /** Mouse wheel over the canvas: upstream's mouse_wheel. */
    fun wheel(delta: Double) = mouse_wheel(delta)

    // Pens tap the toolbar too: make sure finger palms don't trigger tool switches while drawing.
    fun toolbarBlocked(pointerType: String): Boolean =
        state.activeType == "pen" && pointerType != "pen" && now() - state.lastPenTs < 1000

    // ---------- open a saved picture ----------
    // The app lists Pictures/Kid Pix (app/Pictures.kt) in its picker (app/PickerView.kt).
    // Opening is undoable.
    fun loadPicture(img: android.graphics.Bitmap) {
        val c = KiddoPaint.Display.main_canvas
        val ctx = KiddoPaint.Display.main_context
        KiddoPaint.Display.saveUndo()
        KiddoPaint.Display.clearMain()
        if (img.width == c.width && img.height == c.height) {
            ctx.drawImage(img, 0, 0)
        } else {
            val s = min(c.width.toDouble() / img.width, c.height.toDouble() / img.height)
            val w = img.width * s
            val h = img.height * s
            ctx.imageSmoothingEnabled = true
            ctx.drawImage(img, (c.width - w) / 2, (c.height - h) / 2, w, h)
            ctx.imageSmoothingEnabled = false
        }
        KiddoPaint.Display.saveToLocalStorage()
        KiddoPaint.Sounds.stamp()
        KUi.ui.invalidate()
    }

    // ---------- multi-step undo / redo ----------
    // Display.saveUndo/undo/redo keep the 30-step history (util/Display.kt, History).
    // Tap the Undo Guy = back one step, double-tap = forward one step.
    const val DOUBLE_TAP_MS = 350
    var undoTapTimer: Int? by world { null }

    /** The first tap of a possible double tap did undo a step (the second takes it back). */
    var undoTapUndid: Boolean by world { false }

    /**
     * One tap: back one step, at once. A second tap within [DOUBLE_TAP_MS] makes it a double tap:
     * forward one step from where the first tap started (its undo is taken back first). The
     * WebView build waited the double-tap time before undoing; the end states are the same.
     */
    fun undoGuyTap() {
        val t = undoTapTimer
        val keep = !KiddoPaint.Current.modifiedAlt
        if (t != null) { // second tap: forward
            KTime.clearTimeout(t)
            undoTapTimer = null
            KiddoPaint.Sounds.mainmenu()
            if (undoTapUndid) KiddoPaint.Display.redo(keep)
            undoTapUndid = false
            if (KiddoPaint.Display.redo(keep)) {
                KiddoPaint.Sounds.submenuoption()
            } else {
                KUi.ui.nudge("undo")
            }
            KUi.ui.invalidate()
            return
        }
        // single tap: back
        KiddoPaint.Sounds.mainmenu()
        val undid = KiddoPaint.Display.undo(keep)
        undoTapUndid = undid
        if (undid) KiddoPaint.Sounds.oops()
        // nothing to undo: nudge once it is clear this was not the first tap of a double tap
        undoTapTimer = KTime.setTimeout({
            undoTapTimer = null
            undoTapUndid = false
            if (!undid) KUi.ui.nudge("undo")
        }, DOUBLE_TAP_MS)
        KUi.ui.invalidate()
    }

    // ---------- show which tool is selected ----------
    val TOOL_IDS = listOf("pencil", "line", "square", "circle", "brush", "jumble", "flood",
        "eraser", "alphabet", "stamp", "truck")
    val ACTION_IDS = listOf("save", "open", "undo", "exit")
    /** The last generic submenu shown since boot (null until the first one, as in the JS). */
    var currentSubmenu: String? by world { null }
    /** submenu name -> {groupIndex: buttonIndex}; "__alphabet" for the letters. */
    var subSelection: MutableMap<String?, MutableMap<Int, Int>> by world { HashMap() }
    var currentToolId: String by world { "pencil" }
    /** The name the generic option bar was last painted with (paintSubSelection). */
    var barName: String? by world { null }

    fun markMainTool(id: String) {
        currentToolId = id
        KUi.ui.selectionChanged()
    }

    // Spacer buttons split a submenu into independent choices (e.g. pencil size vs texture).
    fun submenuGroups(items: List<SubmenuItem>): List<List<Int>> {
        val groups = mutableListOf(mutableListOf<Int>())
        items.forEachIndexed { i, b ->
            if (b.invisible) groups.add(mutableListOf()) else groups.last().add(i)
        }
        return groups.filter { it.isNotEmpty() }
    }

    fun paintSubSelection(name: String?) {
        barName = name
        KUi.ui.selectionChanged()
    }

    /** Indices into the generic submenu showing now that are lit. */
    fun selectedOptions(): Set<Int> {
        val items = KiddoPaint.Submenu[KUi.currentSubmenu] ?: return emptySet()
        val sel = subSelection[barName] ?: return emptySet()
        val out = HashSet<Int>()
        submenuGroups(items).forEachIndexed { gi, g -> sel[gi]?.let { bi -> g.getOrNull(bi)?.let { out.add(it) } } }
        return out
    }

    /** Index into the alphabet bar (letters, then the next-page arrow) that is lit, or -1. */
    fun selectedLetter(): Int = subSelection["__alphabet"]?.get(0) ?: -1

    // Which handlers the JS inspected by source text (show_generic_submenu / init_sprites_submenu
    // flip the page; Sounds.unimpl without Current.tool does nothing). Read from upstream submenus.
    val PAGE_FLIP_OPTIONS = mapOf(
        "sprites" to setOf("Next Page", "Next Stamp Pack"),
        "wackybrush" to setOf("Spray Paint"), // opens the spray sizes on ctrl-click
    )
    val UNIMPL_OPTIONS = mapOf(
        "wackybrush" to setOf("Caterpillars", "Roll The Dice", "Drippy Paint", "Alphabet Line"),
        "jumble" to setOf("The Outliner", "Zoom In", "Broken Glass", "The Highlighter", "Wrap Around"),
        "truck" to setOf("Truck 2"),
    )
    /** Sounds.unimpl after setting Current.tool: a pick, but never re-applied. */
    val UNIMPL_TOOL_OPTIONS = mapOf(
        "eraser" to setOf("Drop Out", "Black Hole", "Count Down", "Sweep"),
    )

    private fun isPageFlip(name: String?, d: SubmenuItem?) = d != null && PAGE_FLIP_OPTIONS[name]?.contains(d.name) == true
    private fun isUnimpl(name: String?, d: SubmenuItem?) = d != null &&
        (UNIMPL_OPTIONS[name]?.contains(d.name) == true || UNIMPL_TOOL_OPTIONS[name]?.contains(d.name) == true)

    // Buttons that act (flip the stamp page) instead of choosing, and ones upstream never built.
    fun isActionOption(name: String?, d: SubmenuItem?): Boolean =
        isPageFlip(name, d) || (d != null && UNIMPL_OPTIONS[name]?.contains(d.name) == true)

    // Upstream's main tool buttons reset to a default (eraser -> plain eraser, brush -> first brush,
    // stamp -> first stamp) while the highlight remembered the earlier pick, so the lit option lied.
    // Re-apply the remembered pick instead: coming back to a tool gets back what you chose there.
    fun restoreSubSelection(id: String) {
        val name = currentSubmenu
        if (KUi.currentSubmenu == "alphabet" || KUi.currentSubmenu.isEmpty() || name == null) return
        if (id == "stamp" && name == "sprites") { // stamps change with the page; upstream picks the first
            subSelection["sprites"] = mutableMapOf(0 to 0)
        } else {
            val items: List<SubmenuItem> = KiddoPaint.Submenu[name] ?: emptyList()
            val sel = subSelection[name] ?: emptyMap()
            submenuGroups(items).forEachIndexed { gi, g ->
                val bi = sel[gi] ?: return@forEachIndexed
                val idx = g.getOrNull(bi) ?: return@forEachIndexed
                val d = items[idx]
                if (d.handler != null && !isActionOption(name, d) && !isUnimpl(name, d)) {
                    d.handler(Ev(type = "click"))
                }
            }
        }
        paintSubSelection(name)
    }

    /** The show_generic_submenu wrapper (called from it, before the bar is rebuilt). */
    fun onShowGenericSubmenu(name: String) {
        currentSubmenu = name
        // upstream picks the first option itself for these
        if (subSelection[name] == null && (name == "wackybrush" || name == "sprites")) {
            subSelection[name] = mutableMapOf(0 to 0)
        }
        barName = name
    }

    /** The capture-phase click listener of the option bar, before the button's own handler. */
    fun optionClicked(index: Int) {
        val name = currentSubmenu
        val bar = KiddoPaint.Submenu[KUi.currentSubmenu] ?: return
        val b = bar.getOrNull(index) ?: return
        if (b.invisible) return
        val items = name?.let { KiddoPaint.Submenu[it] }
        if (items != null) {
            val d = items.getOrNull(index)
            if (isActionOption(name, d)) {
                // a new stamp page doesn't contain the chosen stamp, so light nothing
                if (isPageFlip(name, d)) subSelection[name] = HashMap()
                return
            }
        }
        val groups = submenuGroups(bar)
        for (gi in groups.indices) {
            val bi = groups[gi].indexOf(index)
            if (bi != -1) subSelection.getOrPut(name) { HashMap() }[gi] = bi
        }
        paintSubSelection(name)
    }

    /** Letters: the click listener on #alphabettoolbar (the arrow is a button there too). */
    fun letterClicked(index: Int) {
        subSelection.getOrPut("__alphabet") { HashMap() }[0] = index
        KUi.ui.selectionChanged()
    }

    // ---------- the tool riding next to a hovering pen ----------
    // On a desktop the mouse pointer turns into the tool; a tablet has no pointer,
    // so show the tool's icon just up-left of the pen tip while it hovers.
    const val BADGE_DX = -50.0
    const val BADGE_DY = -50.0

    fun iconFor(e: PEvent): String =
        if ((e.buttons and 34) != 0) "eraser" else currentToolId // side button or eraser end: temporary eraser

    private fun badgeMove(e: PEvent) {
        if (e.pointerType != "pen" || (e.buttons and 1) != 0 || state.activeId != null) return badgeHide()
        KUi.ui.penBadge(iconFor(e), e.clientX + BADGE_DX, e.clientY + BADGE_DY)
    }

    fun badgeHide() = KUi.ui.penBadge(null, 0.0, 0.0)

    // ---------- toolbar presses, as a tap runs both upstream's and the overlay's listeners ----------
    fun pressMain(id: String, ctrl: Boolean = false): Boolean {
        if (id == "undo") { // installUndoGuy: upstream's single-step listener is gone
            undoGuyTap()
            return true
        }
        val ok = press_main_tool(id, ctrl)
        if (id in TOOL_IDS) { // runs after upstream's own handler
            markMainTool(id)
            restoreSubSelection(id)
        }
        return ok
    }

    fun pressSubmenu(index: Int, contextmenu: Boolean = false, ctrl: Boolean = false): Boolean {
        if (!contextmenu) optionClicked(index)
        return press_submenu(index, contextmenu, ctrl)
    }

    fun pressAlphabet(index: Int) {
        press_alphabet(index)
        letterClicked(index)
    }
}

/**
 * kidpix_android_boot after init_kiddo_paint: the overlay's state on a fresh page. Pencil starts
 * on the middle size and solid ink, both shown selected.
 */
fun kidpix_android_boot() {
    KAndroid.state = AndroidState()
    KAndroid.penEraser = null
    KAndroid.undoTapTimer = null
    KAndroid.currentSubmenu = null // init_pencil_subtoolbar ran before the wrapper existed
    KAndroid.subSelection = HashMap()
    KAndroid.currentToolId = "pencil"
    KiddoPaint.Tools.Pencil.size = 9.0
    KAndroid.subSelection["pencil"] = mutableMapOf(0 to 2, 1 to 0)
    KAndroid.paintSubSelection("pencil")
}
