@file:JvmName("submenus_Jumble")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/submenus/jumble.js

/** `KiddoPaint.Display.canvas.classList = ""; ...classList.add(cls)`. */
internal fun mixerCursor(cls: String) {
    KiddoPaint.Display.cursor = cls
    KUi.ui.cursorChanged(cls)
}

var KiddoPaint.Submenu.jumble: MutableList<SubmenuItem> by world { mutableListOf(
    SubmenuItem(
        name = "Invert",
        imgSrc = "img/tool-submenu-wacky-mixer-164.png",
        handler = {
        mixerCursor("cursor-guy-smile")
        KiddoPaint.Current.tool = KiddoPaint.Tools.ElectricMixerInvert
        },
    ),
    SubmenuItem(
        name = "Raindrops",
        imgSrc = "img/tool-submenu-wacky-mixer-165.png",
        handler = {
        mixerCursor("cursor-guy-smile")
        KiddoPaint.Current.tool = KiddoPaint.Tools.AnimBrush
        KiddoPaint.Tools.AnimBrush.reset()
        KiddoPaint.Tools.AnimBrush.animInterval = 50.0
        KiddoPaint.Tools.AnimBrush.postprocess = {
            mixerCursor("cursor-guy-smile")
        }
        KiddoPaint.Tools.AnimBrush.texture = { _, _ ->
            mixerCursor("cursor-guy-wow")
            KiddoPaint.Sounds.bubblepops()
            val color = KiddoPaint.Colors.randomAllColor()
            KiddoPaint.Brushes.Raindrops(color)
        }
        },
    ),
    SubmenuItem(
        name = "Checkerboard",
        imgSrc = "img/tool-submenu-wacky-mixer-166.png",
        handler = {
        mixerCursor("cursor-guy-smile")
        KiddoPaint.Current.tool = KiddoPaint.Tools.WackyMixerCheckerboard
        },
    ),
    SubmenuItem(
        name = "Wallpaper",
        imgSrc = "img/tool-submenu-wacky-mixer-167.png",
        handler = {
        mixerCursor("cursor-guy-smile")
        KiddoPaint.Current.tool = KiddoPaint.Tools.WackyMixerWallpaper
        },
    ),
    SubmenuItem(
        name = "Venetian Blinds",
        imgSrc = "img/tool-submenu-wacky-mixer-168.png",
        handler = {
        mixerCursor("cursor-guy-smile")
        KiddoPaint.Current.tool = KiddoPaint.Tools.WackyMixerVenetianBlinds
        },
    ),
    SubmenuItem(
        name = "The Outliner",
        imgSrc = "img/tool-submenu-wacky-mixer-169.png",
        handler = {
        KiddoPaint.Sounds.unimpl()
        },
    ),
    SubmenuItem(
        name = "Shadow Boxes",
        imgSrc = "img/tool-submenu-wacky-mixer-170.png",
        handler = {
        mixerCursor("cursor-guy-smile")
        KiddoPaint.Current.tool = KiddoPaint.Tools.WackyMixerShadowBoxes
        },
    ),
    SubmenuItem(
        name = "Zoom In",
        imgSrc = "img/tool-submenu-wacky-mixer-171.png",
        handler = {
        KiddoPaint.Sounds.unimpl()
        },
    ),
    SubmenuItem(
        name = "Broken Glass",
        imgSrc = "img/tool-submenu-wacky-mixer-172.png",
        handler = {
        KiddoPaint.Sounds.unimpl()
        },
    ),
    SubmenuItem(
        name = "Picture In A Picture",
        imgSrc = "img/tool-submenu-wacky-mixer-173.png",
        handler = {
        mixerCursor("cursor-guy-smile")
        KiddoPaint.Current.tool = KiddoPaint.Tools.ElectricMixerPip
        },
    ),
    SubmenuItem(
        name = "The Highlighter",
        imgSrc = "img/tool-submenu-wacky-mixer-174.png",
        handler = {
        KiddoPaint.Sounds.unimpl()
        },
    ),
    SubmenuItem(
        name = "Pattern Maker",
        imgSrc = "img/tool-submenu-wacky-mixer-175.png",
        handler = {
        mixerCursor("cursor-guy-smile")
        KiddoPaint.Current.tool = KiddoPaint.Tools.WackyMixerPattern
        },
    ),
    SubmenuItem(
        name = "Wrap Around",
        imgSrc = "img/tool-submenu-wacky-mixer-176.png",
        handler = {
        KiddoPaint.Sounds.unimpl()
        },
    ),
    /*
    SubmenuItem(
        name = "Snow Flakes And Rain Drops",
        imgSrc = "img/tool-submenu-wacky-mixer-177.png",
        handler = {},
    ),
    */
    SubmenuItem(
        name = "Swirl",
        emoji = "🍭",
        handler = {
            mixerCursor("cursor-lollipop")
            KiddoPaint.Tools.WholeCanvasEffect.effect = JumbleFx.SWIRL
            KiddoPaint.Current.tool = KiddoPaint.Tools.WholeCanvasEffect
        },
    ),
    SubmenuItem(
        name = "Pancake Stack",
        emoji = "🥞",
        handler = {
            mixerCursor("cursor-pancakes")
            KiddoPaint.Tools.WholeCanvasEffect.effect = JumbleFx.PANCAKE
            KiddoPaint.Current.tool = KiddoPaint.Tools.WholeCanvasEffect
        },
    ),
) }
