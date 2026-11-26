@file:JvmName("tools_Fuzzer")

package dev.kidpix.paint

import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.world

// Port of js/tools/fuzzer.js

class ToolboxFuzzer : Tool() {
    val tool = this
    var isDown = false
    var size: () -> Double = { 13 * KiddoPaint.Current.scaling }
    var timeout: Int? = null
    var currentEv: Ev? = null

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.currentEv = ev

        val interval = 37
        fun draw() {
            tool.toolDraw()
            if (tool.timeout == null) return
            tool.timeout = KTime.setTimeout({ draw() }, interval)
        }
        tool.timeout = KTime.setTimeout({ draw() }, interval)
    }

    override fun mousemove(ev: Ev) {
        tool.currentEv = ev
        // just preview
        if (!tool.isDown) {
            tool.toolDraw()
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.isDown = false
            KiddoPaint.Display.saveMain()
            if (tool.timeout != null) {
                KTime.clearTimeout(tool.timeout)
                tool.timeout = null
            }
        }
    }

    // kidpix implements this as a circle tool not rect
    fun toolDraw() {
        val cur = tool.currentEv!!
        val target = KiddoPaint.Display.main_context.getImageData(cur._x - tool.size(), cur._y - tool.size(), 2 * tool.size(), 2 * tool.size())
        val ctx = if (tool.isDown) KiddoPaint.Display.context else KiddoPaint.Display.previewContext
        val jitterx = getRandomFloat(-7.0, 7.0)
        val jittery = getRandomFloat(-7.0, 7.0)

        if (tool.isDown) KiddoPaint.Sounds.brushfuzzer()
        ctx.putImageData(target, (cur._x - tool.size()) + jitterx, (cur._y - tool.size()) + jittery)
    }
}

val KiddoPaint.Tools.Fuzzer by world { ToolboxFuzzer() }
