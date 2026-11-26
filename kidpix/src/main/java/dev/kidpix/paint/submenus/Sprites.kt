@file:JvmName("submenus_Sprites")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KImage
import dev.kidpix.paint.engine.world

var KiddoPaint.Submenu.sprites: MutableList<SubmenuItem> by world { mutableListOf() }

var KSprite.sheets: List<String> by world {
    listOf(
        "img/kidpix-spritesheet-0.png",
        "img/kidpix-spritesheet-0b.png",
        "img/kidpix-spritesheet-1.png",
        "img/kidpix-spritesheet-2.png",
        "img/kidpix-spritesheet-3.png",
        "img/kidpix-spritesheet-4.png",
        "img/kidpix-spritesheet-5.png",
        "img/kidpix-spritesheet-6.png",
        "img/kidpix-spritesheet-7.png",
        "img/kidpix-spritesheet-8.png",
    )
}

fun KSprite.nextSprite() {
    val maxrow = KiddoPaint.Sprite.sheets.size - 1
    KiddoPaint.Sprite.sheetPage += 1
    if (KiddoPaint.Sprite.sheetPage > maxrow) {
        KiddoPaint.Sprite.sheetPage = 0
    }
}

fun KSprite.prevSprite() {
    val maxrow = KiddoPaint.Sprite.sheets.size - 1
    KiddoPaint.Sprite.sheetPage -= 1
    if (KiddoPaint.Sprite.sheetPage < 0) {
        KiddoPaint.Sprite.sheetPage = maxrow
    }
}

fun KSprite.nextPage() {
    val maxrow = 7
    KiddoPaint.Sprite.page += 1
    if (KiddoPaint.Sprite.page > maxrow) {
        KiddoPaint.Sprite.page = 0
    }
}

fun KSprite.prevPage() {
    val maxrow = 7
    KiddoPaint.Sprite.page -= 1
    if (KiddoPaint.Sprite.page < 0) {
        KiddoPaint.Sprite.page = maxrow
    }
}

fun init_sprites_submenu() {
    val sheet = KiddoPaint.Sprite.sheets[KiddoPaint.Sprite.sheetPage]
    val maxcols = 14

    val row = KiddoPaint.Sprite.page

    KiddoPaint.Submenu.sprites = mutableListOf()

    for (j in 0 until maxcols) {
        val individualSprite = SubmenuItem(
            name = "Sprite",
            spriteSheet = sheet,
            spriteRow = row,
            spriteCol = j,
            handler = {
                val img = KImage()
                img.src = sheet
                img.crossOrigin = "anonymous"
                img.onload = {
                    KiddoPaint.Tools.SpritePlacer.image = scaleImageDataCanvasAPIPixelated(extractSprite(img, 32, j, row, 0), 2.0)
                    KiddoPaint.Tools.SpritePlacer.soundBefore = {
                        KiddoPaint.Sounds.stamp()
                    }
                    KiddoPaint.Tools.SpritePlacer.soundDuring = {}
                    KiddoPaint.Current.tool = KiddoPaint.Tools.SpritePlacer
                }
            },
        )

        KiddoPaint.Submenu.sprites.add(individualSprite)
    }

    KiddoPaint.Submenu.sprites.add(SubmenuItem(
        name = "Next Page",
        emoji = "↪",
        handler = { e ->
            if (e.type == "contextmenu") KiddoPaint.Sprite.prevPage() else KiddoPaint.Sprite.nextPage()
            init_sprites_submenu()
            show_generic_submenu("sprites")
        },
    ))

    KiddoPaint.Submenu.sprites.add(SubmenuItem(
        name = "Next Stamp Pack",
        emoji = "➡️",
        handler = { e ->
            if (e.type == "contextmenu") KiddoPaint.Sprite.prevSprite() else KiddoPaint.Sprite.nextSprite()
            init_sprites_submenu()
            show_generic_submenu("sprites")
        },
    ))
}
