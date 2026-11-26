@file:JvmName("tools_Pines")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/tools/pines.js

class ToolboxPines : Tool() {
    val tool = this
    var isDown = false
    var previousEv: Ev? = null
    var texture: () -> Any? = {
        KiddoPaint.Textures.Solid(KiddoPaint.Current.color)
    }
    var spacing = 3.0
    var strokeSize = 1.0
    var boundingBox = 25.0
    var jitter: () -> Double = {
        getRandomFloat(-tool.boundingBox, tool.boundingBox)
    }

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.previousEv = ev
    }

    override fun mousemove(ev: Ev) {
        if (tool.isDown) {
            if (tool.previousEv == null || distanceBetween(tool.previousEv!!, ev) > tool.spacing) {
                KiddoPaint.Sounds.brushpines()
                val jitterx = tool.jitter()
                val jittery = tool.jitter()
                for (i in 0 until 7) {
                    KiddoPaint.Display.context.beginPath()
                    KiddoPaint.Display.context.moveTo(ev._x, ev._y)
                    KiddoPaint.Display.context.lineTo(tool.previousEv!!._x + jitterx, tool.previousEv!!._y + jittery)
                    KiddoPaint.Display.context.strokeStyle = tool.texture()
                    KiddoPaint.Display.context.lineWidth = tool.strokeSize
                    KiddoPaint.Display.context.stroke()
                    KiddoPaint.Display.context.closePath()
                }
                tool.previousEv = ev
            }
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

val KiddoPaint.Tools.Pines by world { ToolboxPines() }
