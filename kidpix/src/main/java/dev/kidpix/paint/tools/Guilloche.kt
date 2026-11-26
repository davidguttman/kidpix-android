@file:JvmName("tools_Guilloche")

package dev.kidpix.paint

import dev.kidpix.paint.engine.KRandom
import dev.kidpix.paint.engine.world
import kotlin.math.PI

// Port of js/tools/guilloche.js

/** `tool.randomSettings`; JS resets it to `{}` (every field undefined) on mouseup. */
class GuilSettings(
    val outradius: Double = Double.NaN,
    val inradius: Double = Double.NaN,
    val r: Double = Double.NaN,
    val Q: Double = Double.NaN,
    val m: Double = Double.NaN,
    val n: Double = Double.NaN,
)

class ToolboxGuilloche : Tool() {
    val tool = this
    var isDown = false
    var minDistance = 50.0
    var previousEv: Ev? = null
    var randomSettings = GuilSettings()
    var texture: () -> Any? = {
        KiddoPaint.Textures.Solid(KiddoPaint.Current.color)
    }

    override fun mousedown(ev: Ev) {
        val outradius = (41 + 64 * KRandom.random()) * KiddoPaint.Current.scaling
        val inradius = (21 + 42 * KRandom.random()) * KiddoPaint.Current.scaling
        val r = -5 * KRandom.random()
        val q = 7 * KRandom.random()
        val m = 5 * KRandom.random()
        val n = 10 * KRandom.random()
        tool.randomSettings = GuilSettings(outradius, inradius, r, q, m, n)

        tool.isDown = true
        tool.mousemove(ev)
    }

    override fun mousemove(ev: Ev) {
        if (!tool.isDown) return

        if (tool.previousEv == null || distanceBetween(tool.previousEv!!, ev) > tool.minDistance) {
            KiddoPaint.Sounds.brushguil()
            KiddoPaint.Display.context.beginPath()

            KiddoPaint.Display.context.lineWidth = 0.5
            KiddoPaint.Display.context.strokeStyle = tool.texture()
            KiddoPaint.Display.context.fillStyle = tool.texture()

            val rs = tool.randomSettings
            val ctx = KiddoPaint.Display.context
            if (KiddoPaint.Current.modifiedMeta) {
                var i = 0.0
                while (i < PI * 4) {
                    val coord = guil(rs.outradius, rs.r, rs.m, i, rs.inradius, rs.Q, rs.m, rs.n)
                    ctx.fillRect(jsRound(ev._x + coord.x), jsRound(ev._y + coord.y), 1, 1)
                    i += 0.007
                }
            } else {
                // Upstream strokes one path of ~1,800 points, 0.5 px wide. Chrome-like MSAA of that
                // outline took ~19 ms per figure on the tablet (thin segments sampled one by one: up to
                // 30 ms for the big figures); plain Skia anti-aliasing draws it in a couple of ms. The
                // same faint line; edge pixels differ slightly (Ctx2D.draft).
                var i = 0.0
                while (i < PI * 4) {
                    val coord = guil(rs.outradius, rs.r, rs.m, i, rs.inradius, rs.Q, rs.m, rs.n)
                    ctx.lineTo(jsRound(ev._x + coord.x), jsRound(ev._y + coord.y))
                    i += 0.007
                }
                ctx.draft = true
                try { ctx.stroke() } finally { ctx.draft = false }
            }
            KiddoPaint.Display.context.closePath()
            tool.previousEv = ev
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.isDown = false
            tool.previousEv = null
            tool.randomSettings = GuilSettings()
            KiddoPaint.Display.saveMain()
        }
    }
}

val KiddoPaint.Tools.Guilloche by world { ToolboxGuilloche() }
