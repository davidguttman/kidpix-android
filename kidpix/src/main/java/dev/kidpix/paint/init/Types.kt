@file:JvmName("init_Types")

package dev.kidpix.paint

/**
 * A mouse-ish event as upstream sees it after ev_canvas: `_x`/`_y` are canvas pixels.
 * Tools also build synthetic ones (`{_x: 3, _y: 4}` in the JS) with `Ev(3, 4)`.
 */
class Ev(
    var _x: Double = 0.0,
    var _y: Double = 0.0,
    var type: String = "",
) {
    constructor(x: Number, y: Number, type: String = "") : this(x.toDouble(), y.toDouble(), type)

    var offsetX: Double = _x
    var offsetY: Double = _y
    /** 1 = left (primary), 2 = middle, 3 = right. */
    var which: Int = 1
    var button: Int = 0
    var buttons: Int = 0
    var pressure: Double = 0.5
    var pointerType: String = "mouse"
    var shiftKey = false
    var altKey = false
    var ctrlKey = false
    var metaKey = false
    var timeStamp: Double = 0.0
    fun preventDefault() {}
    fun stopPropagation() {}
}

/**
 * Base of every tool (`KiddoPaint.Tools.Toolbox.X` constructors). ev_canvas calls the method
 * named by the event type; the defaults do nothing (the JS `if (func)` check).
 */
abstract class Tool {
    open fun mousedown(ev: Ev) {}
    open fun mousemove(ev: Ev) {}
    open fun mouseup(ev: Ev) {}

    fun dispatch(ev: Ev) = when (ev.type) {
        "mousedown" -> mousedown(ev)
        "mousemove" -> mousemove(ev)
        "mouseup" -> mouseup(ev)
        else -> Unit
    }
}

/** A tool that does nothing; stubs use it until their piece is ported. */
open class NoopTool(val name: String) : Tool()

/**
 * One button of a generic submenu (`KiddoPaint.Submenu.x[i]` in the JS). Exactly one of the
 * display fields is normally set. `handler` gets the click event (`e.type` is "click", or
 * "contextmenu" for a long press / right click). Spacers: `invisible = true`, no handler.
 */
class SubmenuItem(
    val name: String,
    val imgSrc: String? = null,
    /** JS `imgJs` returned a data URL; here it returns the icon canvas (see makeIcon). */
    val imgJs: (() -> dev.kidpix.paint.canvas.CanvasImageSource)? = null,
    val text: String? = null,
    val emoji: String? = null,
    val spriteSheet: String? = null,
    val spriteCol: Int = 0,
    val spriteRow: Int = 0,
    val invisible: Boolean = false,
    val handler: ((Ev) -> Unit)? = null,
) {
    /** `KiddoPaint.Submenu.x[0].handler()` with no event. */
    fun handler() { handler?.invoke(Ev(type = "click")) }
}

/** `KiddoPaint.Alphabet.english[...]`-style character sets. */
class CharGroup(val letters: List<String>)

class Charset(val face: String, val pages: Int, val groups: Map<String, CharGroup>) {
    operator fun get(key: String): CharGroup? = groups[key]
}
