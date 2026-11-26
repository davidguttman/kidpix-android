@file:JvmName("submenus_Stickers")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KImage
import dev.kidpix.paint.engine.world

var KiddoPaint.Submenu.stickers: MutableList<SubmenuItem> by world {
    mutableListOf(
    SubmenuItem(
        name = "Kid Pix Sticker 1",
        imgSrc = "img/kp-sticker-1.png",
        handler = {
            val img = KImage()
            img.src = "img/kp-sticker-1.png"
            img.crossOrigin = "anonymous"
            img.onload = {
                KiddoPaint.Tools.Placer.image = img
                KiddoPaint.Tools.Placer.size = Size(img.width.toDouble(), img.height.toDouble())
                KiddoPaint.Current.tool = KiddoPaint.Tools.Placer
            }
        },
    ),
    SubmenuItem(
        name = "Kid Pix Sticker 2",
        imgSrc = "img/kp-sticker-2.png",
        handler = {
            val img = KImage()
            img.src = "img/kp-sticker-2.png"
            img.crossOrigin = "anonymous"
            img.onload = {
                KiddoPaint.Tools.Placer.image = img
                KiddoPaint.Tools.Placer.size = Size(img.width.toDouble(), img.height.toDouble())
                KiddoPaint.Current.tool = KiddoPaint.Tools.Placer
            }
        },
    ),
    SubmenuItem(
        name = "Kid Pix Sticker 3",
        imgSrc = "img/kp-sticker-3.png",
        handler = {
            val img = KImage()
            img.src = "img/kp-sticker-3.png"
            img.crossOrigin = "anonymous"
            img.onload = {
                KiddoPaint.Tools.Placer.image = img
                KiddoPaint.Tools.Placer.size = Size(img.width.toDouble(), img.height.toDouble())
                KiddoPaint.Current.tool = KiddoPaint.Tools.Placer
            }
        },
    ),
    SubmenuItem(
        name = "Kid Pix Sticker 4",
        imgSrc = "img/kp-sticker-4.png",
        handler = {
            val img = KImage()
            img.src = "img/kp-sticker-4.png"
            img.crossOrigin = "anonymous"
            img.onload = {
                KiddoPaint.Tools.Placer.image = img
                KiddoPaint.Tools.Placer.size = Size(img.width.toDouble(), img.height.toDouble())
                KiddoPaint.Current.tool = KiddoPaint.Tools.Placer
            }
        },
    ),
    SubmenuItem(
        name = "Kid Pix Sticker 5",
        imgSrc = "img/kp-sticker-5.png",
        handler = {
            val img = KImage()
            img.src = "img/kp-sticker-5.png"
            img.crossOrigin = "anonymous"
            img.onload = {
                KiddoPaint.Tools.Placer.image = img
                KiddoPaint.Tools.Placer.size = Size(img.width.toDouble(), img.height.toDouble())
                KiddoPaint.Current.tool = KiddoPaint.Tools.Placer
            }
        },
    ),
    SubmenuItem(
        name = "Kid Pix Sticker 6",
        imgSrc = "img/kp-sticker-6.png",
        handler = {
            val img = KImage()
            img.src = "img/kp-sticker-6.png"
            img.crossOrigin = "anonymous"
            img.onload = {
                KiddoPaint.Tools.Placer.image = img
                KiddoPaint.Tools.Placer.size = Size(img.width.toDouble(), img.height.toDouble())
                KiddoPaint.Current.tool = KiddoPaint.Tools.Placer
            }
        },
    ),
    )
}
