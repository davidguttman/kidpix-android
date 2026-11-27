@file:JvmName("tools_Wholefx")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.CanvasImageSource
import dev.kidpix.paint.canvas.ImageData
import dev.kidpix.paint.engine.world
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign

// Port of js/tools/wholefx.js

object JumbleFx {
    const val PINCH = "pinch"
    const val SWIRL = "swirl"
    const val LENSBLUR = "lensblur"
    const val TRIBLUR = "triblur"
    const val ZOOM = "zoom"
    const val HEXAGON = "hexagon"
    const val INK = "ink"
    const val EDGE = "edge"
    const val PANCAKE = "pancake"
    const val PIXELATE = "pixelate"
    const val HUE = "hue"
    const val SAT = "sat"
    const val NIGHTVISION = "nightvision"
    const val INVERT = "invert"
    const val SUNSHINE = "sunshine"
    const val DITHER = "dither"
    const val THRESHOLD = "threshold"
}

class ToolboxWholeCanvasEffect : Tool() {
    val tool = this
    var isDown = false
    val gfx = fx.canvas() // expensive; create once (natively a handle on the shared GL canvas)
    var textureGfx: GfxTexture? = null
    var mainImageData: ImageData? = null
    var initialClick: Ev? = null
    var effect: String = JumbleFx.PANCAKE

    /** Effects drawn by glfx (the rest read [mainImageData] on the CPU). */
    private fun usesGl() = tool.effect !in CPU_EFFECTS
    /** Bumped at each mouseup: a glfx picture that arrives later belongs to an ended stroke. */
    private var stroke = 0
    private var lastEv: Ev? = null
    /** mousemove renders glfx effects in the background (see [mousemove]); off for the final picture. */
    private var async = true

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.initialClick = ev
        // only what this effect reads: a whole-picture copy each is 3.4 MB of work and garbage
        if (usesGl()) tool.textureGfx = tool.gfx.texture(KiddoPaint.Display.main_canvas)
        else tool.mainImageData = KiddoPaint.Display.main_context.getImageData(0, 0, KiddoPaint.Display.main_canvas.width, KiddoPaint.Display.main_canvas.height)
        KiddoPaint.Display.saveUndo()
        // glfx effects arrive from the GL thread a frame or more later: until the first rendered
        // picture replaces tmp, tmp shows the picture as it was (main is cleared below), so no
        // frame ever shows a blank canvas. Every path that ends the drag (mouseup; a cancel is a
        // pointerup) renders into tmp with replaceWith, so the end state is unchanged.
        if (tool.effect in GL_EFFECTS) KiddoPaint.Display.context.replaceWith(KiddoPaint.Display.main_canvas)
        KiddoPaint.Display.clearMain() // this causes the bug where if the mouse move off screen, the mouseout even clears tmp context and everything is lost; but we need the main clear incase there's alpha it gets double rendered on preview...
        tool.mousemove(ev)
    }

    override fun mousemove(ev: Ev) {
        if (tool.isDown) {
            lastEv = ev
            // a replay (log/KLog.kt) shows nothing while dragging: only the last position counts (mouseup)
            // (the play button's replay, KLog.showDrags, shows the drag as it was drawn)
            if (tool.effect in GL_EFFECTS && async && dev.kidpix.paint.log.KLog.replaying && !dev.kidpix.paint.log.KLog.showDrags) return
            if (tool.effect in GL_EFFECTS && async) {
                // While dragging, the GPU renders in the background and the newest picture replaces
                // tmp when it is ready (a frame or so later); the main thread doesn't wait for the
                // render and the read-back. mouseup renders the last position once more, waiting.
                val s = stroke
                val chain = glChain(ev) ?: return
                chain.updateAsync { img ->
                    // (and only into this world: a replay that ended restarts the engine)
                    if (tool.isDown && s == stroke && KiddoPaint.Tools.WholeCanvasEffect === tool) { KiddoPaint.Display.context.replaceWith(img); KUi.ui.invalidate() }
                }
                return
            }
            val initialClick = tool.initialClick!!
            val drawDistance = distanceBetween(ev, initialClick)
            var renderedGfx: CanvasImageSource? = null
            when (tool.effect) {
                in GL_EFFECTS -> renderedGfx = glChain(ev)!!.update()
                JumbleFx.PIXELATE -> {
                    val rendered = glChain(ev)!!.update()
                    val blocks = remap(0.0, 500.0, 50.0, 7.0, clamp(0.0, 500.0, drawDistance))
                    renderedGfx = pixelateCanvas(rendered, blocks)
                }
                JumbleFx.NIGHTVISION -> {
                    val s = Filters.sobel(tool.mainImageData!!)
                    renderedGfx = KiddoPaint.Display.imageTypeToCanvas(s)
                }
                JumbleFx.INVERT -> {
                    val alpha = remap(0.0, 500.0, 1.0, 0.0, clamp(0.0, 500.0, drawDistance))
                    val s = Filters.gcoInvert(tool.mainImageData!!, alpha)
                    renderedGfx = s
                }
                JumbleFx.SUNSHINE -> {
                    val alpha = remap(0.0, 500.0, 1.0, 0.0, clamp(0.0, 500.0, drawDistance))
                    val s = Filters.gcoOverlay(tool.mainImageData!!, alpha)
                    renderedGfx = s
                }
                JumbleFx.DITHER -> {
                    val s: ImageData
                    if (KiddoPaint.Current.modifiedCtrl) {
                        val threshold = remap(0.0, 500.0, 192.0, 0.0, clamp(0.0, 500.0, drawDistance))
                        s = Dither.bayer(tool.mainImageData!!, threshold)
                    } else if (KiddoPaint.Current.modifiedMeta) {
                        s = Dither.atkinson(tool.mainImageData!!)
                    } else {
                        s = Dither.floydsteinberg(tool.mainImageData!!)
                    }
                    renderedGfx = KiddoPaint.Display.imageTypeToCanvas(s)
                }
                JumbleFx.THRESHOLD -> {
                    // var threshold = remap(0, 500, 1, 255, clamp(0, 500, drawDistance));
                    val s = Dither.threshold(tool.mainImageData!!, 100.0)
                    renderedGfx = KiddoPaint.Display.imageTypeToCanvas(s)
                }
            }
            // JS: drawImage(undefined) throws a TypeError for an unknown effect
            // (clearTmp + drawImage at 0, 0 of a whole-canvas picture: one copy)
            KiddoPaint.Display.context.replaceWith(renderedGfx ?: throw IllegalStateException("WholeCanvasEffect: unknown effect ${tool.effect}"))
        }
    }

    /**
     * The glfx chain of a glfx effect for [ev] (recorded, not run), or null for a CPU effect.
     * Pancake Stack: upstream draws the glfx picture (an identity brightnessContrast) n times onto
     * tmp with growing globalAlpha; here the GPU composites the copies (FxCanvas.pancakeStack).
     */
    private fun glChain(ev: Ev): GfxCanvas? {
        val initialClick = tool.initialClick!!
        val drawDistance = distanceBetween(ev, initialClick)
        val g = tool.gfx.draw(tool.textureGfx!!)
        return when (tool.effect) {
            JumbleFx.PINCH -> g.bulgePinch(initialClick._x, initialClick._y, 200.0, remap(0.0, 500.0, -1.0, 1.0, drawDistance))
            JumbleFx.SWIRL -> {
                val horizDist = abs(ev._x - initialClick._x)
                val vertDist = ev._y - initialClick._y
                val swirlAngle = remap(-300.0, 300.0, -PI * 2, PI * 2, vertDist)
                g.swirl(initialClick._x, initialClick._y, horizDist, swirlAngle)
            }
            JumbleFx.LENSBLUR -> g.lensBlur(remap(0.0, 500.0, 0.0, 50.0, drawDistance), 0.88, 0.70841)
            JumbleFx.TRIBLUR -> g.triangleBlur(drawDistance / 5.0)
            JumbleFx.ZOOM -> g.zoomBlur(initialClick._x, initialClick._y, remap(0.0, 250.0, 0.0, 1.0, drawDistance))
            JumbleFx.HEXAGON -> g.hexagonalPixelate(initialClick._x, initialClick._y, drawDistance / 10.0)
            JumbleFx.INK -> g.ink(remap(0.0, 250.0, -1.0, 1.0, drawDistance))
            JumbleFx.HUE -> g.hueSaturation(remap(0.0, 1000.0, -1.0, 1.0, drawDistance), 0.0)
            JumbleFx.SAT -> g.hueSaturation(0.0, remap(0.0, 500.0, -1.0, 1.0, drawDistance))
            JumbleFx.EDGE -> g.edgeWork(drawDistance / 10.0)
            JumbleFx.PANCAKE -> {
                val howManyPancakes = 2 + (drawDistance / 64)
                val increment = if (KiddoPaint.Current.modifiedAlt) 4 else 16
                g.brightnessContrast(0.0, 0.0).pancakeStack(howManyPancakes, increment.toDouble(), sign(ev._x - initialClick._x), sign(ev._y - initialClick._y))
            }
            JumbleFx.PIXELATE -> g.brightnessContrast(0.0, 0.0)
            else -> null
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            stroke++
            // the picture kept: the last position rendered (upstream's last mousemove), waiting for it
            val last = lastEv
            if (usesGl() && last != null) { async = false; try { tool.mousemove(last) } finally { async = true } }
            lastEv = null
            tool.isDown = false
            tool.textureGfx?.destroy()
            tool.textureGfx = null
            tool.mainImageData = null
            tool.initialClick = null
            KiddoPaint.Display.saveMainSkipUndo()
        }
    }
}

private val CPU_EFFECTS = setOf(JumbleFx.NIGHTVISION, JumbleFx.INVERT, JumbleFx.SUNSHINE, JumbleFx.DITHER, JumbleFx.THRESHOLD)
private val GL_EFFECTS = setOf(JumbleFx.PINCH, JumbleFx.SWIRL, JumbleFx.LENSBLUR, JumbleFx.TRIBLUR, JumbleFx.ZOOM, JumbleFx.HEXAGON,
    JumbleFx.INK, JumbleFx.HUE, JumbleFx.SAT, JumbleFx.EDGE, JumbleFx.PANCAKE)

val KiddoPaint.Tools.WholeCanvasEffect by world { ToolboxWholeCanvasEffect() }
