@file:JvmName("tools_WackyMixerPattern")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KImage
import dev.kidpix.paint.engine.world

// Port of js/tools/wacky-mixer-pattern.js

class ToolboxWackyMixerPattern : Tool() {
    val tool = this
    val patternImages = (206..230).map { "img/kidpix-mixer-pattern-$it.png" }

    override fun mousedown(ev: Ev) {
        KiddoPaint.Sounds.mixerframe()
        val image = KImage()
        image.src = tool.patternImages.random()
        image.crossOrigin = "anonymous"
        image.onload = {
            val ctx = KiddoPaint.Display.context
            ctx.imageSmoothingEnabled = false
            ctx.fillStyle = ctx.createPattern(scaleImageDataCanvasAPIPixelated(image, 4.0), "repeat")
            ctx.fillRect(0, 0, KiddoPaint.Display.canvas.width, KiddoPaint.Display.canvas.height)
            KiddoPaint.Display.saveMain()
        }
    }

    override fun mousemove(ev: Ev) {}

    override fun mouseup(ev: Ev) {}
}

val KiddoPaint.Tools.WackyMixerPattern by world { ToolboxWackyMixerPattern() }
