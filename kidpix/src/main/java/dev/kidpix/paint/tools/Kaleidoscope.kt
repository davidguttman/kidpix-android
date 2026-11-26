@file:JvmName("tools_Kaleidoscope")

package dev.kidpix.paint

import dev.kidpix.paint.engine.world

// Port of js/tools/kaleidoscope.js

class ToolboxKaleidoscope : Tool() {
    // bug: undo does one quadrant
    val tool = this
    var isDown = false
    var size = 2.0
    var origin: Ev = Ev()
    var previousEv = XY(0.0, 0.0)
    var texture: () -> Any? = {
        KiddoPaint.Textures.Solid(KiddoPaint.Current.color)
    }

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.previousEv = XY(0.0, 0.0)
        tool.origin = ev
        KiddoPaint.Display.context.strokeStyle = tool.texture()
        KiddoPaint.Display.context.lineWidth = tool.size
        KiddoPaint.Display.context.beginPath()
        KiddoPaint.Display.context.save()
        KiddoPaint.Display.context.lineCap = "round"
        KiddoPaint.Display.context.lineJoin = "round"
        KiddoPaint.Display.context.translate(ev._x + 0.1, ev._y + 0.1)
        KiddoPaint.Display.context.moveTo(0, 0)
    }

    override fun mousemove(ev: Ev) {
        if (tool.isDown) {
            KiddoPaint.Sounds.brushkaliediscope()
            val x = tool.origin._x - ev._x
            val y = tool.origin._y - ev._y
            val p = tool.previousEv
            val ctx = KiddoPaint.Display.context

            // Upstream adds these four segments to one growing path and strokes all of it on every
            // move (cost grows with the stroke). The path is only separate segments in an opaque
            // colour, so stroking just the new ones paints the same pixels: each move strokes its own
            // four segments (thin round segments, sampled directly: Ctx2D/MsaaSurface).
            if (KiddoPaint.Current.modifiedAlt) {
                seg(ctx, p.x, p.y, x, y)
                seg(ctx, p.y, p.x, y, x)
                seg(ctx, -p.x, -p.y, -x, -y)
                seg(ctx, -p.y, -p.x, -y, -x)
            } else {
                seg(ctx, p.x, p.y, x, y)
                seg(ctx, -p.x, p.y, -x, y)
                seg(ctx, p.x, -p.y, x, -y)
                seg(ctx, -p.x, -p.y, -x, -y)
            }
            tool.previousEv = XY(x, y)
        }
    }

    private fun seg(ctx: dev.kidpix.paint.canvas.Ctx2D, x0: Double, y0: Double, x1: Double, y1: Double) {
        ctx.beginPath()
        ctx.moveTo(x0, y0)
        ctx.lineTo(x1, y1)
        ctx.stroke()
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            KiddoPaint.Display.context.restore()
            KiddoPaint.Display.context.closePath()
            tool.previousEv = XY(0.0, 0.0)
            tool.isDown = false
            KiddoPaint.Display.saveMain()
        }
    }
}

val KiddoPaint.Tools.Kaleidoscope by world { ToolboxKaleidoscope() }
