@file:JvmName("init_Kiddopaint")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.engine.KRandom
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.World
import kotlin.math.max
import kotlin.math.min

// Port of js/init/kiddopaint.js. The DOM parts (buttons, divs) became the [Ui] callbacks the
// Android layer implements; the logic stays here so it runs headless.

/** The KiddoPaint namespace. Members live in their own files (see CONVENTIONS.md). */
object KiddoPaint {
    /** Tools attach as `val KiddoPaint.Tools.X by world { ToolboxX() }` in tools/X.kt. */
    object Tools { object Toolbox }
    /** Textures attach as `fun KiddoPaint.Textures.X(...)` in textures/Textures.kt. */
    object Textures
    object Brushes
    object Builders
    /** Sound triggers: `fun KiddoPaint.Sounds.pencil()` in sounds/Sounds.kt. */
    object Sounds

    val Display get() = KDisplay
    val Colors get() = KColors
    val Current get() = KCurrent
    val Cache get() = KCache
    val Stamps get() = KStamps
    val Alphabet get() = KAlphabet
    val Sprite get() = KSprite
    /** Submenu lists attach as `var KiddoPaint.Submenu.x by world { ... }` in submenus/X.kt. */
    object Submenu {
        operator fun get(name: String): MutableList<SubmenuItem>? = submenuByName(name)
    }

    /** A fresh page load: new tool instances, default palette/caches, no pending timers. */
    fun resetWorld() {
        World.reset()
        KTime.reset()
        KColors.Current.PaletteNumber = 0
        KColors.Current.colorStep = 0
        KCache.StampSettings.clear()
        KCurrent.ev = null
        KCurrent.kdspiral = null
        KStamps.page = 0
        KStamps.currentFace = "sans-serif"
        KSprite.page = 0
        KSprite.sheetPage = 0
        KAlphabet.page = 1
    }
}

/** KiddoPaint.Current */
object KCurrent {
    var color: String = "rgb(0, 0, 0)"
    var altColor: String = "rgb(0, 0, 0)"
    var terColor: String = "rgb(0, 0, 0)"
    lateinit var tool: Tool
    var globalAlpha = 1.0
    var scaling = 1.0
    var modified = false
    var modifiedAlt = false
    var modifiedCtrl = false
    var modifiedToggle = false
    var modifiedMeta = false
    var modifiedTilde = false
    var velToggle = false
    var multiplier = 1
    var prevEv: Ev? = null
    var prevEvTs = 0L
    var velocity = 0.0
    var velocityMultiplier = 1.0
    var modifiedRange = 0
    var modifiedAltRange = 0
    var modifiedCtrlRange = 0
    var ev: Ev? = null
    /** kd-tree state of the spiral tool (js/tools/spiral.js). */
    var kdspiral: Any? = null
}

/** KiddoPaint.Stamps fields shared with init code; stamps/Stamps.kt adds the functions. */
object KStamps {
    var page = 0
    var currentFace = "sans-serif"
    var grouping: Charset? = null
}

/** KiddoPaint.Alphabet: `english`, `wingdings`, `nextPage` live in stamps/Alphabet.kt. */
object KAlphabet {
    var page = 1
}

/** KiddoPaint.Sprite: `sheets`, `nextPage`... live in submenus/Sprites.kt. */
object KSprite {
    var page = 0
    var sheetPage = 0
}

/**
 * What the JS did to the DOM. The Android activity implements it; headless runs keep the
 * default no-op. The engine never touches Views.
 */
interface Ui {
    /** show_generic_submenu / show_sub_toolbar: the option bar now shows [name]. */
    fun showSubmenu(name: String) {}
    /** The alphabet bar (`alphabettoolbar`) with these letters. */
    fun showAlphabet(letters: List<String>) {}
    /** set_colors_to_current_palette / currentColor swatch. */
    fun paletteChanged() {}
    fun currentColorChanged(color: String) {}
    /** canvas.classList cursor class ('cursor-pencil', 'cursor-crosshair', ...). */
    fun cursorChanged(cls: String) {}
    /** Main canvas or layers changed outside a pointer event (timers draw too). */
    fun invalidate() {}
    /** save_to_file and the Android-only buttons (piece 8 implements them). */
    fun save() {}
    fun open() {}
    fun exit() {}
    /** The overlay's highlight changed (KAndroid.currentToolId / selectedOptions / selectedLetter). */
    fun selectionChanged() {}
    /** Nothing to undo/redo: shake the button with this id (kp-nope). */
    fun nudge(id: String) {}
    /** The tool badge next to a hovering pen: [toolId] null hides it; x/y in canvas CSS px. */
    fun penBadge(toolId: String?, clientX: Double, clientY: Double) {}
}

object KUi {
    var ui: Ui = object : Ui {}
    /** The submenu the option bar shows right now ("" = none; "alphabet" for the letters). */
    var currentSubmenu = ""
    var alphabetLetters: List<String> = emptyList()
}

fun init_kiddo_paint(width: Int, height: Int) {
    KDisplay.init_layers(width, height)
    KDisplay.loadFromLocalStorage()
    init_kiddo_defaults()
    init_tool_bar()
    init_subtool_bars()
    init_color_selector()
}

fun init_kiddo_defaults() {
    KiddoPaint.Current.color = KiddoPaint.Colors.currentPalette()[0]
    KiddoPaint.Current.altColor = KiddoPaint.Colors.currentPalette()[0]
    KiddoPaint.Current.terColor = KiddoPaint.Colors.currentPalette()[0]
    KiddoPaint.Current.tool = KiddoPaint.Tools.Pencil
    KiddoPaint.Current.globalAlpha = 1.0
    KiddoPaint.Current.scaling = 1.0
    KiddoPaint.Display.step = 0
    KiddoPaint.Current.modified = false
    KiddoPaint.Current.modifiedAlt = false
    KiddoPaint.Current.modifiedCtrl = false
    KiddoPaint.Current.modifiedToggle = false
    KiddoPaint.Current.modifiedMeta = false
    KiddoPaint.Current.modifiedTilde = false
    KiddoPaint.Current.velToggle = false
    KiddoPaint.Alphabet.page = 1
    KiddoPaint.Stamps.page = 0
    KiddoPaint.Sprite.page = 0
    KiddoPaint.Current.multiplier = 1
    KiddoPaint.Current.prevEv = null
    KiddoPaint.Current.prevEvTs = KTime.now()
    KiddoPaint.Current.velocity = 0.0
    KiddoPaint.Current.velocityMultiplier = 1.0
    reset_ranges()
}

fun reset_ranges() {
    KiddoPaint.Current.multiplier = 1
    KiddoPaint.Current.modifiedRange = 0
    KiddoPaint.Current.modifiedAltRange = 0
    KiddoPaint.Current.modifiedCtrlRange = 0
    KiddoPaint.Current.modifiedToggle = false
    KiddoPaint.Current.velToggle = false
    KiddoPaint.Current.modifiedMeta = false
    KiddoPaint.Current.modifiedTilde = false
}

/** canvas 'mouseleave': force a mouseup and clear the overlays. */
fun canvas_mouseleave() {
    KiddoPaint.Current.ev?.let { KiddoPaint.Current.tool.mouseup(it) }
    KiddoPaint.Display.clearPreview()
    KiddoPaint.Display.clearAnim()
    KiddoPaint.Display.clearBnim()
}

/** document.onkeydown with upstream's key codes (shift 16, ctrl 17, alt 18, cmd 91/93, ~ 192...). */
fun key_down(keyCode: Int, ctrlKey: Boolean = false, key: String = "") {
    when {
        keyCode == 16 -> { KiddoPaint.Current.scaling = 2.0; KiddoPaint.Current.modified = true }
        keyCode == 91 || keyCode == 93 -> KiddoPaint.Current.modifiedCtrl = true
        keyCode == 18 -> KiddoPaint.Current.modifiedAlt = true
        keyCode == 17 -> KiddoPaint.Current.modifiedMeta = true
        keyCode == 192 -> KiddoPaint.Current.modifiedTilde = true
        keyCode == 78 -> { // n
            val c = KiddoPaint.Colors.nextAllColor()
            KiddoPaint.Current.color = c; KiddoPaint.Current.altColor = c; KiddoPaint.Current.terColor = c
            KUi.ui.currentColorChanged(c)
        }
        keyCode == 67 -> { KiddoPaint.Colors.nextPalette(); set_colors_to_current_palette() } // c
        keyCode == 82 -> { // r
            val c = KiddoPaint.Colors.randomAllColor()
            KiddoPaint.Current.color = c
            KUi.ui.currentColorChanged(c)
            KiddoPaint.Current.altColor = KiddoPaint.Colors.randomAllColor()
            KiddoPaint.Current.terColor = KiddoPaint.Colors.randomAllColor()
        }
        keyCode == 83 -> save_to_file()
        keyCode in 49..57 -> KiddoPaint.Current.multiplier = keyCode - 48
        keyCode == 32 -> KiddoPaint.Current.modifiedToggle = !KiddoPaint.Current.modifiedToggle
        keyCode == 86 -> KiddoPaint.Current.velToggle = !KiddoPaint.Current.velToggle
        ctrlKey && key == "z" -> {
            KiddoPaint.Sounds.mainmenu(); KiddoPaint.Sounds.oops()
            KiddoPaint.Display.undo(!KiddoPaint.Current.modifiedAlt)
        }
    }
}

fun key_up(keyCode: Int) {
    when (keyCode) {
        16 -> { KiddoPaint.Current.scaling = 1.0; KiddoPaint.Current.modified = false }
        91, 93 -> KiddoPaint.Current.modifiedCtrl = false
        17 -> KiddoPaint.Current.modifiedMeta = false
        192 -> KiddoPaint.Current.modifiedTilde = false
        18 -> KiddoPaint.Current.modifiedAlt = false
    }
}

/** A palette button press; [which] 1 = left, 3 = right (altColor), 2 = middle (terColor). */
fun colorSelect(colorId: Int, which: Int = 1) {
    KiddoPaint.Sounds.submenucolor()
    val colorSelected = KiddoPaint.Colors.currentPalette().getOrNull(colorId) ?: return
    when (which) {
        1 -> { KiddoPaint.Current.color = colorSelected; KUi.ui.currentColorChanged(colorSelected) }
        3 -> KiddoPaint.Current.altColor = colorSelected
        2 -> KiddoPaint.Current.terColor = colorSelected
    }
}

fun set_colors_to_current_palette() = KUi.ui.paletteChanged()

fun init_color_selector() {
    set_colors_to_current_palette()
    KUi.ui.currentColorChanged(KiddoPaint.Current.color)
}

fun color_prev() {
    KiddoPaint.Sounds.submenucolor(); KiddoPaint.Colors.prevPalette(); set_colors_to_current_palette()
}

fun color_next() {
    KiddoPaint.Sounds.submenucolor(); KiddoPaint.Colors.nextPalette(); set_colors_to_current_palette()
}

fun show_sub_toolbar(subtoolbar: String) {
    reset_ranges()
    KUi.currentSubmenu = if (subtoolbar == "alphabettoolbar") "alphabet" else subtoolbar
    if (subtoolbar != "alphabettoolbar") KUi.ui.showSubmenu(subtoolbar)
}

private fun setCursor(cls: String) {
    KiddoPaint.Display.cursor = cls
    KUi.ui.cursorChanged(cls)
}

/** The main toolbar ids from upstream index.html, top to bottom (save first, undo last). */
val MAIN_TOOLBAR = listOf("save", "pencil", "line", "square", "circle", "brush", "jumble", "flood",
    "eraser", "alphabet", "stamp", "truck", "undo")

/** Buttons' mousedown handlers (init_tool_bar). Returns false for an unknown id. */
fun press_main_tool(id: String, ctrl: Boolean = false): Boolean {
    when (id) {
        "save" -> { KiddoPaint.Sounds.mainmenu(); save_to_file() }
        "open" -> { KiddoPaint.Sounds.mainmenu(); KUi.ui.open() }
        "exit" -> { KiddoPaint.Sounds.mainmenu(); KUi.ui.exit() }
        "pencil" -> {
            KiddoPaint.Sounds.mainmenu()
            show_generic_submenu("pencil")
            KiddoPaint.Current.tool = KiddoPaint.Tools.Pencil
            setCursor("cursor-pencil")
        }
        "line" -> {
            KiddoPaint.Sounds.mainmenu()
            show_generic_submenu("line")
            KiddoPaint.Current.tool = KiddoPaint.Tools.Line
            setCursor("cursor-crosshair")
        }
        "square" -> {
            KiddoPaint.Sounds.mainmenu()
            show_generic_submenu("square")
            KiddoPaint.Current.tool = KiddoPaint.Tools.Square
            setCursor("cursor-crosshair")
        }
        "circle" -> {
            KiddoPaint.Sounds.mainmenu()
            show_generic_submenu("circle")
            KiddoPaint.Current.tool = KiddoPaint.Tools.Circle
            setCursor("cursor-crosshair")
        }
        "brush" -> {
            KiddoPaint.Sounds.mainmenu()
            reset_ranges()
            show_generic_submenu("wackybrush")
            KiddoPaint.Submenu.wackybrush.firstOrNull()?.handler()
        }
        "stamp" -> {
            KiddoPaint.Sounds.mainmenu()
            reset_ranges()
            setCursor("cursor-none")
            if (KiddoPaint.Current.modifiedCtrl || ctrl) {
                show_generic_submenu("stickers")
            } else {
                init_sprites_submenu() // automatically does for current page
                show_generic_submenu("sprites")
                KiddoPaint.Tools.Stamp.useColor = false
                KiddoPaint.Submenu.sprites.firstOrNull()?.handler()
            }
        }
        "alphabet" -> {
            KiddoPaint.Sounds.mainmenu()
            init_alphabet_bar("character" + KiddoPaint.Alphabet.page)
            show_sub_toolbar("alphabettoolbar")
            KiddoPaint.Tools.Stamp.useColor = true
            KiddoPaint.Current.tool = KiddoPaint.Tools.Stamp
            KiddoPaint.Stamps.currentFace = KiddoPaint.Alphabet.english.face
            setCursor("cursor-none")
        }
        "flood" -> {
            KiddoPaint.Sounds.mainmenu()
            show_generic_submenu("flood")
            KiddoPaint.Current.tool = KiddoPaint.Tools.Flood
            setCursor("cursor-bucket")
        }
        "eraser" -> {
            KiddoPaint.Sounds.mainmenu()
            KiddoPaint.Current.tool = KiddoPaint.Tools.Eraser
            setCursor("cursor-crosshair")
            show_generic_submenu("eraser")
        }
        "truck" -> {
            KiddoPaint.Sounds.mainmenu()
            show_generic_submenu("truck")
            KiddoPaint.Current.tool = KiddoPaint.Tools.Cut
            setCursor("cursor-crosshair")
        }
        "undo" -> {
            KiddoPaint.Sounds.mainmenu()
            KiddoPaint.Sounds.oops()
            KiddoPaint.Display.undo(!KiddoPaint.Current.modifiedAlt)
        }
        "alnext" -> {
            KiddoPaint.Sounds.submenuoption()
            KiddoPaint.Alphabet.nextPage()
            init_alphabet_bar("character" + KiddoPaint.Alphabet.page)
        }
        "jumble" -> {
            KiddoPaint.Sounds.mainmenu()
            show_generic_submenu("jumble")
            setCursor("cursor-guy-smile")
            KiddoPaint.Tools.WholeCanvasEffect.effect = JumbleFx.INVERT
            KiddoPaint.Current.tool = KiddoPaint.Tools.WholeCanvasEffect
        }
        else -> return false
    }
    return true
}

fun init_tool_bar() {}

fun init_alphabet_bar(alphabetgroup: String) {
    val alphabettoolbar = KiddoPaint.Alphabet.english[alphabetgroup]?.letters ?: emptyList()
    alphabettoolbar.firstOrNull()?.let { KiddoPaint.Tools.Stamp.stamp = it }
    KUi.alphabetLetters = alphabettoolbar
    KUi.ui.showAlphabet(alphabettoolbar)
}

fun init_subtool_bars() {
    init_pencil_subtoolbar()
}

fun init_pencil_subtoolbar() {
    // this is the default, so we show it
    show_generic_submenu("pencil")
    setCursor("cursor-pencil")
}

/** An alphabet bar button (`xal<i>`); the `alnext` button is index == letters.size. */
fun press_alphabet(index: Int) {
    val letters = KUi.alphabetLetters
    if (index == letters.size) { press_main_tool("alnext"); return }
    val letter = letters.getOrNull(index) ?: return
    reset_ranges()
    KiddoPaint.Tools.Stamp.stamp = letter
    KiddoPaint.Sounds.playKey(letter)
}

fun ev_canvas(ev: Ev?) {
    if (ev == null) return
    // pre event
    KiddoPaint.Display.step += 1
    KiddoPaint.Display.clearPreview()
    KiddoPaint.Current.ev = ev

    ev._x = ev.offsetX
    ev._y = ev.offsetY

    // handle event
    if (ev.type == "touchstart") ev.type = "mousedown"
    if (ev.type == "touchmove") ev.type = "mousemove"
    if (ev.type == "touchend") ev.type = "mouseup"

    KiddoPaint.Current.tool.dispatch(ev)

    // common ev processing
    common_ev_proc(ev)

    KiddoPaint.Current.prevEv = ev
    KiddoPaint.Current.prevEvTs = KTime.now()
}

fun common_ev_proc(ev: Ev) {
    val prev = KiddoPaint.Current.prevEv ?: return
    val dist = distanceBetween(prev, ev)
    val tsdelta = (KTime.now() - KiddoPaint.Current.prevEvTs) + 1
    val velocity = (1.0 * dist) / tsdelta * 1000.0
    KiddoPaint.Current.velocity = velocity
    KiddoPaint.Current.velocityMultiplier = if (velocity > 1000) velocity / 1000 else 1.0
    if (KiddoPaint.Current.velToggle) {
        KiddoPaint.Current.scaling = KiddoPaint.Current.velocityMultiplier
    }
}

fun mouse_wheel(wheelDelta: Double) {
    val delta = max(-1.0, min(1.0, wheelDelta)).toInt()
    if (KiddoPaint.Current.modified) {
        KiddoPaint.Current.modifiedRange += delta
        if (KiddoPaint.Current.modifiedRange > 100) KiddoPaint.Current.modifiedRange = -100
        else if (KiddoPaint.Current.modifiedRange < -100) KiddoPaint.Current.modifiedRange = 100
    } else if (KiddoPaint.Current.modifiedAlt) {
        KiddoPaint.Current.modifiedAltRange += delta
        if (KiddoPaint.Current.modifiedAltRange > 100) KiddoPaint.Current.modifiedAltRange = -100
        else if (KiddoPaint.Current.modifiedAltRange < -100) KiddoPaint.Current.modifiedAltRange = 100
    } else if (KiddoPaint.Current.modifiedCtrl) {
        KiddoPaint.Current.modifiedCtrlRange += delta
        if (KiddoPaint.Current.modifiedCtrlRange > 100) KiddoPaint.Current.modifiedCtrlRange = -100
        else if (KiddoPaint.Current.modifiedCtrlRange < -100) KiddoPaint.Current.modifiedCtrlRange = 100
    }
    // kick off a redraw of preview
    KiddoPaint.Current.ev?.let { ev_canvas(it) }
}

fun save_to_file() = KUi.ui.save()

/** image_upload: an image dropped/opened onto the canvas. */
fun image_upload(img: dev.kidpix.paint.canvas.CanvasImageSource) {
    if (KiddoPaint.Current.modifiedAlt) {
        KiddoPaint.Display.context.drawImage(img, 0, 0)
        KiddoPaint.Display.saveMain()
    } else {
        KiddoPaint.Tools.Placer.image = img
        KiddoPaint.Tools.Placer.size = Size(img.width.toDouble(), img.height.toDouble())
        KiddoPaint.Tools.Placer.prevTool = KiddoPaint.Current.tool
        KiddoPaint.Current.tool = KiddoPaint.Tools.Placer
    }
}

/** `{width: w, height: h}` objects. */
class Size(var width: Double, var height: Double)
