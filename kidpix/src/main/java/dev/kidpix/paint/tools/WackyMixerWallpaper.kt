@file:JvmName("tools_WackyMixerWallpaper")

package dev.kidpix.paint

import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.world

// Port of js/tools/wacky-mixer-wallpaper.js

class ToolboxWackyMixerWallpaper : Tool() {
    val tool = this
    var isDown = false
    var animInterval = 50.0
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
            KiddoPaint.Sounds.mixerwallpaper()
            // alpha hide hack
            KiddoPaint.Display.animContext.fillStyle = "white"
            KiddoPaint.Display.animContext.fillRect(0, 0, KiddoPaint.Display.main_canvas.width, KiddoPaint.Display.main_canvas.height)

            // random source
            val rx = tool.currentEv!!._x
            val ry = tool.currentEv!!._y

            val rwidth = KiddoPaint.Display.canvas.width / 3.0
            val rheight = KiddoPaint.Display.canvas.height / 3.0

            val sourceImage = KiddoPaint.Display.main_context.getImageData(rx, ry, rwidth, rheight)

            for (i in 0 until 3) {
                for (j in 0 until 3) {
                    // kidpix has this jitter which decays the edges....
                    KiddoPaint.Display.context.putImageData(sourceImage, (rwidth * i) + getRandomInt(1, 4), (rheight * j) + getRandomInt(1, 4))
                }
            }
        }
    }
}

val KiddoPaint.Tools.WackyMixerWallpaper by world { ToolboxWackyMixerWallpaper() }
