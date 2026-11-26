@file:JvmName("submenus_Circle")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/submenus/circle.js

var KiddoPaint.Submenu.circle: MutableList<SubmenuItem> by world {
    mutableListOf(
        SubmenuItem(
            name = "Texture 1",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::None)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.None()  // JS passes the color; None ignores it
                }
            },
        ),
        SubmenuItem(
            name = "Texture 1",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::Solid)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.Solid(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 2",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::Partial1)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.Partial1(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 3",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::Partial2)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.Partial2(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 4",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::Partial3)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.Partial3(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 6",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::PartialArtifactAlias)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.PartialArtifactAlias(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 7",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::Speckles)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.Speckles(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 7",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::Stripes)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.Stripes(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 7",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::Thatch)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.Thatch(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 7",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::Shingles)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.Shingles(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 8",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::Bubbles)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.Bubbles(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 9",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::Diamond)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.Diamond(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 9",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::Ribbon)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.Ribbon(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 10",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::Sand)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.Sand(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 11",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::Brick)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.Brick(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 11",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::Chevron)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.Chevron(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 11",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::Stairs)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.Stairs(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 11",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::Cross)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.Cross(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 11",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::DiagBrick)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.DiagBrick(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 12",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::CornerStair)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.CornerStair(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 13",
            imgJs = {
                makeCircleIcon(KiddoPaint.Textures::Houndstooth)
            },
            handler = {
                KiddoPaint.Tools.Circle.texture = { _, _ ->
                    KiddoPaint.Textures.Houndstooth(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture ?",
            imgSrc = "img/tool-unknown.png",
            handler = {
                KiddoPaint.Tools.Circle.texture = { start, end ->
                    KiddoPaint.Textures.RainbowGrad(start, end)
                }
            },
        ),
    )
}
