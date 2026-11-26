@file:JvmName("tools_Magnify")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/tools/magnify.js

class ToolboxMagnify : Tool() {
    val tool = this
    var isDown = false
    var size: () -> Double = { 36 * KiddoPaint.Current.scaling }
    var scale = 2.0

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.mousemove(ev)
        setCanvasCursor("cursor-none")
    }

    override fun mousemove(ev: Ev) {
        if (tool.isDown) {
            KiddoPaint.Sounds.brushzoom()
            val target = KiddoPaint.Display.main_context.getImageData(ev._x - tool.size(), ev._y - tool.size(), 2 * tool.size(), 2 * tool.size())
            val ctx = KiddoPaint.Display.previewContext
            val scaled = scaleImageData(target, tool.scale)
            ctx.putImageData(scaled, ev._x - (tool.scale * tool.size()), ev._y - (tool.scale * tool.size()))
            KiddoPaint.Display.clearAnim()
            KiddoPaint.Display.animContext.fillStyle = "white"
            KiddoPaint.Display.animContext.fillRect(ev._x - (tool.scale * tool.size()), ev._y - (tool.scale * tool.size()), tool.scale * tool.size() * 2, tool.scale * tool.size() * 2)
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.isDown = false
            KiddoPaint.Display.clearAnim()
            KiddoPaint.Display.saveUndo()

            val target = KiddoPaint.Display.main_context.getImageData(ev._x - tool.size(), ev._y - tool.size(), 2 * tool.size(), 2 * tool.size())
            val ctx = KiddoPaint.Display.context
            val scaled = scaleImageData(target, tool.scale)
            ctx.putImageData(scaled, ev._x - (tool.scale * tool.size()), ev._y - (tool.scale * tool.size()))
            KiddoPaint.Display.main_context.clearRect(ev._x - (tool.scale * tool.size()), ev._y - (tool.scale * tool.size()), tool.scale * tool.size() * 2, tool.scale * tool.size() * 2)

            // break abstraction since we're clearing main underneath
            KiddoPaint.Display.main_context.drawImage(KiddoPaint.Display.canvas, 0, 0)
            KiddoPaint.Display.clearTmp()
            KiddoPaint.Display.clearAnim() // cut preview
            KiddoPaint.Display.saveToLocalStorage()
            setCanvasCursor("cursor-paint-brush")
        }
    }
}

val KiddoPaint.Tools.Magnify by world { ToolboxMagnify() }
