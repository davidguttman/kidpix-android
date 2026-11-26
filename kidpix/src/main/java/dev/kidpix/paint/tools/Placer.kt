@file:JvmName("tools_Placer")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.CanvasImageSource
import dev.kidpix.paint.canvas.Ctx2D
import dev.kidpix.paint.engine.world

class ToolboxPlacer : Tool() {
    val tool = this
    var isDown = false
    var image: CanvasImageSource? = null
    var prevTool: Tool? = null
    var size: Size? = null

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        tool.mousemove(ev)
    }

    override fun mousemove(ev: Ev) {
        val ctx = if (tool.isDown) KiddoPaint.Display.context else KiddoPaint.Display.previewContext
        val image = tool.image ?: return // JS: drawImage({}) throws
        val size = tool.size ?: return

        if (KiddoPaint.Current.modifiedRange != 0) {
            val scale = 1 + KiddoPaint.Current.modifiedRange / 100.0
            val scaledImg = scaleImageDataCanvasAPI(image, scale)
            drawImageff(ctx, scaledImg, ev._x, ev._y, size.width * scale, size.height * scale, KiddoPaint.Current.modifiedAlt, KiddoPaint.Current.modifiedMeta, true)
        } else {
            drawImageff(ctx, image, ev._x, ev._y, size.width, size.height, KiddoPaint.Current.modifiedAlt, KiddoPaint.Current.modifiedMeta, true)
        }
    }

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.isDown = false
            tool.image = null
            KiddoPaint.Display.saveMain()
            // JS: Current.tool = prevTool, which is `{}` (a tool with no handlers) when nobody set it
            KiddoPaint.Current.tool = tool.prevTool ?: NoopTool("{}")
            reset_ranges()
            tool.prevTool = null
            tool.size = null
        }
    }

    // https://stackoverflow.com/a/37388113 (a closure over ctx in the JS mousemove)
    fun drawImageff(ctx: Ctx2D, img: CanvasImageSource, xIn: Double, yIn: Double, widthIn: Double?, heightIn: Double?, flip: Boolean, flop: Boolean, center: Boolean = false) {
        var x = xIn
        var y = yIn
        ctx.save()
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

val KiddoPaint.Tools.Placer by world { ToolboxPlacer() }
