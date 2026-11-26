@file:JvmName("submenus_Brush")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/submenus/brush.js. The commented-out upstream entries (Drippy Paint, Alphabet Line,
// Caterpillars, Roll The Dice) stay out, as upstream.

/** `KiddoPaint.Display.canvas.classList = ""; ...classList.add(cls)` */
internal fun setCanvasCursor(cls: String) {
    KiddoPaint.Display.cursor = cls
    KUi.ui.cursorChanged(cls)
}

var KiddoPaint.Submenu.wackybrush: MutableList<SubmenuItem> by world {
    mutableListOf(
        SubmenuItem(
            name = "Leaky Pen",
            imgSrc = "img/tool-menu-wacky-brush-70.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.AnimBrush
                KiddoPaint.Tools.AnimBrush.reset()
                KiddoPaint.Tools.AnimBrush.texture = { _, distancePrev ->
                    KiddoPaint.Sounds.brushleakypen() // sound
                    KiddoPaint.Brushes.LeakyPen(KiddoPaint.Current.color, distancePrev)
                }
            },
        ),
        SubmenuItem(
            name = "Zig Zag",
            imgSrc = "img/tool-menu-wacky-brush-71.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.Scribble
            },
        ),
        SubmenuItem(
            name = "Dots",
            imgSrc = "img/tool-menu-wacky-brush-72.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.PlainBrush
                KiddoPaint.Tools.PlainBrush.reset()
                KiddoPaint.Tools.PlainBrush.soundduring = { KiddoPaint.Sounds.brushdots() }
                KiddoPaint.Tools.PlainBrush.texture = { _, _ ->
                    KiddoPaint.Tools.PlainBrush.spacing = 22 * KiddoPaint.Current.scaling
                    if (KiddoPaint.Current.modifiedMeta) KiddoPaint.Brushes.RCircles() else KiddoPaint.Brushes.Circles(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Bubbly",
            imgSrc = "img/tool-menu-wacky-brush-73.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.AnimBrush
                KiddoPaint.Tools.AnimBrush.reset()
                KiddoPaint.Tools.AnimBrush.texture = { _, _ ->
                    KiddoPaint.Sounds.brushbubbly()
                    KiddoPaint.Brushes.Bubbles(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Pies",
            imgSrc = "img/tool-menu-wacky-brush-74.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.PlainBrush
                KiddoPaint.Tools.PlainBrush.reset()
                KiddoPaint.Tools.PlainBrush.soundduring = { KiddoPaint.Sounds.brushpies() }
                KiddoPaint.Tools.PlainBrush.texture = { _, _ ->
                    KiddoPaint.Tools.PlainBrush.spacing = 40 * KiddoPaint.Current.scaling
                    KiddoPaint.Brushes.Pies(KiddoPaint.Current.color)
                }
            },
        ),
        SubmenuItem(
            name = "Echoes",
            imgSrc = "img/tool-menu-wacky-brush-75.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.PlainBrush
                KiddoPaint.Tools.PlainBrush.reset()
                KiddoPaint.Tools.PlainBrush.soundduring = { KiddoPaint.Sounds.brushecho() }
                KiddoPaint.Tools.PlainBrush.spacing = 1.0
                KiddoPaint.Tools.PlainBrush.texture = { step, _ ->
                    KiddoPaint.Brushes.Concentric(KiddoPaint.Current.color, step)
                }
            },
        ),
        SubmenuItem(
            name = "Northern Lights",
            imgSrc = "img/tool-menu-wacky-brush-76.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.Contours
            },
        ),
        SubmenuItem(
            name = "Fuzzer",
            imgSrc = "img/tool-menu-wacky-brush-77.png",
            handler = {
                setCanvasCursor("cursor-none")
                KiddoPaint.Current.tool = KiddoPaint.Tools.Fuzzer
            },
        ),
        SubmenuItem(
            name = "Magnifying Glass",
            imgSrc = "img/tool-menu-wacky-brush-78.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.Magnify
            },
        ),
        SubmenuItem(
            name = "Spray Paint",
            imgSrc = "img/tool-menu-wacky-brush-79.png",
            handler = { e ->
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Tools.SpritePlacer.image = KiddoPaint.Textures.SprayPaint2(KiddoPaint.Current.color)
                KiddoPaint.Tools.SpritePlacer.soundBefore = {}
                KiddoPaint.Tools.SpritePlacer.soundDuring = {
                    KiddoPaint.Sounds.brushspraypaint()
                }
                KiddoPaint.Current.tool = KiddoPaint.Tools.SpritePlacer
                if (e.ctrlKey) {
                    show_generic_submenu("spray")
                }
            },
        ),
        SubmenuItem(
            name = "Pine Needles",
            imgSrc = "img/tool-menu-wacky-brush-80.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.Pines
            },
        ),
        SubmenuItem(
            name = "3-D",
            imgSrc = "img/tool-menu-wacky-brush-81.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.ThreeDBrush
            },
        ),
        SubmenuItem(
            name = "Kaliediscope",
            imgSrc = "img/tool-menu-wacky-brush-82.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.Kaleidoscope
            },
        ),
        SubmenuItem(
            name = "Connect The Dots",
            imgSrc = "img/tool-menu-wacky-brush-84.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.PlainBrush
                KiddoPaint.Tools.PlainBrush.reset()
                KiddoPaint.Tools.PlainBrush.spacing = 25.0
                KiddoPaint.Tools.PlainBrush.soundduring = { KiddoPaint.Sounds.brushtwirly() }
                KiddoPaint.Tools.PlainBrush.texture = { _, pstep ->
                    KiddoPaint.Brushes.ConnectTheDots(if (KiddoPaint.Current.modifiedMeta) KiddoPaint.Colors.nextColor() else KiddoPaint.Current.color, pstep)
                }
            },
        ),
        SubmenuItem(
            name = "Swirl",
            imgSrc = "img/tool-menu-wacky-brush-86.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.PlainBrush
                KiddoPaint.Tools.PlainBrush.reset()
                KiddoPaint.Tools.PlainBrush.spacing = 1.0
                KiddoPaint.Tools.PlainBrush.soundduring = { KiddoPaint.Sounds.brushtwirly() }
                KiddoPaint.Tools.PlainBrush.texture = { step, _ ->
                    KiddoPaint.Brushes.Twirly(if (KiddoPaint.Current.modifiedMeta) KiddoPaint.Colors.nextColor() else KiddoPaint.Current.color, step)
                }
            },
        ),
        SubmenuItem(
            name = "Rotating Dots",
            imgSrc = "img/br12.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.PlainBrush
                KiddoPaint.Tools.PlainBrush.reset()
                KiddoPaint.Tools.PlainBrush.soundduring = { KiddoPaint.Sounds.brushrollingdots() }
                KiddoPaint.Tools.PlainBrush.spacing = 1.0
                KiddoPaint.Tools.PlainBrush.texture = { step, _ ->
                    if (KiddoPaint.Current.modifiedCtrl) {
                        KiddoPaint.Brushes.RotatingPentagon(if (KiddoPaint.Current.modifiedMeta) KiddoPaint.Colors.nextColor() else KiddoPaint.Current.color, step)
                    } else {
                        KiddoPaint.Brushes.FollowingSine(if (KiddoPaint.Current.modifiedMeta) KiddoPaint.Colors.nextColor() else KiddoPaint.Current.color, step)
                    }
                }
            },
        ),
        SubmenuItem(
            name = "Inverter",
            imgSrc = "img/tool-menu-wacky-brush-87.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.Inverter
            },
        ),
        SubmenuItem(
            name = "Geometry",
            imgSrc = "img/tool-menu-wacky-brush-88.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.Guilloche
            },
        ),
        SubmenuItem(
            name = "XY to XY",
            imgSrc = "img/br16.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.Astroid
            },
        ),
        SubmenuItem(
            name = "Tree",
            imgSrc = "img/tool-menu-wacky-brush-89.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.Tree
            },
        ),
        SubmenuItem(
            name = "Splatter Paint",
            imgSrc = "img/tool-menu-wacky-brush-91.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.AnimBrush
                KiddoPaint.Tools.AnimBrush.reset()
                KiddoPaint.Tools.AnimBrush.animInterval = 100.0
                KiddoPaint.Tools.AnimBrush.texture = { _, _ ->
                    KiddoPaint.Sounds.brushbubbly()
                    if (KiddoPaint.Current.modifiedMeta) KiddoPaint.Brushes.Triangles() else KiddoPaint.Brushes.Splatters()
                }
            },
        ),
        SubmenuItem(
            name = "Starburst",
            imgSrc = "img/br-starburst.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Tools.Line.size = if (KiddoPaint.Current.modified) 3.0 else 2.0
                KiddoPaint.Tools.Line.stomp = false
                KiddoPaint.Tools.Line.texture = { _ ->
                    KiddoPaint.Textures.Solid(KiddoPaint.Current.color)
                }
                KiddoPaint.Current.tool = KiddoPaint.Tools.Line
            },
        ),
        SubmenuItem(
            name = "The Looper",
            imgSrc = "img/tool-menu-wacky-brush-92.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.Looper
            },
        ),
        SubmenuItem(
            name = "A Galaxy of Stars",
            imgSrc = "img/tool-menu-wacky-brush-94.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.PlainBrush
                KiddoPaint.Tools.PlainBrush.reset()
                KiddoPaint.Tools.PlainBrush.spacing = 36.0
                KiddoPaint.Tools.PlainBrush.soundduring = { KiddoPaint.Sounds.brushstars() }
                KiddoPaint.Tools.PlainBrush.texture = { _, _ ->
                    KiddoPaint.Builders.Prints(KiddoPaint.Current.color, KiddoPaint.Alphabet.nextWingding(2))
                }
            },
        ),
        SubmenuItem(
            name = "Lots of Hugs and Xs",
            imgSrc = "img/tool-menu-wacky-brush-95.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.PlainBrush
                KiddoPaint.Tools.PlainBrush.reset()
                KiddoPaint.Tools.PlainBrush.soundduring = { KiddoPaint.Sounds.brushxos() }
                KiddoPaint.Tools.PlainBrush.spacing = 36.0
                KiddoPaint.Tools.PlainBrush.texture = { _, _ ->
                    KiddoPaint.Builders.Prints(KiddoPaint.Current.color, KiddoPaint.Alphabet.nextWingding(1))
                }
            },
        ),
        SubmenuItem(
            name = "A Full Deck of Cards",
            imgSrc = "img/tool-menu-wacky-brush-96.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.PlainBrush
                KiddoPaint.Tools.PlainBrush.reset()
                KiddoPaint.Tools.PlainBrush.soundduring = { KiddoPaint.Sounds.brushcards() }
                KiddoPaint.Tools.PlainBrush.spacing = 36.0
                KiddoPaint.Tools.PlainBrush.texture = { _, _ ->
                    KiddoPaint.Builders.Prints(KiddoPaint.Current.color, KiddoPaint.Alphabet.nextWingding(3))
                }
            },
        ),
        SubmenuItem(
            name = "Shapes and More Shapes",
            imgSrc = "img/tool-menu-wacky-brush-97.png",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.PlainBrush
                KiddoPaint.Tools.PlainBrush.reset()
                KiddoPaint.Tools.PlainBrush.soundduring = { KiddoPaint.Sounds.brushshapes() }
                KiddoPaint.Tools.PlainBrush.spacing = 36.0
                KiddoPaint.Tools.PlainBrush.texture = { _, _ ->
                    KiddoPaint.Builders.Prints(KiddoPaint.Current.color, KiddoPaint.Alphabet.nextWingding(4))
                }
            },
        ),
        SubmenuItem(
            name = "Paw Prints",
            emoji = "\uD83D\uDC3E",
            handler = {
                setCanvasCursor("cursor-paint-brush")
                KiddoPaint.Current.tool = KiddoPaint.Tools.Brush
                KiddoPaint.Tools.Brush.reset()
                KiddoPaint.Tools.Brush.soundduring = { KiddoPaint.Sounds.brushprints() }
                KiddoPaint.Tools.Brush.texture = { angle ->
                    KiddoPaint.Builders.Prints(KiddoPaint.Current.color, if (KiddoPaint.Current.modifiedMeta) "\uD83D\uDC63" else "\uD83D\uDC3E", angle).brush
                }
            },
        ),
    )
}
