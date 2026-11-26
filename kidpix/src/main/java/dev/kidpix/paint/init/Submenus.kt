@file:JvmName("init_Submenus")

package dev.kidpix.paint

// Port of js/init/submenus.js. The lists themselves live in submenus/<Name>.kt as
// `var KiddoPaint.Submenu.<name> by world { mutableListOf(...) }`.

/** Every submenu upstream names. Each one is declared in its own file under submenus/. */
val SUBMENU_NAMES = listOf("pencil", "line", "square", "circle", "wackybrush", "spray", "jumble", "flood",
    "eraser", "truck", "sprites", "stickers")

/** KiddoPaint.Submenu[name] as in the JS. */
internal fun submenuByName(name: String): MutableList<SubmenuItem>? = when (name) {
        "pencil" -> KiddoPaint.Submenu.pencil
        "line" -> KiddoPaint.Submenu.line
        "square" -> KiddoPaint.Submenu.square
        "circle" -> KiddoPaint.Submenu.circle
        "wackybrush" -> KiddoPaint.Submenu.wackybrush
        "spray" -> KiddoPaint.Submenu.spray
        "jumble" -> KiddoPaint.Submenu.jumble
        "flood" -> KiddoPaint.Submenu.flood
        "eraser" -> KiddoPaint.Submenu.eraser
        "truck" -> KiddoPaint.Submenu.truck
        "sprites" -> KiddoPaint.Submenu.sprites
        "stickers" -> KiddoPaint.Submenu.stickers
        else -> null
}

fun show_generic_submenu(subtoolbar: String) {
    val list = KiddoPaint.Submenu[subtoolbar]
    if (list == null || list.isEmpty() && subtoolbar !in SUBMENU_NAMES) return
    reset_ranges()
    KAndroid.onShowGenericSubmenu(subtoolbar) // the overlay's wrapper (overlay/android.js)
    KUi.currentSubmenu = subtoolbar
    KUi.ui.showSubmenu(subtoolbar)
}

/**
 * A press on button [index] of the generic submenu showing now (spacers count). The JS wraps
 * each handler with the submenuoption sound; a long press / right click arrives as
 * "contextmenu".
 */
fun press_submenu(index: Int, contextmenu: Boolean = false, ctrl: Boolean = false): Boolean {
    val list = KiddoPaint.Submenu[KUi.currentSubmenu] ?: return false
    val item = list.getOrNull(index) ?: return false
    val h = item.handler ?: return false
    val prevCtrl = KiddoPaint.Current.modifiedCtrl
    val ev = Ev(type = if (contextmenu) "contextmenu" else "click").apply {
        which = if (contextmenu) 3 else 1; ctrlKey = ctrl
    }
    KiddoPaint.Sounds.submenuoption()
    h(ev)
    if (ctrl) KiddoPaint.Current.modifiedCtrl = prevCtrl
    return true
}
