@file:JvmName("tools_Pixelpencil")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/tools/pixelpencil.js

class ToolboxPencil : Tool() {
    val tool = this
    var isDown = false
    var lastX = 0.0
    var lastY = 0.0
    var size = 7.0

    var texture: (String) -> Any? = { color ->
        KiddoPaint.Textures.Solid(color)
    }

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.lastX = ev._x
        tool.lastY = ev._y
    }

    override fun mousemove(ev: Ev) {
        if (tool.isDown) {
            KiddoPaint.Sounds.pencil()
            KiddoPaint.Display.context.beginPath()
            KiddoPaint.Display.context.strokeStyle = tool.texture(KiddoPaint.Current.color)
            KiddoPaint.Display.context.lineWidth = tool.size * KiddoPaint.Current.scaling
            KiddoPaint.Display.context.lineCap = "round"
            KiddoPaint.Display.context.lineJoin = "round"
            KiddoPaint.Display.context.moveTo(tool.lastX, tool.lastY)
            KiddoPaint.Display.context.lineTo(ev._x, ev._y)
            KiddoPaint.Display.context.stroke()
            tool.lastX = ev._x
            tool.lastY = ev._y
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.mousemove(ev)
            KiddoPaint.Display.context.closePath()
            tool.isDown = false
            KiddoPaint.Display.saveMain()
        }
    }
}

val KiddoPaint.Tools.Pencil by world { ToolboxPencil() }
