@file:JvmName("tools_Square")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

// Port of js/tools/square.js

class ToolboxSquare : Tool() {
    val tool = this
    var isDown = false
    var size = 1.0
    var startEv: Ev? = null

    /** JS `tool.texture(start, end)`: most textures ignore the arguments, RainbowGrad uses them. */
    var texture: (Ev?, Ev?) -> Any? = { _, _ ->
        KiddoPaint.Textures.None()
    }
    var stroke: () -> Any? = {
        KiddoPaint.Textures.Solid(KiddoPaint.Current.color)
    }

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.startEv = ev
    }

    override fun mousemove(ev: Ev) {
        val startEv = tool.startEv
        if (startEv != null) {
            val ctx = if (tool.isDown) KiddoPaint.Display.previewContext else KiddoPaint.Display.context
            var sizex = ev._x - startEv._x
            var sizey = ev._y - startEv._y
            if (KiddoPaint.Current.modified) { // kidpix feature to keep side's lengths the same
                val signx = sign(sizex)
                val signy = sign(sizey)
                sizex = max(abs(sizex), abs(sizey))
                sizey = sizex
                sizex *= signx
                sizey *= signy
            }

            if (!KiddoPaint.Current.modifiedCtrl) {
                ctx.strokeStyle = tool.stroke()
                ctx.lineWidth = 1.5
                ctx.strokeRect(startEv._x, startEv._y, sizex, sizey)
            }
            ctx.fillStyle = tool.texture(startEv, ev)
            ctx.fillRect(startEv._x, startEv._y, sizex, sizey)
            KiddoPaint.Sounds.box()
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.isDown = false
            tool.mousemove(ev)
            KiddoPaint.Display.saveMain()
            tool.startEv = null
        }
    }
}

val KiddoPaint.Tools.Square by world { ToolboxSquare() }
