@file:JvmName("submenus_Spray")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

var KiddoPaint.Submenu.spray: MutableList<SubmenuItem> by world {
    mutableListOf(
    SubmenuItem(
        name = "Spray Paint 2",
        imgJs = { KiddoPaint.Textures.SprayPaint2() },
        handler = {
            KiddoPaint.Tools.SpritePlacer.image = KiddoPaint.Textures.SprayPaint2(KiddoPaint.Current.color)
            KiddoPaint.Tools.SpritePlacer.soundBefore = {}
            KiddoPaint.Tools.SpritePlacer.soundDuring = {}
            KiddoPaint.Current.tool = KiddoPaint.Tools.SpritePlacer
        },
    ),
    SubmenuItem(
        name = "Spray Paint 3",
        imgJs = { KiddoPaint.Textures.SprayPaint3() },
        handler = {
            KiddoPaint.Tools.SpritePlacer.image = KiddoPaint.Textures.SprayPaint3(KiddoPaint.Current.color)
            KiddoPaint.Current.tool = KiddoPaint.Tools.SpritePlacer
        },
    ),
    SubmenuItem(
        name = "Spray Paint 4",
        imgJs = { KiddoPaint.Textures.SprayPaint4() },
        handler = {
            KiddoPaint.Tools.SpritePlacer.image = KiddoPaint.Textures.SprayPaint4(KiddoPaint.Current.color)
            KiddoPaint.Current.tool = KiddoPaint.Tools.SpritePlacer
        },
    ),
    SubmenuItem(
        name = "Spray Paint 5",
        imgJs = { KiddoPaint.Textures.SprayPaint5() },
        handler = {
            KiddoPaint.Tools.SpritePlacer.image = KiddoPaint.Textures.SprayPaint5(KiddoPaint.Current.color)
            KiddoPaint.Current.tool = KiddoPaint.Tools.SpritePlacer
        },
    ),
    SubmenuItem(
        name = "Spray Paint 6",
        imgJs = { KiddoPaint.Textures.SprayPaint6() },
        handler = {
            KiddoPaint.Tools.SpritePlacer.image = KiddoPaint.Textures.SprayPaint6(KiddoPaint.Current.color)
            KiddoPaint.Current.tool = KiddoPaint.Tools.SpritePlacer
        },
    ),
    SubmenuItem(
        name = "Spray Paint 7",
        imgJs = { KiddoPaint.Textures.SprayPaint7() },
        handler = {
            KiddoPaint.Tools.SpritePlacer.image = KiddoPaint.Textures.SprayPaint7(KiddoPaint.Current.color)
            KiddoPaint.Current.tool = KiddoPaint.Tools.SpritePlacer
        },
    ),
    SubmenuItem(name = "spacer", invisible = true),
    )
}
