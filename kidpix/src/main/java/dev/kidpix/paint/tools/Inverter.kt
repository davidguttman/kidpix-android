@file:JvmName("tools_Inverter")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.CanvasPattern
import dev.kidpix.paint.engine.world

// Port of js/tools/inverter.js

class ToolboxInverter : Tool() {
    val tool = this
    var isDown = false
    var size = 25.0
    var hiddenPattern: CanvasPattern? = null

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.hiddenPattern = KiddoPaint.Display.context.createPattern(
            Filters.gcoInvert(KiddoPaint.Display.main_context.getImageData(0, 0, KiddoPaint.Display.canvas.width, KiddoPaint.Display.canvas.height)),
            "no-repeat",
        )
    }

    override fun mousemove(ev: Ev) {
        val currentSize = tool.size * KiddoPaint.Current.scaling
        if (tool.isDown) {
            KiddoPaint.Sounds.brushinvert()
            val ctx = KiddoPaint.Display.context
            ctx.fillStyle = tool.hiddenPattern
            ctx.fillRect(jsRound(ev._x) - (currentSize / 2.0), jsRound(ev._y) - (currentSize / 2.0), currentSize, currentSize)
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.isDown = false
            tool.hiddenPattern = null
            KiddoPaint.Display.saveMain()
        }
    }
}

val KiddoPaint.Tools.Inverter by world { ToolboxInverter() }
