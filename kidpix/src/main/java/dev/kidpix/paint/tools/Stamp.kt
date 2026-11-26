@file:JvmName("tools_Stamp")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.engine.world

class ToolboxStamp : Tool() {
    val tool = this
    var isDown = false
    var stamp = "🚂"
    var size = 64.0
    var useColor = false
    var seed = 1

    fun texture(): KCanvas {
        var altSize = KiddoPaint.Cache.getStampSettings(tool.stamp).altSize
        if (KiddoPaint.Current.modifiedRange != 0) {
            val modifiedSize = KiddoPaint.Current.modifiedRange + 112.0
            KiddoPaint.Cache.setStampSetting(tool.stamp, "altSize", modifiedSize)
            altSize = modifiedSize
        }
        tool.size = if (KiddoPaint.Current.modified) altSize else 64.0

        var hueShift = KiddoPaint.Cache.getStampSettings(tool.stamp).hueShift
        if (KiddoPaint.Current.modifiedCtrlRange != 0) {
            val modifiedHue = KiddoPaint.Current.modifiedCtrlRange / 100.0
            KiddoPaint.Cache.setStampSetting(tool.stamp, "hueShift", modifiedHue)
            hueShift = modifiedHue
        }

        return KiddoPaint.Stamps.stamp(tool.stamp, KiddoPaint.Current.modifiedAlt, KiddoPaint.Current.modifiedMeta, tool.size, hueShift, if (tool.useColor) KiddoPaint.Current.color else null)
    }

    override fun mousedown(ev: Ev) {
        val rng = srng(tool.seed)
        tool.isDown = true
        KiddoPaint.Sounds.stamp()

        KiddoPaint.Display.context.fillStyle = if (tool.useColor) KiddoPaint.Current.color else null
        val brushFill = tool.texture()
        KiddoPaint.Display.context.drawImage(brushFill, jsRound(ev._x), jsRound(ev._y - tool.size))
        if (KiddoPaint.Current.multiplier > 1) {
            for (i in 0 until 2 * KiddoPaint.Current.multiplier) {
                val x = jsRound(ev._x + (1000 * rng.next() - 500))
                val y = jsRound(ev._y + (20 * rng.next() - 10) - tool.size)
                KiddoPaint.Display.context.drawImage(brushFill, x, y)
            }
        }
    }

    override fun mousemove(ev: Ev) {
        val rng = srng(tool.seed)
        if (!tool.isDown) {
            KiddoPaint.Display.previewContext.fillStyle = if (tool.useColor) KiddoPaint.Current.color else null
            val brushFill = tool.texture()
            KiddoPaint.Display.previewContext.drawImage(brushFill, jsRound(ev._x), jsRound(ev._y - tool.size))
            if (KiddoPaint.Current.multiplier > 1) {
                for (i in 0 until 2 * KiddoPaint.Current.multiplier) {
                    val x = jsRound(ev._x + (1000 * rng.next() - 500))
                    val y = jsRound(ev._y + (20 * rng.next() - 10) - tool.size)
                    KiddoPaint.Display.previewContext.drawImage(brushFill, x, y)
                }
            }
        }
    }

    override fun mouseup(ev: Ev) {
        tool.isDown = false
        tool.seed += 1
        KiddoPaint.Display.saveMain()
    }
}

val KiddoPaint.Tools.Stamp by world { ToolboxStamp() }
