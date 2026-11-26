@file:JvmName("submenus_Square")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/submenus/square.js

var KiddoPaint.Submenu.square: MutableList<SubmenuItem> by world {
    mutableListOf(
        SubmenuItem(
            name = "Texture 1",
            imgJs = {
                makeIcon(KiddoPaint.Textures::None)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.None()  // JS passes the color; None ignores it
                }
            },
        ),
        SubmenuItem(
            name = "Texture 1",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Solid)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.Solid(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 2",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Partial1)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.Partial1(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 3",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Partial2)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.Partial2(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 4",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Partial3)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.Partial3(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 6",
            imgJs = {
                makeIcon(KiddoPaint.Textures::PartialArtifactAlias)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.PartialArtifactAlias(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 7",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Speckles)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.Speckles(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 7",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Stripes)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.Stripes(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 7",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Thatch)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.Thatch(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 7",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Shingles)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.Shingles(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 8",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Bubbles)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.Bubbles(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 9",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Diamond)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.Diamond(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 9",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Ribbon)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.Ribbon(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 10",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Sand)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.Sand(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 11",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Brick)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.Brick(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 11",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Chevron)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.Chevron(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 11",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Stairs)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.Stairs(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 11",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Cross)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.Cross(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 11",
            imgJs = {
                makeIcon(KiddoPaint.Textures::DiagBrick)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.DiagBrick(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 12",
            imgJs = {
                makeIcon(KiddoPaint.Textures::CornerStair)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.CornerStair(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 13",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Houndstooth)
            },
            handler = {
                KiddoPaint.Tools.Square.texture = { _, _ ->
                    KiddoPaint.Textures.Houndstooth(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture ?",
            imgSrc = "img/tool-unknown.png",
            handler = {
                KiddoPaint.Tools.Square.texture = { start, end ->
                    KiddoPaint.Textures.RainbowGrad(start, end)
                }
            },
        ),
    )
}
