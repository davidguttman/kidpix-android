@file:JvmName("tools_Looper")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world
import kotlin.math.cos
import kotlin.math.sin

// Port of js/tools/looper.js

class ToolboxLooper : Tool() {
    val tool = this
    var isDown = false
    var size = 5.0
    var radius = 32.0
    var lstep = 0.0
    var lincr = 0.15
    var previousCoord: XY? = null
    var stroke: () -> Any? = {
        KiddoPaint.Textures.Solid(if (KiddoPaint.Current.modifiedMeta) KiddoPaint.Colors.randomColor() else KiddoPaint.Current.color)
    }

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.previousCoord = XY(ev._x + tool.radius * sin(-tool.lstep), ev._y + tool.radius * cos(tool.lstep))
    }

    override fun mousemove(ev: Ev) {
        if (tool.isDown) {
            val x = ev._x + tool.radius * sin(-tool.lstep)
            val y = ev._y + tool.radius * cos(tool.lstep)

            KiddoPaint.Display.context.beginPath()
            KiddoPaint.Display.context.strokeStyle = tool.stroke()
            KiddoPaint.Display.context.lineWidth = tool.size
            KiddoPaint.Display.context.lineCap = "round"
            KiddoPaint.Display.context.lineJoin = "round"
            KiddoPaint.Display.context.moveTo(tool.previousCoord!!.x, tool.previousCoord!!.y)
            KiddoPaint.Display.context.lineTo(x, y)
            KiddoPaint.Display.context.stroke()
            KiddoPaint.Display.context.closePath()

            tool.lstep += tool.lincr

            tool.previousCoord = XY(x, y)
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

val KiddoPaint.Tools.Looper by world { ToolboxLooper() }
