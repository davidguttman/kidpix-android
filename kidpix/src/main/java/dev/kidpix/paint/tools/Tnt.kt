@file:JvmName("tools_Tnt")

package dev.kidpix.paint

import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.world

// Port of js/tools/tnt.js

class ToolboxTnt : Tool() {
    val tool = this
    var isDown = false

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.animate(ev)
    }

    override fun mousemove(ev: Ev) {}

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.isDown = false
        }
    }

    fun animate(ev: Ev) {
        KiddoPaint.Display.saveUndo()
        KiddoPaint.Display.pauseUndo()

        var iter = 1
        var intervalID = 0

        fun drawBomb() {
            val ctx = KiddoPaint.Display.context
            ctx.beginPath()
            ctx.fillStyle = "rgb(0, 255, 0)"
            ctx.arc(ev._x, ev._y, 50 * iter, 0, 2 * Math.PI)
            ctx.fill()
            KiddoPaint.Display.saveMainGcoSkipUndo("difference") // exclusion difference
            iter++
            if (iter > 15) {
                KTime.clearInterval(intervalID) // Will clear the timer.
                KiddoPaint.Display.clearAll()
                KiddoPaint.Display.resumeUndo()
            } else if (KiddoPaint.Current.modifiedMeta) { // hidden feature to block completion
                KTime.clearInterval(intervalID) // Will clear the timer.
                KiddoPaint.Display.resumeUndo() // Already saved to main above in previous frames, so no need here
            }
        }

        intervalID = KTime.setInterval({ drawBomb() }, 175) // Will alert every second.
        KiddoPaint.Sounds.explosion()
        drawBomb()
    }
}

val KiddoPaint.Tools.Tnt by world { ToolboxTnt() }
