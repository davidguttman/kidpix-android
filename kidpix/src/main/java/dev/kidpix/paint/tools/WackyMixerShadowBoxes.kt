@file:JvmName("tools_WackyMixerShadowBoxes")

package dev.kidpix.paint

import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.world

// Port of js/tools/wacky-mixer-shadow-boxes.js

class ToolboxWackyMixerShadowBoxes : Tool() {
    val tool = this
    var isDown = false
    var animInterval = 50.0
    var timeout: Int? = null

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        KiddoPaint.Display.context.save()
        mixerCursor("cursor-guy-wow")
        val interval = tool.animInterval
        fun draw() {
            tool.toolDraw()
            if (tool.timeout == null) return
            tool.timeout = KTime.setTimeout({ draw() }, interval)
        }
        tool.timeout = KTime.setTimeout({ draw() }, interval)
        tool.toolDraw()
    }

    override fun mousemove(ev: Ev) {}

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.isDown = false
            KiddoPaint.Display.context.restore()
            mixerCursor("cursor-guy-smile")
            if (tool.timeout != null) {
                KTime.clearTimeout(tool.timeout)
                tool.timeout = null
            }
            KiddoPaint.Display.saveMain()
        }
    }

    fun toolDraw() {
        if (tool.isDown) {
            KiddoPaint.Sounds.mixershadowbox()

            val minSize = 0.05 * KiddoPaint.Display.canvas.width
            val maxSize = 0.20 * KiddoPaint.Display.canvas.width

            // random source
            val rx = getRandomFloat(0.0, KiddoPaint.Display.canvas.width.toDouble())
            val ry = getRandomFloat(0.0, KiddoPaint.Display.canvas.height.toDouble())
            val rwidth = getRandomFloat(minSize, maxSize)
            val rheight = getRandomFloat(minSize, maxSize)
            // random dest
            val rdx = getRandomFloat(-25.0, KiddoPaint.Display.canvas.width.toDouble())
            val rdy = getRandomFloat(-25.0, KiddoPaint.Display.canvas.height.toDouble())

            val sourceImage = KiddoPaint.Display.main_context.getImageData(rx, ry, rwidth, rheight)

            KiddoPaint.Display.context.shadowColor = if (KiddoPaint.Current.modifiedMeta) KiddoPaint.Colors.randomAllColor() else "black"
            KiddoPaint.Display.context.shadowBlur = 4.0
            KiddoPaint.Display.context.lineWidth = 4.0
            KiddoPaint.Display.context.shadowOffsetX = 2.0
            KiddoPaint.Display.context.shadowOffsetY = 2.0

            KiddoPaint.Display.context.strokeStyle = KiddoPaint.Current.color
            KiddoPaint.Display.context.strokeRect(rdx, rdy, rwidth, rheight)
            KiddoPaint.Display.context.putImageData(sourceImage, rdx, rdy)
        }
    }
}

val KiddoPaint.Tools.WackyMixerShadowBoxes by world { ToolboxWackyMixerShadowBoxes() }
