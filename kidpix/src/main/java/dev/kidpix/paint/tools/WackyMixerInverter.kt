@file:JvmName("tools_WackyMixerInverter")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.ImageData
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.world

// Port of js/tools/wacky-mixer-inverter.js

class ToolboxElectricMixerInvert : Tool() {
    val tool = this
    var isDown = false
    var leftside: ImageData? = null
    var rightside: ImageData? = null

    override fun mousedown(ev: Ev) {
        tool.isDown = true

        tool.leftside = KiddoPaint.Display.main_context.getImageData(0, 0, KiddoPaint.Display.main_canvas.width / 2.0, KiddoPaint.Display.main_canvas.height)
        tool.rightside = KiddoPaint.Display.main_context.getImageData(KiddoPaint.Display.main_canvas.width / 2.0, 0, KiddoPaint.Display.main_canvas.width / 2.0, KiddoPaint.Display.main_canvas.height)
        tool.animate(ev)
    }

    override fun mousemove(ev: Ev) {}

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.isDown = false
            tool.leftside = null
            tool.rightside = null
        }
    }

    fun animate(ev: Ev) {
        var iter = 1
        val right = flattenImage(tool.rightside!!)
        val left = flattenImage(tool.leftside!!)

        KiddoPaint.Display.animContext.putImageData(left, 0, 0)
        KiddoPaint.Display.animContext.putImageData(right, KiddoPaint.Display.main_canvas.width / 2.0, 0)

        KiddoPaint.Tools.WholeCanvasEffect.effect = JumbleFx.INVERT
        KiddoPaint.Tools.WholeCanvasEffect.mousedown(ev)
        KiddoPaint.Tools.WholeCanvasEffect.mouseup(ev)

        KiddoPaint.Sounds.mixerinvert() // estimated duration: 1.393107 sec

        var intervalID = 0

        fun drawSlideOut() {
            val totalIter = 70 // 1393 / 20
            val step = (KiddoPaint.Display.main_canvas.width / 2.0) / totalIter

            KiddoPaint.Display.clearAnim()
            KiddoPaint.Display.animContext.putImageData(left, 0 - (iter * step), 0)
            KiddoPaint.Display.animContext.putImageData(right, (KiddoPaint.Display.main_canvas.width / 2.0) + (iter * step), 0)
            iter++
            if (iter > totalIter) {
                KTime.clearInterval(intervalID)
                KiddoPaint.Display.clearAnim()
            }
        }

        intervalID = KTime.setInterval({ drawSlideOut() }, 20) // 20ms frames
        drawSlideOut()
    }
}

val KiddoPaint.Tools.ElectricMixerInvert by world { ToolboxElectricMixerInvert() }
