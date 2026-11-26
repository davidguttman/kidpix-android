@file:JvmName("tools_AnimBrush")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.CanvasImageSource
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.world

// Port of js/tools/animbrush.js

/**
 * What a brush function returns in the JS: `{brush: canvas, offset: n}` (or `abspos: {x, y}`
 * instead of offset; some also set `inplace: true`). `offset == null` is JS `undefined`.
 */
class BrushFill(
    val brush: CanvasImageSource,
    val offset: Double? = null,
    val abspos: XY? = null,
    val inplace: Boolean = false,
)

/** JS truthiness of a number (`if (brushFill.offset)`). */
internal fun jsTruthy(d: Double?): Boolean = d != null && d != 0.0 && !d.isNaN()

class ToolboxAnimBrush : Tool() {
    val tool = this
    var isDown = false
    var previousEv: Ev? = null
    var currentEv: Ev? = null
    var distanceFromPrev: Double? = null
    var texture: (step: Double, distancePrev: Double?) -> BrushFill? = { _, _ -> null }
    var preprocess: () -> Unit = {}
    var postprocess: () -> Unit = {}
    var step = 0.0
    var animInterval = 30.0
    var timeout: Int? = null

    fun reset() {
        tool.isDown = false
        tool.currentEv = null
        tool.previousEv = null
        tool.distanceFromPrev = null
        tool.texture = { _, _ -> null }
        tool.preprocess = {}
        tool.postprocess = {}
        tool.step = 0.0
        tool.animInterval = 30.0
        if (tool.timeout != null) {
            KTime.clearTimeout(tool.timeout)
            tool.timeout = null
        }
    }

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.preprocess()
        tool.currentEv = ev
        val interval = tool.animInterval
        fun draw() {
            tool.toolDraw()
            if (tool.timeout == null) return
            tool.timeout = KTime.setTimeout({ draw() }, interval)
        }
        tool.timeout = KTime.setTimeout({ draw() }, interval)
        tool.toolDraw()
    }

    override fun mousemove(ev: Ev) {
        if (tool.isDown) {
            tool.distanceFromPrev = if (tool.previousEv != null) distanceBetween(tool.previousEv!!, ev) else Double.MAX_VALUE
            tool.previousEv = tool.currentEv
            tool.currentEv = ev
            tool.toolDraw()
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.postprocess()
            tool.isDown = false
            if (tool.timeout != null) {
                KTime.clearTimeout(tool.timeout)
                tool.timeout = null
            }
            tool.currentEv = null
            tool.previousEv = null
            tool.distanceFromPrev = null
            tool.step = 0.0
            KiddoPaint.Display.saveMain()
        }
    }

    fun toolDraw() {
        if (tool.isDown) {
            val ev = tool.currentEv!!
            // JS: texture() returning undefined throws on .offset; the reset texture does that
            val brushFill = tool.texture(tool.step, tool.distanceFromPrev)
                ?: throw IllegalStateException("AnimBrush texture returned undefined")
            var lx = ev._x
            var ly = ev._y
            if (jsTruthy(brushFill.offset)) {
                lx = lx - brushFill.offset!!
                ly = ly - brushFill.offset
            } else if (brushFill.abspos != null) {
                lx = brushFill.abspos.x
                ly = brushFill.abspos.y
            }
            KiddoPaint.Display.context.drawImage(brushFill.brush, lx, ly)
            tool.step += 1
        }
    }
}

val KiddoPaint.Tools.AnimBrush by world { ToolboxAnimBrush() }
