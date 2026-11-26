@file:JvmName("tools_Contours")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/tools/contours.js

class ToolboxContours : Tool() {
    val tool = this
    var isDown = false
    var size = 2.0
    var x = 0.0
    var y = 0.0
    var stroke: () -> Any? = {
        KiddoPaint.Textures.Solid(if (KiddoPaint.Current.modified) KiddoPaint.Colors.randomColor() else KiddoPaint.Current.color)
    }

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.x = ev._x
        tool.y = ev._y
    }

    override fun mousemove(ev: Ev) {
        if (tool.isDown) {
            KiddoPaint.Sounds.brushnorthern()
            KiddoPaint.Display.context.beginPath()
            KiddoPaint.Display.context.strokeStyle = tool.stroke()
            KiddoPaint.Display.context.lineWidth = tool.size
            if (KiddoPaint.Current.modifiedAlt) KiddoPaint.Display.context.moveTo(tool.x, ev._y) else KiddoPaint.Display.context.moveTo(ev._x, tool.y)
            KiddoPaint.Display.context.lineTo(ev._x, ev._y)
            KiddoPaint.Display.context.stroke()
            KiddoPaint.Display.context.closePath()
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.mousemove(ev)
            tool.isDown = false
            KiddoPaint.Display.saveMain()
        }
    }
}

val KiddoPaint.Tools.Contours by world { ToolboxContours() }
