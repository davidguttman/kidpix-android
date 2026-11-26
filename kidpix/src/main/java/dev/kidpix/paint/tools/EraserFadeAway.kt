@file:JvmName("tools_EraserFadeAway")

package dev.kidpix.paint

import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.world

// Port of js/tools/eraser-fade-away.js

class ToolboxEraserFadeAway : Tool() {
    val tool = this

    override fun mousedown(ev: Ev) {
        val ctx = KiddoPaint.Display.context
        KTime.setTimeout({
            KiddoPaint.Sounds.eraserfadeb()
            ctx.fillStyle = KiddoPaint.Textures.Screen1()
            ctx.fillRect(0, 0, KiddoPaint.Display.canvas.width, KiddoPaint.Display.canvas.height)
        }, 500)
        KTime.setTimeout({
            KiddoPaint.Sounds.eraserfadea()
            ctx.fillStyle = KiddoPaint.Textures.Screen2()
            ctx.fillRect(0, 0, KiddoPaint.Display.canvas.width, KiddoPaint.Display.canvas.height)
            ctx.fillStyle = KiddoPaint.Textures.Screen3()
            ctx.fillRect(0, 0, KiddoPaint.Display.canvas.width, KiddoPaint.Display.canvas.height)
        }, 1200)
        KTime.setTimeout({
            KiddoPaint.Sounds.eraserfadeb()
            ctx.fillStyle = KiddoPaint.Textures.Screen4()
            ctx.fillRect(0, 0, KiddoPaint.Display.canvas.width, KiddoPaint.Display.canvas.height)
        }, 1900)
        KTime.setTimeout({
            KiddoPaint.Display.clearAll()
        }, 2000)
    }

    override fun mousemove(ev: Ev) {}
    override fun mouseup(ev: Ev) {}
}

val KiddoPaint.Tools.EraserFadeAway by world { ToolboxEraserFadeAway() }
