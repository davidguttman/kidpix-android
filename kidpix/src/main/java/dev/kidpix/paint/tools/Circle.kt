@file:JvmName("tools_Circle")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world
import kotlin.math.PI
import kotlin.math.abs

// Port of js/tools/circle.js

class ToolboxCircle : Tool() {
    val tool = this
    var isDown = false
    var size = 1.0
    var stomp = true
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
        if (tool.isDown) {
            val startEv = tool.startEv!!
            if (tool.stomp) {
                KiddoPaint.Display.clearTmp()
            }
            KiddoPaint.Sounds.circle()
            KiddoPaint.Display.context.beginPath()
            KiddoPaint.Display.context.fillStyle = tool.texture(startEv, ev)
            KiddoPaint.Display.context.strokeStyle = tool.stroke()
            KiddoPaint.Display.context.lineWidth = 1.5
            if (KiddoPaint.Current.modifiedMeta) {
                KiddoPaint.Display.context.arc(startEv._x, startEv._y, distanceBetween(ev, Ev(startEv._x, startEv._y)), 0, 2 * PI)
            } else if (KiddoPaint.Current.modified) {
                val sizex = abs(ev._x - startEv._x)
                val sizey = abs(ev._y - startEv._y)
                KiddoPaint.Display.context.ellipse((ev._x + startEv._x) / 2.0, (ev._y + startEv._y) / 2.0, sizex, sizey, 0, 0, 2 * PI)
            } else {
                KiddoPaint.Display.context.arc((ev._x + startEv._x) / 2.0, (ev._y + startEv._y) / 2.0, 0.5 * distanceBetween(ev, Ev(startEv._x, startEv._y)), 0, 2 * PI)
            }
            KiddoPaint.Display.context.fill()
            if (!KiddoPaint.Current.modifiedCtrl) {
                // while dragging: a cheap preview (Ctx2D.draft); the oval kept at mouseup is drawn exactly
                KiddoPaint.Display.context.draft = tool.preview && tool.stomp // without stomp every move's stroke stays: exact
                KiddoPaint.Display.context.stroke()
                KiddoPaint.Display.context.draft = false
            }
            KiddoPaint.Display.context.closePath()
        }
    }

    /** mousemove draws a preview (not the final mouseup stroke). */
    private var preview = true

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            preview = false
            try { tool.mousemove(ev) } finally { preview = true }
            tool.isDown = false
            tool.startEv = null
            KiddoPaint.Display.saveMain()
        }
    }
}

val KiddoPaint.Tools.Circle by world { ToolboxCircle() }
