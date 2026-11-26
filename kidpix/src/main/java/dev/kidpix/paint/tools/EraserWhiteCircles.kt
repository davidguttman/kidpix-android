@file:JvmName("tools_EraserWhiteCircles")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/tools/eraser-white-circles.js

class ToolboxEraserWhiteCircles : Tool() {
    val tool = this
    var isDown = false
    var size = 10.0

    fun reset() {
        tool.size = 25.0
    }

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.mousemove(ev)
    }

    override fun mousemove(ev: Ev) {
        if (tool.isDown) {
            KiddoPaint.Sounds.bubblepops()
            val currentSize = tool.size * KiddoPaint.Current.scaling
            val ctx = KiddoPaint.Display.context
            ctx.fillStyle = "white"
            ctx.beginPath()
            val rx = getRandomFloat(0.0, KiddoPaint.Display.canvas.width.toDouble())
            val ry = getRandomFloat(0.0, KiddoPaint.Display.canvas.height.toDouble())
            ctx.arc(rx, ry, currentSize, 0, 2 * Math.PI)
            ctx.fill()
            ctx.closePath()
            if (tool.size < 200)
                tool.size += 1
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.mousemove(ev)
            tool.isDown = false
            KiddoPaint.Display.saveMainGco("destination-out")
        }
    }
}

val KiddoPaint.Tools.EraserWhiteCircles by world { ToolboxEraserWhiteCircles() }
