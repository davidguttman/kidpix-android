@file:JvmName("tools_EraserHiddenPictures")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.CanvasPattern
import dev.kidpix.paint.canvas.KImage
import dev.kidpix.paint.engine.world

// Port of js/tools/eraser-hidden-pictures.js

class ToolboxEraserHiddenPicture : Tool() {
    val tool = this
    val hiddenPictures = listOf("img/kp-h-bear.png", "img/kp-h-bison.png", "img/kp-h-corn.png", "img/kp-h-eye.png", "img/kp-h-fox.png", "img/kp-h-horse.png", "img/kp-h-hummingbird.png", "img/kp-h-ladybug.png", "img/kp-h-lion.png", "img/kp-h-magnet.png", "img/kp-h-moth.png", "img/kp-h-octopus.png")
    var isDown = false
    var size = 32.0
    var hiddenPattern: CanvasPattern? = null

    fun reset() {
        val image = KImage()
        image.src = tool.hiddenPictures.random()
        image.crossOrigin = "anonymous"
        image.onload = {
            tool.hiddenPattern = makePatternFromImage(image)
        }
    }

    override fun mousedown(ev: Ev) {
        tool.isDown = true
    }

    override fun mousemove(ev: Ev) {
        val currentSize = tool.size * KiddoPaint.Current.scaling
        if (tool.isDown) {
            val ctx = KiddoPaint.Display.context
            ctx.fillStyle = tool.hiddenPattern
            ctx.fillRect(jsRound(ev._x) - (currentSize / 2.0), jsRound(ev._y) - (currentSize / 2.0), currentSize, currentSize)
        } else {
            val ctx = KiddoPaint.Display.previewContext
            ctx.fillStyle = "white"
            ctx.strokeStyle = "black"
            ctx.strokeRect(jsRound(ev._x) - (currentSize / 2.0), jsRound(ev._y) - (currentSize / 2.0), currentSize, currentSize)
            ctx.fillRect(jsRound(ev._x) - (currentSize / 2.0), jsRound(ev._y) - (currentSize / 2.0), currentSize, currentSize)
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.isDown = false
            KiddoPaint.Display.saveMain()
        }
    }
}

val KiddoPaint.Tools.EraserHiddenPicture by world { ToolboxEraserHiddenPicture() }
