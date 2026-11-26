@file:JvmName("tools_Three3d")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/tools/three3d.js

class ToolboxThreeDBrush : Tool() {
    val tool = this
    var isDown = false
    var size: () -> Double = { 16 * KiddoPaint.Current.scaling }
    var previousEv: Ev? = null
    var spacing = 3.0
    var texture: () -> Any? = {
        val shadecolor = if (colorNearWhite(color2json(KiddoPaint.Current.color))) "black" else "white"
        if (KiddoPaint.Current.modifiedAlt) {
            KiddoPaint.Textures.Bubbles(shadecolor)
        } else if (KiddoPaint.Current.modifiedCtrl) {
            KiddoPaint.Textures.Speckles(shadecolor)
        } else if (KiddoPaint.Current.modifiedMeta) {
            KiddoPaint.Textures.Sand(shadecolor)
        } else {
            KiddoPaint.Textures.Partial1(shadecolor)
        }
    }

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.mousemove(ev)
        tool.previousEv = ev
    }

    override fun mousemove(ev: Ev) {
        if (tool.isDown) {
            if (KiddoPaint.Current.modifiedToggle) {
                ev._x = ev._x - (ev._x % (tool.size()))
                ev._y = ev._y - (ev._y % (tool.size()))
            }
            if (tool.previousEv == null || distanceBetween(tool.previousEv!!, ev) < tool.spacing) {
                KiddoPaint.Display.context.fillStyle = KiddoPaint.Current.color
                KiddoPaint.Display.context.fillRect(jsRound(ev._x), jsRound(ev._y), tool.size(), tool.size())

                KiddoPaint.Display.context.fillStyle = tool.texture()
                KiddoPaint.Display.context.fillRect(jsRound(ev._x), jsRound(ev._y), tool.size() / 2, tool.size() / 2)
            } else {
                // events carry whole pixels, so the JS bitwise math sees integers too
                bresenham(tool.previousEv!!._x.toInt(), tool.previousEv!!._y.toInt(), ev._x.toInt(), ev._y.toInt()) { lx, ly ->
                    KiddoPaint.Display.context.fillStyle = KiddoPaint.Current.color
                    KiddoPaint.Display.context.fillRect(lx, ly, tool.size(), tool.size())

                    KiddoPaint.Display.context.fillStyle = tool.texture()
                    KiddoPaint.Display.context.fillRect(lx, ly, tool.size() / 2, tool.size() / 2)
                }
            }
            tool.previousEv = ev
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.mousemove(ev)
            tool.isDown = false
            tool.previousEv = null
            KiddoPaint.Display.saveMain()
        }
    }
}

val KiddoPaint.Tools.ThreeDBrush by world { ToolboxThreeDBrush() }
