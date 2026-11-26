@file:JvmName("tools_WackyMixerPip")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/tools/wacky-mixer-pip.js

class ToolboxElectricMixerPip : Tool() {
    val tool = this
    var isDown = false

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        KiddoPaint.Sounds.mixerpip()
    }

    override fun mousemove(ev: Ev) {}

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            KiddoPaint.Sounds.mixershadowbox()
            tool.isDown = false
            val target = KiddoPaint.Display.main_context.getImageData(0, 0, KiddoPaint.Display.canvas.width, KiddoPaint.Display.canvas.height)
            KiddoPaint.Tools.Placer.image = KiddoPaint.Display.imageTypeToCanvas(scaleImageData(target, 1.0 / 5.0))
            KiddoPaint.Tools.Placer.size = Size(
                width = KiddoPaint.Display.canvas.width / 5.0,
                height = KiddoPaint.Display.canvas.width / 5.0,
            )
            KiddoPaint.Current.tool = KiddoPaint.Tools.Placer
            KiddoPaint.Tools.Placer.prevTool = KiddoPaint.Tools.ElectricMixerPip
        }
    }
}

val KiddoPaint.Tools.ElectricMixerPip by world { ToolboxElectricMixerPip() }
