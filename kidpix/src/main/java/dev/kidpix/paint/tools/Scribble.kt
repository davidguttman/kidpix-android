@file:JvmName("tools_Scribble")

package dev.kidpix.paint

import dev.kidpix.paint.engine.KRandom
import dev.kidpix.paint.engine.world

// Port of js/tools/scribble.js

class ToolboxScribble : Tool() {
    val tool = this
    var isDown = false
    var previousEv: Ev? = null
    var texture: () -> Any? = {
        KiddoPaint.Textures.Solid(KiddoPaint.Current.color)
    }
    var spacing = 5.0
    var size = 1.0
    var jitter: () -> Double = {
        val baseJitter = if (KiddoPaint.Current.modifiedMeta) 25 else 10
        baseJitter + (KRandom.random() * baseJitter)
    }

    override fun mousedown(ev: Ev) {
        KiddoPaint.Sounds.brushzigzag()
        tool.isDown = true
        KiddoPaint.Display.context.beginPath()
        KiddoPaint.Display.context.moveTo(ev._x, ev._y)
        tool.previousEv = ev
        pts[0] = ev._x; pts[1] = ev._y; npts = 1
    }

    // Upstream adds each point to one growing path and strokes all of it on every move (the cost
    // grew with the stroke). The colour is opaque, so stroking only the newest segment together
    // with the one before it (for the join between them) paints the same pixels: each move strokes
    // at most two segments.
    private val pts = DoubleArray(4) // the last two points: x, y, x, y
    private var npts = 0

    override fun mousemove(ev: Ev) {
        if (tool.isDown) {
            if (tool.previousEv == null || distanceBetween(tool.previousEv!!, ev) > tool.spacing) {
                KiddoPaint.Sounds.brushzigzag()
                val jitterx = tool.jitter()
                val jittery = tool.jitter()
                val lx = ev._x + (KRandom.random() * jitterx - jitterx / 2)
                val ly = ev._y + (KRandom.random() * jittery - jittery / 2)
                val ctx = KiddoPaint.Display.context
                ctx.beginPath()
                ctx.moveTo(pts[0], pts[1])
                if (npts == 2) ctx.lineTo(pts[2], pts[3])
                ctx.lineTo(lx, ly)
                if (npts == 2) { pts[0] = pts[2]; pts[1] = pts[3] }
                pts[2] = lx; pts[3] = ly; npts = 2
                KiddoPaint.Display.context.strokeStyle = tool.texture()
                KiddoPaint.Display.context.lineWidth = tool.size
                KiddoPaint.Display.context.stroke()
                tool.previousEv = ev
            }
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.mousemove(ev)
            tool.isDown = false
            tool.previousEv = null
            KiddoPaint.Display.context.closePath()
            KiddoPaint.Display.saveMain()
        }
    }
}

val KiddoPaint.Tools.Scribble by world { ToolboxScribble() }
