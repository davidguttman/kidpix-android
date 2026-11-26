@file:JvmName("submenus_Pencil")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/submenus/pencil.js

var KiddoPaint.Submenu.pencil: MutableList<SubmenuItem> by world {
    mutableListOf(
        SubmenuItem(
            name = "Size 1",
            imgSrc = "img/tool-submenu-pencil-size-1.png",
            handler = {
                KiddoPaint.Tools.Pencil.size = 1.0
            },
        ),
        SubmenuItem(
            name = "Size 5",
            imgSrc = "img/tool-submenu-pencil-size-2.png",
            handler = {
                KiddoPaint.Tools.Pencil.size = 5.0
            },
        ),
        SubmenuItem(
            name = "Size 10",
            imgSrc = "img/tool-submenu-pencil-size-3.png",
            handler = {
                KiddoPaint.Tools.Pencil.size = 9.0
            },
        ),
        SubmenuItem(
            name = "Size 25",
            imgSrc = "img/tool-submenu-pencil-size-4.png",
            handler = {
                KiddoPaint.Tools.Pencil.size = 13.0
            },
        ),
        SubmenuItem(
            name = "Size 100",
            imgSrc = "img/tool-submenu-pencil-size-5.png",
            handler = {
                KiddoPaint.Tools.Pencil.size = 17.0
            },
        ),
        SubmenuItem(
            name = "Size 100",
            imgSrc = "img/tool-submenu-pencil-size-6.png",
            handler = {
                KiddoPaint.Tools.Pencil.size = 25.0
            },
        ),

        SubmenuItem(
            name = "spacer",
            invisible = true,
        ),

        SubmenuItem(
            name = "Solid",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Solid)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.Solid(color)
                }
            },
        ),
        SubmenuItem(
            name = "Partial 1",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Partial1)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.Partial1(color)
                }
            },
        ),
        SubmenuItem(
            name = "Partial 2",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Partial2)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.Partial2(color)
                }
            },
        ),
        SubmenuItem(
            name = "Partial 3",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Partial3)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.Partial3(color)
                }
            },
        ),
        SubmenuItem(
            name = "Partial Artifact",
            imgJs = {
                makeIcon(KiddoPaint.Textures::PartialArtifactAlias)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.PartialArtifactAlias(color)
                }
            },
        ),
        SubmenuItem(
            name = "Speckles",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Speckles)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.Speckles(color)
                }
            },
        ),
        SubmenuItem(
            name = "Stripes",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Stripes)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.Stripes(color)
                }
            },
        ),
        SubmenuItem(
            name = "Thatch",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Thatch)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.Thatch(color)
                }
            },
        ),
        SubmenuItem(
            name = "Shingles",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Shingles)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.Shingles(color)
                }
            },
        ),
        SubmenuItem(
            name = "Bubbles",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Bubbles)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.Bubbles(color)
                }
            },
        ),
        SubmenuItem(
            name = "Diamond",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Diamond)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.Diamond(color)
                }
            },
        ),
        SubmenuItem(
            name = "Ribbon",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Ribbon)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.Ribbon(color)
                }
            },
        ),
        SubmenuItem(
            name = "Sand",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Sand)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.Sand(color)
                }
            },
        ),
        SubmenuItem(
            name = "Brick",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Brick)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.Brick(color)
                }
            },
        ),
        SubmenuItem(
            name = "Chevron",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Chevron)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.Chevron(color)
                }
            },
        ),
        SubmenuItem(
            name = "Stairs",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Stairs)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.Stairs(color)
                }
            },
        ),
        SubmenuItem(
            name = "Cross",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Cross)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.Cross(color)
                }
            },
        ),
        SubmenuItem(
            name = "Diagonal Brick",
            imgJs = {
                makeIcon(KiddoPaint.Textures::DiagBrick)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.DiagBrick(color)
                }
            },
        ),
        SubmenuItem(
            name = "Corner Stair",
            imgJs = {
                makeIcon(KiddoPaint.Textures::CornerStair)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.CornerStair(color)
                }
            },
        ),
        SubmenuItem(
            name = "Houndstooth",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Houndstooth)
            },
            handler = {
                KiddoPaint.Tools.Pencil.texture = { color ->
                    KiddoPaint.Textures.Houndstooth(color)
                }
            },
        ),

        SubmenuItem(
            name = "Rainbow",
            imgSrc = "img/tool-unknown.png",
            handler = {
                var hue = 0
                KiddoPaint.Tools.Pencil.texture = { _ ->
                    if (KiddoPaint.Current.modifiedMeta) {
                        KiddoPaint.Textures.Rainbow()
                    } else if (KiddoPaint.Current.modifiedAlt) {
                        KiddoPaint.Textures.RSolid()
                    } else {
                        hue++
                        if (hue >= 360) hue = 0
                        KiddoPaint.Textures.HueSolid(hue)
                    }
                }
            },
        ),
        // (upstream has a commented-out "Rainbow 3" PlainBrush entry here)
    )
}
