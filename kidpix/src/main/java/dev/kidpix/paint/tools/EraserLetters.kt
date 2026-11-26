@file:JvmName("tools_EraserLetters")

package dev.kidpix.paint

import dev.kidpix.paint.engine.KRandom
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.world

// Port of js/tools/eraser-letters.js

class ToolboxEraserLetters : Tool() {
    val tool = this
    var isDown = false
    var animInterval = 10
    var timeout: Int? = null

    override fun mousedown(ev: Ev) {
        tool.isDown = true
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
            if (tool.timeout != null) {
                KTime.clearTimeout(tool.timeout)
                tool.timeout = null
            }
            KiddoPaint.Display.clearAnim()
            KiddoPaint.Display.clearAll()
        }
    }

    fun toolDraw() {
        if (tool.isDown) {
            KiddoPaint.Sounds.mixershadowbox()
            val rx = getRandomFloat(-10.0, KiddoPaint.Display.canvas.width.toDouble())
            val ry = getRandomFloat(-10.0, KiddoPaint.Display.canvas.height.toDouble())
            val rs = getRandomInt(24, 500)
            val rl = getRandomLetter()

            KiddoPaint.Display.animContext.fillStyle = "white"
            KiddoPaint.Display.animContext.fillRect(rx, ry, rs / 2.0, rs / 2.0)

            KiddoPaint.Display.animContext.font = "${rs}px serif"
            KiddoPaint.Display.animContext.textBaseline = "top"
            KiddoPaint.Display.animContext.textAlign = "center"

            KiddoPaint.Display.animContext.fillStyle = KiddoPaint.Colors.randomAllColor()
            KiddoPaint.Display.animContext.strokeStyle = KiddoPaint.Colors.randomAllColor()
            if (KRandom.random() > 0.25) {
                KiddoPaint.Display.animContext.fillText(" " + rl, rx, ry)
            } else {
                KiddoPaint.Display.animContext.strokeText(" " + rl, rx, ry)
            }
        }
    }
}

val KiddoPaint.Tools.EraserLetters by world { ToolboxEraserLetters() }
