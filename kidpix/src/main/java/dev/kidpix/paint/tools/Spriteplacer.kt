@file:JvmName("tools_Spriteplacer")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.CanvasImageSource
import dev.kidpix.paint.canvas.Ctx2D
import dev.kidpix.paint.engine.world

class ToolboxSpritePlacer : Tool() {
    val tool = this
    var isDown = false
    var image: CanvasImageSource? = null
    var prevTool: Tool? = null
    /** JS `{}` (never filled in upstream): null width/height = the image's own size. */
    var size: Size? = null
    var soundBefore: () -> Unit = {}
    var soundDuring: () -> Unit = {}

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.mousemove(ev)
        tool.soundBefore()
    }

    override fun mousemove(ev: Ev) {
        val ctx = if (tool.isDown) KiddoPaint.Display.context else KiddoPaint.Display.previewContext

        if (tool.isDown) {
            tool.soundDuring()
        }

        val image = tool.image ?: return // JS: drawing `{}` throws
        drawImageff(ctx, image, ev._x, ev._y, tool.size?.width, tool.size?.height, KiddoPaint.Current.modifiedAlt, KiddoPaint.Current.modifiedMeta, true)
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.isDown = false
            KiddoPaint.Display.saveMain()
            tool.size = null
        }
    }

    // https://stackoverflow.com/a/37388113 (a closure over ctx in the JS mousemove)
    fun drawImageff(ctx: Ctx2D, imgIn: CanvasImageSource, xIn: Double, yIn: Double, widthIn: Double?, heightIn: Double?, flip: Boolean, flop: Boolean, center: Boolean = false) {
        var x = xIn
        var y = yIn
        ctx.save()
        ctx.imageSmoothingEnabled = false

        // kidpix supported 3 different sizes of stamps
        val img = scaleImageDataCanvasAPIPixelated(imgIn, 1 * KiddoPaint.Current.scaling * (if (KiddoPaint.Current.modifiedCtrl) 3 else 1))

        val width = widthIn ?: img.width.toDouble()
        val height = heightIn ?: img.height.toDouble()
        // Set rotation point to center of image, instead of top/left
        if (center) {
            x -= width / 2
            y -= height / 2
        }

        // Set the origin to the center of the image
        ctx.translate(x + width / 2, y + height / 2)

        // Flip/flop the canvas
        ctx.scale(if (flip) -1 else 1, if (flop) -1 else 1)

        // Draw the image
        ctx.drawImage(img, -width / 2, -height / 2)

        ctx.restore()
    }
}

val KiddoPaint.Tools.SpritePlacer by world { ToolboxSpritePlacer() }
