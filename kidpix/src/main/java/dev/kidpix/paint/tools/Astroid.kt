@file:JvmName("tools_Astroid")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/tools/astroid.js

class ToolboxAstroid : Tool() {
    val tool = this
    var size = 1.0
    var stroke: () -> Any? = { KiddoPaint.Current.color }
    var points = mutableListOf<XY>()

    // http://mathworld.wolfram.com/Astroid.html
    fun drawAstroid(pt1: XY, pt2: XY, pt3: XY) {
        val interval = 37 * KiddoPaint.Current.scaling

        val seg1deltax = (pt2.x - pt1.x) / interval
        val seg1deltay = (pt2.y - pt1.y) / interval

        val seg2deltax = (pt3.x - pt2.x) / interval
        val seg2deltay = (pt3.y - pt2.y) / interval

        var i = 0
        while (i <= interval) {
            val a1 = XY(pt1.x + (seg1deltax * i), pt1.y + (seg1deltay * i))
            val a2 = XY(pt2.x + (seg2deltax * i), pt2.y + (seg2deltay * i))
            KiddoPaint.Display.context.beginPath()
            KiddoPaint.Display.context.lineWidth = tool.size
            KiddoPaint.Display.context.moveTo(jsRound(a1.x), jsRound(a1.y))
            KiddoPaint.Display.context.lineTo(jsRound(a2.x), jsRound(a2.y))
            if (KiddoPaint.Current.modifiedMeta) {
                KiddoPaint.Display.context.strokeStyle = KiddoPaint.Colors.randomColor()
            } else if (KiddoPaint.Current.modifiedCtrl) {
                KiddoPaint.Display.context.strokeStyle = (if (i % 2 != 0) KiddoPaint.Current.color else KiddoPaint.Current.altColor)
            } else {
                KiddoPaint.Display.context.strokeStyle = KiddoPaint.Current.color
            }
            KiddoPaint.Display.context.stroke()
            KiddoPaint.Display.context.closePath()
            i++
        }
    }

    override fun mousedown(ev: Ev) {
        KiddoPaint.Sounds.xyStart()
        tool.points.add(XY(ev._x, ev._y))
    }

    override fun mousemove(ev: Ev) {
        KiddoPaint.Display.clearTmp()
        if (tool.points.size == 1) {
            KiddoPaint.Sounds.xyDuring()
            KiddoPaint.Display.context.beginPath()
            KiddoPaint.Display.context.moveTo(jsRound(tool.points[0].x), jsRound(tool.points[0].y))
            KiddoPaint.Display.context.lineTo(ev._x, ev._y)
            KiddoPaint.Display.context.strokeStyle = tool.stroke()
            KiddoPaint.Display.context.lineWidth = tool.size
            KiddoPaint.Display.context.stroke()
            KiddoPaint.Display.context.closePath()
        } else if (tool.points.size == 2) {
            KiddoPaint.Sounds.xyDuring()
            tool.drawAstroid(tool.points[0], tool.points[1], XY(ev._x, ev._y))
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.points.size == 3) {
            KiddoPaint.Sounds.xyEnd()
            KiddoPaint.Display.clearTmp()
            tool.drawAstroid(tool.points[0], tool.points[1], XY(ev._x, ev._y))
            tool.points = mutableListOf()
            KiddoPaint.Display.saveMain()
        }
    }
}

val KiddoPaint.Tools.Astroid by world { ToolboxAstroid() }
