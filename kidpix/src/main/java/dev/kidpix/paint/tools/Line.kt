@file:JvmName("tools_Line")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world
import kotlin.math.abs

// Port of js/tools/line.js

class ToolboxLine : Tool() {
    val tool = this
    var isDown = false
    var size = 7.0
    var stomp = true
    var x = 0.0
    var y = 0.0

    /** JS `tool.texture()` reads KiddoPaint.Current.color itself; here the color is passed in. */
    var texture: (String) -> Any? = { color ->
        KiddoPaint.Textures.Solid(color)
    }

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.x = ev._x
        tool.y = ev._y
        KiddoPaint.Sounds.lineStart()
    }

    override fun mousemove(ev: Ev) {
        if (tool.isDown) {
            if (tool.stomp) {
                KiddoPaint.Display.clearTmp()
            }
            KiddoPaint.Sounds.lineDuring()

            KiddoPaint.Display.context.beginPath()
            KiddoPaint.Display.context.moveTo(jsRound(tool.x), jsRound(tool.y))
            if (KiddoPaint.Current.modified) {
                val deltax = abs(ev._x - tool.x)
                val deltay = abs(ev._y - tool.y)
                if (deltax < deltay) {
                    KiddoPaint.Display.context.lineTo(tool.x, ev._y)
                } else {
                    KiddoPaint.Display.context.lineTo(ev._x, tool.y)
                }
            } else {
                KiddoPaint.Display.context.lineTo(ev._x, ev._y)
            }
            KiddoPaint.Display.context.strokeStyle = tool.texture(KiddoPaint.Current.color)
            KiddoPaint.Display.context.lineWidth = tool.size
            // while dragging: a cheap preview (Ctx2D.draft); the line kept at mouseup is drawn exactly
            KiddoPaint.Display.context.draft = tool.preview && tool.stomp // without stomp every move's stroke stays: exact
            KiddoPaint.Display.context.stroke()
            KiddoPaint.Display.context.draft = false
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
            KiddoPaint.Display.saveMain()
            KiddoPaint.Sounds.lineEnd()
        }
    }
}

val KiddoPaint.Tools.Line by world { ToolboxLine() }
