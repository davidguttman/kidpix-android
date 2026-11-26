@file:JvmName("tools_WackyMixerVenetianBlinds")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.ImageData
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.world

// Port of js/tools/wacky-mixer-venetian-blinds.js

class ToolboxWackyMixerVenetianBlinds : Tool() {
    val tool = this
    var isDown = false
    var animInterval = 100.0
    var timeout: Int? = null
    var currentEv: Ev? = null

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.currentEv = ev
        KiddoPaint.Display.context.save()
        mixerCursor("cursor-guy-wow")
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
        tool.currentEv = ev
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.isDown = false
            KiddoPaint.Display.context.restore()
            mixerCursor("cursor-guy-smile")
            if (tool.timeout != null) {
                KTime.clearTimeout(tool.timeout)
                tool.timeout = null
            }
            KiddoPaint.Display.clearAnim()
            KiddoPaint.Display.clearBeforeSaveMain()
        }
    }

    fun toolDraw() {
        if (tool.isDown) {
            KiddoPaint.Sounds.mixervenetian()
            // alpha hide hack
            KiddoPaint.Display.animContext.fillStyle = "white"
            KiddoPaint.Display.animContext.fillRect(0, 0, KiddoPaint.Display.main_canvas.width, KiddoPaint.Display.main_canvas.height)

            val rwidth = KiddoPaint.Display.canvas.width.toDouble()
            val rheight = KiddoPaint.Display.canvas.height / 10.0

            val blinds = ArrayList<ImageData>()
            for (i in 0 until 10) {
                val sourceImage = KiddoPaint.Display.main_context.getImageData(0, rheight * i, rwidth, rheight)
                blinds.add(sourceImage)
            }

            fisherYatesArrayShuffle(blinds)

            for (i in 0 until 10) {
                KiddoPaint.Display.context.putImageData(blinds[i], 0, rheight * i)
            }
        }
    }
}

val KiddoPaint.Tools.WackyMixerVenetianBlinds by world { ToolboxWackyMixerVenetianBlinds() }
