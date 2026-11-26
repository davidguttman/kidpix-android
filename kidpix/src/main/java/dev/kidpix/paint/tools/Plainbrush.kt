@file:JvmName("tools_Plainbrush")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world
import kotlin.math.cos
import kotlin.math.sin

// Port of js/tools/plainbrush.js

class ToolboxPlainBrush : Tool() {
    val tool = this
    var isDown = false
    var previousEv: Ev? = null
    var texture: (step: Double, pstep: Double) -> BrushFill? = { _, _ -> null }
    var preprocess: () -> Unit = {}
    var postprocess: () -> Unit = {}
    var soundduring: () -> Unit = {}
    var spacing = 5.0
    var step = 0.0 // local tool step which resets on mouse up
    var pstep = 0.0 // local persistent step which resets when invoker chooses; used in connect the dots
    var alwaysGapFill = false

    fun reset() {
        tool.isDown = false
        tool.previousEv = null
        tool.texture = { _, _ -> null }
        tool.preprocess = {}
        tool.postprocess = {}
        tool.soundduring = {}
        tool.step = 0.0
        tool.pstep = 0.0
        tool.alwaysGapFill = false
    }

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.mousemove(ev)
        tool.previousEv = ev
        tool.preprocess()
    }

    // JS: reading .brush of undefined throws a TypeError
    private fun fill(): BrushFill =
        tool.texture(tool.step, tool.pstep) ?: throw IllegalStateException("PlainBrush texture returned undefined")

    override fun mousemove(ev: Ev) {
        if (tool.isDown) {
            if (tool.previousEv == null || distanceBetween(tool.previousEv!!, ev) > tool.spacing) {
                if (KiddoPaint.Current.modifiedTilde) {
                    // alpha decay
                    KiddoPaint.Display.context.globalAlpha *= 0.95
                    KiddoPaint.Display.previewContext.globalAlpha *= 0.95
                }
                // gap fill
                if ((KiddoPaint.Current.modifiedAlt || tool.alwaysGapFill) && tool.previousEv != null) {
                    val prev = tool.previousEv!!
                    val dist = distanceBetween(prev, ev)
                    val angle = angleBetweenRad(prev, ev)
                    var i = 0.0
                    while (i < dist) {
                        val x = prev._x + (sin(angle) * i)
                        val y = prev._y + (cos(angle) * i)
                        val brushFill = fill()
                        val off = brushFill.offset ?: Double.NaN
                        KiddoPaint.Display.context.drawImage(brushFill.brush, jsRound(x - off), jsRound(y - off))
                        tool.step += 1
                        tool.pstep += 1
                        if (KiddoPaint.Current.modifiedTilde) {
                            // alpha decay
                            KiddoPaint.Display.context.globalAlpha *= 0.99
                            KiddoPaint.Display.previewContext.globalAlpha *= 0.99
                        }
                        i += 5
                    }
                }
                val brushFill = fill()
                tool.soundduring()
                val off = brushFill.offset ?: Double.NaN
                KiddoPaint.Display.context.drawImage(brushFill.brush, jsRound(ev._x - off), jsRound(ev._y - off))
                tool.previousEv = ev
                tool.step += 1
                tool.pstep += 1
            }
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.mousemove(ev)
            tool.isDown = false
            tool.previousEv = null
            tool.step = 0.0 // only reset local step, not pstep. caller invokes reset() if need be
            tool.postprocess()
            KiddoPaint.Display.saveMain()
            KiddoPaint.Display.context.globalAlpha = KiddoPaint.Current.globalAlpha
            KiddoPaint.Display.previewContext.globalAlpha = KiddoPaint.Current.globalAlpha
        }
    }
}

val KiddoPaint.Tools.PlainBrush by world { ToolboxPlainBrush() }
