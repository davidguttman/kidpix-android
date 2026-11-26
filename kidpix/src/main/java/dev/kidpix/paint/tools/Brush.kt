@file:JvmName("tools_Brush")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.CanvasImageSource
import dev.kidpix.paint.engine.world
import kotlin.math.PI

// Port of js/tools/brush.js

class ToolboxBrush : Tool() {
    val tool = this
    var isDown = false
    var didMove = false
    var previousEv: Ev? = null
    var minDistance = 0.0
    var texture: (angle: Double) -> CanvasImageSource? = { null }
    var soundduring: (() -> Unit)? = null

    override fun mousedown(ev: Ev) {
        tool.isDown = true

        tool.didMove = true // put first click
        tool.mousemove(ev)
        tool.didMove = false // clear first click if need be

        tool.previousEv = ev
    }

    fun reset() {
        tool.soundduring = null
        tool.texture = { null }
    }

    override fun mousemove(ev: Ev) {
        if (tool.isDown) {
            if (!tool.didMove) {
                // just kidding! we're moving, so clear the first builder mark and ...
                KiddoPaint.Display.clearTmp()
                tool.didMove = true
                tool.previousEv = ev
                // ... start drawing the new builder as soon as possible.
                tool.minDistance = 0.0
            } else if (tool.previousEv == null || distanceBetween(tool.previousEv!!, ev) > tool.minDistance) {
                val angle = if (tool.previousEv == null) 0.0 else angleBetween(tool.previousEv!!, ev) + 0.5 * PI
                // JS: drawImage(undefined) throws a TypeError
                val brushFill = tool.texture(angle) ?: throw IllegalStateException("Brush texture returned undefined")
                tool.soundduring?.invoke()
                KiddoPaint.Display.context.drawImage(brushFill, jsRound(ev._x), jsRound(ev._y))
                tool.previousEv = ev
                // next builder should be spaced out
                tool.minDistance = 25 * KiddoPaint.Current.scaling
            }
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.isDown = false
            tool.previousEv = null
            tool.minDistance = 0.0
            KiddoPaint.Display.saveMain()
        }
    }
}

val KiddoPaint.Tools.Brush by world { ToolboxBrush() }
