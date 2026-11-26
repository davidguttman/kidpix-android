@file:JvmName("tools_Eraser")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/tools/eraser.js

class ToolboxEraser : Tool() {
    val tool = this
    var isDown = false
    var size = 10.0
    var isSquareEraser = true
    var texture: () -> Any? = {
        KiddoPaint.Textures.Solid("rgb(240, 180, 180)")
    }

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.mousemove(ev)
    }

    override fun mousemove(ev: Ev) {
        val currentSize = tool.size * KiddoPaint.Current.scaling
        val ctx = if (tool.isDown) KiddoPaint.Display.context else KiddoPaint.Display.previewContext
        ctx.fillStyle = tool.texture()
        if (tool.isSquareEraser) {
            ctx.fillRect(jsRound(ev._x) - (currentSize / 2.0), jsRound(ev._y) - (currentSize / 2.0), currentSize, currentSize)
        } else {
            ctx.beginPath()
            ctx.arc(ev._x, ev._y, currentSize, 0, 2 * Math.PI)
            ctx.fill()
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.mousemove(ev)
            tool.isDown = false
            KiddoPaint.Display.saveMainGco("destination-out")
        }
    }
}

val KiddoPaint.Tools.Eraser by world { ToolboxEraser() }
