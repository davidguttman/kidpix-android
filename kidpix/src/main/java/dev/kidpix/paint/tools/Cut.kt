@file:JvmName("tools_Cut")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.ImageData
import dev.kidpix.paint.engine.world

// Port of js/tools/cut.js
//
// The JS assigns `sizex`/`sizey` without `var` (implicit globals); here they are locals.

class SizeXY(val x: Double, val y: Double)

class ToolboxCut : Tool() {
    val tool = this
    var isDown = false
    var length = 50.0
    var width = 50.0
    var x = 0.0
    var y = 0.0
    fun size(): SizeXY {
        val lx = tool.length
        val ly = tool.width
        return SizeXY(
            x = lx * KiddoPaint.Current.scaling * KiddoPaint.Current.multiplier * ((KiddoPaint.Current.modifiedCtrlRange + 100) / 100.0),
            y = ly * KiddoPaint.Current.scaling * KiddoPaint.Current.multiplier * ((KiddoPaint.Current.modifiedAltRange + 100) / 100.0),
        )
    }
    var stomp = true
    var selectedData: ImageData? = null

    var texture: () -> Any? = {
        KiddoPaint.Textures.None()
    }

    override fun mousedown(ev: Ev) {
        KiddoPaint.Sounds.truckStart()
        tool.isDown = true
        tool.x = ev._x
        tool.y = ev._y
        val sizex = tool.size().x
        val sizey = tool.size().y
        if (!KiddoPaint.Current.modifiedToggle) {
            // save previous capture to reuse; click and drag to reinstantiate
            tool.selectedData = KiddoPaint.Display.main_context.getImageData(ev._x - sizex, ev._y - sizey, 2 * sizex, 2 * sizey)
        }
        tool.mousemove(ev)
    }

    override fun mousemove(ev: Ev) {
        val sizex = tool.size().x
        val sizey = tool.size().y

        if (tool.stomp) {
            KiddoPaint.Display.clearTmp()
        }

        val selectedData = tool.selectedData
        if ((tool.isDown || KiddoPaint.Current.modifiedToggle) && selectedData != null) {
            KiddoPaint.Sounds.truckDuring()
            if (!KiddoPaint.Current.modifiedMeta) { // preview what a cut will look like
                KiddoPaint.Display.animContext.fillStyle = "white"
                KiddoPaint.Display.animContext.fillRect(tool.x - sizex, tool.y - sizey, 2 * sizex, 2 * sizey)
            } else {
                KiddoPaint.Display.clearAnim() // cut preview if they change mind show underlying
            }
            KiddoPaint.Display.previewContext.putImageData(selectedData, ev._x - sizex, ev._y - sizey)
        } else {
            KiddoPaint.Display.previewContext.strokeStyle = "white"
            KiddoPaint.Display.previewContext.lineWidth = 0.5
            KiddoPaint.Display.previewContext.setLineDash(if (KiddoPaint.Display.step % 2 != 0) listOf(4) else listOf(2))
            KiddoPaint.Display.previewContext.strokeRect(ev._x - sizex, ev._y - sizey, 2 * sizex, 2 * sizey)
            KiddoPaint.Display.previewContext.strokeStyle = "black"
            KiddoPaint.Display.previewContext.strokeRect(ev._x - sizex, ev._y - sizey, 2 * sizex + 1, 2 * sizey + 1)
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            KiddoPaint.Sounds.truckEnd()
            val sizex = tool.size().x
            val sizey = tool.size().y
            tool.isDown = false
            tool.selectedData?.let { KiddoPaint.Display.context.putImageData(it, ev._x - sizex, ev._y - sizey) }

            // Since we're manipulating main_context and we need to do it between undo() and cleartmp(), we dupe
            // and peek into abstraction here
            // KiddoPaint.Display.saveMain();
            KiddoPaint.Display.saveUndo()
            if (!KiddoPaint.Current.modifiedMeta) { // actually do the cut; and before placing the new pixels
                KiddoPaint.Display.main_context.clearRect(tool.x - sizex, tool.y - sizey, 2 * sizex, 2 * sizey)
            }
            KiddoPaint.Display.main_context.drawImage(KiddoPaint.Display.canvas, 0, 0)
            KiddoPaint.Display.clearTmp()
            KiddoPaint.Display.clearAnim() // cut preview
            KiddoPaint.Display.saveToLocalStorage()
        }
    }
}

val KiddoPaint.Tools.Cut by world { ToolboxCut() }
