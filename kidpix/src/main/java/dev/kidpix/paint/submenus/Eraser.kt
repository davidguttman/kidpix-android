@file:JvmName("submenus_Eraser")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/submenus/eraser.js

/** KiddoPaint.Display.canvas.classList = ""; classList.add(cls) */
private fun eraserCursor(cls: String) {
    KiddoPaint.Display.cursor = cls
    KUi.ui.cursorChanged(cls)
}

var KiddoPaint.Submenu.eraser: MutableList<SubmenuItem> by world {
    mutableListOf(
        SubmenuItem(
            name = "Eraser Squre 20",
            imgSrc = "img/tool-submenu-eraser-178.png",
            handler = {
                eraserCursor("cursor-crosshair")
                KiddoPaint.Tools.Eraser.size = 20.0
                KiddoPaint.Tools.Eraser.isSquareEraser = true
                KiddoPaint.Current.tool = KiddoPaint.Tools.Eraser
            },
        ),
        SubmenuItem(
            name = "Eraser Circle 15",
            imgSrc = "img/tool-submenu-eraser-179.png",
            handler = {
                eraserCursor("cursor-crosshair")
                KiddoPaint.Tools.Eraser.size = 10.0
                KiddoPaint.Tools.Eraser.isSquareEraser = false
                KiddoPaint.Current.tool = KiddoPaint.Tools.Eraser
            },
        ),
        SubmenuItem(
            name = "Eraser",
            imgSrc = "img/tool-submenu-eraser-180.png",
            handler = {
                eraserCursor("cursor-crosshair")
                KiddoPaint.Tools.Eraser.size = 10.0
                KiddoPaint.Tools.Eraser.isSquareEraser = true
                KiddoPaint.Current.tool = KiddoPaint.Tools.Eraser
            },
        ),
        SubmenuItem(
            name = "Eraser",
            imgSrc = "img/tool-submenu-eraser-181.png",
            handler = {
                eraserCursor("cursor-crosshair")
                KiddoPaint.Tools.Eraser.size = 2.0
                KiddoPaint.Tools.Eraser.isSquareEraser = true
                KiddoPaint.Current.tool = KiddoPaint.Tools.Eraser
            },
        ),
        SubmenuItem(
            name = "Firecracker",
            imgSrc = "img/tool-submenu-eraser-182.png",
            handler = {
                eraserCursor("cursor-tnt")
                KiddoPaint.Current.tool = KiddoPaint.Tools.Tnt
            },
        ),
        SubmenuItem(
            name = "Hidden Pictures",
            imgSrc = "img/tool-submenu-eraser-183.png",
            handler = {
                eraserCursor("cursor-crosshair")
                KiddoPaint.Tools.EraserHiddenPicture.reset()
                KiddoPaint.Current.tool = KiddoPaint.Tools.EraserHiddenPicture
            },
        ),
        SubmenuItem(
            name = "White Circles",
            imgSrc = "img/tool-submenu-eraser-184.png",
            handler = {
                eraserCursor("cursor-crosshair")
                KiddoPaint.Tools.EraserWhiteCircles.reset()
                KiddoPaint.Current.tool = KiddoPaint.Tools.EraserWhiteCircles
            },
        ),
        SubmenuItem(
            name = "Slip-Sliding Away",
            imgSrc = "img/tool-submenu-eraser-185.png",
            handler = {
                eraserCursor("cursor-guy-smile")
                KiddoPaint.Current.tool = KiddoPaint.Tools.Doorbell
            },
        ),
        SubmenuItem(
            name = "#\$%!*!!",
            imgSrc = "img/tool-submenu-eraser-186.png",
            handler = {
                eraserCursor("cursor-guy-smile")
                KiddoPaint.Current.tool = KiddoPaint.Tools.EraserLetters
            },
        ),
        SubmenuItem(
            name = "Fade Away",
            imgSrc = "img/tool-submenu-eraser-187.png",
            handler = {
                eraserCursor("cursor-guy-smile")
                KiddoPaint.Current.tool = KiddoPaint.Tools.EraserFadeAway
            },
        ),
        // (upstream has a commented-out "Drop Out" entry here, img 188)
        SubmenuItem(
            name = "Black Hole",
            imgSrc = "img/tool-submenu-eraser-189.png",
            handler = {
                eraserCursor("cursor-crosshair")
                KiddoPaint.Current.tool = KiddoPaint.Tools.Eraser
                KiddoPaint.Sounds.unimpl()
            },
        ),
        SubmenuItem(
            name = "Count Down",
            imgSrc = "img/tool-submenu-eraser-190.png",
            handler = {
                eraserCursor("cursor-crosshair")
                KiddoPaint.Current.tool = KiddoPaint.Tools.Eraser
                KiddoPaint.Sounds.unimpl()
            },
        ),
        // (upstream has a commented-out "Sweep" entry here, img 191)
    )
}
