@file:JvmName("tools_WackyMixerCheckerboard")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/tools/wacky-mixer-checkerboard.js

class ToolboxWackyMixerCheckerboard : Tool() {
    val tool = this

    override fun mousedown(ev: Ev) {
        val ctx = KiddoPaint.Display.context
        ctx.fillStyle = KiddoPaint.Textures.BigGrid()
        ctx.fillRect(0, 0, KiddoPaint.Display.canvas.width, KiddoPaint.Display.canvas.height)
        KiddoPaint.Display.saveMainGco("difference")
    }

    override fun mousemove(ev: Ev) {}

    override fun mouseup(ev: Ev) {}
}

val KiddoPaint.Tools.WackyMixerCheckerboard by world { ToolboxWackyMixerCheckerboard() }
