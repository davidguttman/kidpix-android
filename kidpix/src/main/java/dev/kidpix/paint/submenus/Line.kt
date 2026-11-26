@file:JvmName("submenus_Line")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/submenus/line.js

var KiddoPaint.Submenu.line: MutableList<SubmenuItem> by world {
    mutableListOf(
        SubmenuItem(
            name = "Size 1",
            imgSrc = "img/pw1.png",
            handler = {
                KiddoPaint.Tools.Line.size = 1.0
            },
        ),
        SubmenuItem(
            name = "Size 5",
            imgSrc = "img/pw2.png",
            handler = {
                KiddoPaint.Tools.Line.size = 5.0
            },
        ),
        SubmenuItem(
            name = "Size 10",
            imgSrc = "img/pw3.png",
            handler = {
                KiddoPaint.Tools.Line.size = 9.0
            },
        ),
        SubmenuItem(
            name = "Size 25",
            imgSrc = "img/pw4.png",
            handler = {
                KiddoPaint.Tools.Line.size = 13.0
            },
        ),
        SubmenuItem(
            name = "Size 100",
            imgSrc = "img/pw5.png",
            handler = {
                KiddoPaint.Tools.Line.size = 17.0
            },
        ),
        SubmenuItem(
            name = "Size 100",
            imgSrc = "img/pw6.png",
            handler = {
                KiddoPaint.Tools.Line.size = 25.0
            },
        ),
        SubmenuItem(
            name = "spacer",
            invisible = true,
        ),
        SubmenuItem(
            name = "Texture 1",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Solid)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.Solid(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 2",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Partial1)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.Partial1(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 3",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Partial2)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.Partial2(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 4",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Partial3)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.Partial3(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 6",
            imgJs = {
                makeIcon(KiddoPaint.Textures::PartialArtifactAlias)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.PartialArtifactAlias(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 7",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Speckles)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.Speckles(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 7",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Stripes)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.Stripes(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 7",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Thatch)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.Thatch(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 7",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Shingles)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.Shingles(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 8",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Bubbles)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.Bubbles(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 9",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Diamond)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.Diamond(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 9",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Ribbon)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.Ribbon(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 10",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Sand)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.Sand(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 11",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Brick)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.Brick(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 11",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Chevron)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.Chevron(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 11",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Stairs)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.Stairs(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 11",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Cross)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.Cross(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 11",
            imgJs = {
                makeIcon(KiddoPaint.Textures::DiagBrick)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.DiagBrick(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 12",
            imgJs = {
                makeIcon(KiddoPaint.Textures::CornerStair)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.CornerStair(color)
                }
            },
        ),
        SubmenuItem(
            name = "Texture 13",
            imgJs = {
                makeIcon(KiddoPaint.Textures::Houndstooth)
            },
            handler = {
                KiddoPaint.Tools.Line.stomp = true
                KiddoPaint.Tools.Line.texture = { color ->
                    KiddoPaint.Textures.Houndstooth(color)
                }
            },
        ),
        SubmenuItem(
            name = "Rainbow",
            imgSrc = "img/tool-unknown.png",
            handler = {
                KiddoPaint.Tools.Line.stomp = false
                KiddoPaint.Tools.Line.texture = { _ ->
                    KiddoPaint.Textures.RSolid()
                }
            },
        ),
    )
}
