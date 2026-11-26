@file:JvmName("tools_Flood")

package dev.kidpix.paint

import dev.kidpix.paint.canvas.ImageData
import dev.kidpix.paint.engine.world
import kotlin.math.floor

// Port of js/tools/flood.js
//
// Differences that keep the JS result but not its accidents:
// - Event coordinates are floored to whole pixels (fractional coordinates made the JS index
//   between pixels; the WebView app ran out of memory that way). A press outside the canvas
//   does nothing.
// - JS reads past the end of `pixels.data` give `undefined`, which never equals a color
//   component; `Uint8Clamped` gives 0 there. `same()` checks the range so a fill of a
//   transparent black area stops at the canvas edge exactly like the JS.
// - The pixel stack is an IntArray queue of packed (x, y) and `touched` an IntArray: same
//   traversal order as the JS array with shift()/push(), no per-pixel objects.

class ToolboxFlood : Tool() {
    val tool = this
    var gcop = "destination-in"
    var texture: (String) -> Any? = { _ ->
        KiddoPaint.Textures.Solid(KiddoPaint.Current.color)
    }

    override fun mousedown(ev: Ev) {
        if (KiddoPaint.Current.modifiedAlt) {
            tool.canvasWideReplace(ev)
        } else {
            tool.boundedFill(ev)
        }
    }

    fun canvasWideReplace(ev: Ev) {
        KiddoPaint.Sounds.flood()
        val width = KiddoPaint.Display.canvas.width
        val height = KiddoPaint.Display.canvas.height
        val x = floor(ev._x).toInt()
        val y = floor(ev._y).toInt()
        if (x < 0 || y < 0 || x >= width || y >= height) return

        val pixels = KiddoPaint.Display.main_context.getImageData(0, 0, width, height)
        val changedPixels = ImageData(width, height)

        val linear_cords = (y * width + x) * 4
        val original_color = ColorJson(
            pixels.data[linear_cords],
            pixels.data[linear_cords + 1],
            pixels.data[linear_cords + 2],
            pixels.data[linear_cords + 3],
        )

        val color = color2json(KiddoPaint.Current.color)

        if (colorsEqual(color, original_color)) {
            return
        }

        val d = pixels.data.bytes
        val c = changedPixels.data.bytes
        val or = original_color.r.toByte(); val og = original_color.g.toByte()
        val ob = original_color.b.toByte(); val oa = original_color.a.toByte()
        var i = 0
        while (i < d.size) {
            if (d[i] == or && d[i + 1] == og && d[i + 2] == ob && d[i + 3] == oa) {
                c[i] = color.r.toByte()
                c[i + 1] = color.g.toByte()
                c[i + 2] = color.b.toByte()
                c[i + 3] = color.a.toByte()
            }
            i += 4
        }
        KiddoPaint.Display.context.putImageData(changedPixels, 0, 0)
        KiddoPaint.Display.context.globalCompositeOperation = tool.gcop
        KiddoPaint.Display.context.fillStyle = tool.texture(KiddoPaint.Current.color)
        KiddoPaint.Display.context.fillRect(0, 0, width, height)
        KiddoPaint.Display.context.globalCompositeOperation = "source-over"
        KiddoPaint.Display.saveMain()
    }

    fun boundedFill(ev: Ev) {
        KiddoPaint.Sounds.flood()
        val width = KiddoPaint.Display.canvas.width
        val height = KiddoPaint.Display.canvas.height
        var x = floor(ev._x).toInt()
        var y = floor(ev._y).toInt()
        if (x < 0 || y < 0 || x >= width || y >= height) return
        // pixel_stack: queue of y * width + x
        var pixel_stack = IntArray(1024)
        var head = 0
        var tail = 0
        fun push(px: Int, py: Int) {
            if (tail == pixel_stack.size) {
                if (head > 0) {
                    pixel_stack.copyInto(pixel_stack, 0, head, tail); tail -= head; head = 0
                }
                if (tail == pixel_stack.size) pixel_stack = pixel_stack.copyOf(pixel_stack.size * 2)
            }
            pixel_stack[tail++] = py * width + px
        }
        push(x, y)
        // The picture as non-premultiplied ARGB ints (what getImageData's bytes hold, one int per
        // pixel) in buffers kept between taps; filled pixels go straight into [changed].
        val n = width * height
        val d = buffer(0, n)
        KiddoPaint.Display.main_canvas.sourceBitmap()!!.getPixels(d, 0, width, 0, 0, width, height)
        val original = d[y * width + x]
        val color = color2json(KiddoPaint.Current.color)
        val oc = ColorJson((original shr 16) and 0xff, (original shr 8) and 0xff, original and 0xff, original ushr 24)
        if (colorsEqual(color, oc)) {
            return
        }
        val fill = (color.a shl 24) or (color.r shl 16) or (color.g shl 8) or color.b
        val changed = buffer(1, n)
        java.util.Arrays.fill(changed, 0, n, 0)
        // JS: data[l] == original.r && ... (undefined outside the array never matches)
        fun same(p: Int) = p >= 0 && p < n && d[p] == original

        while (tail > head) {
            val new_pixel = pixel_stack[head++]
            x = new_pixel % width
            y = new_pixel / width

            var p = y * width + x
            while (y-- >= 0 && same(p)) {
                p -= width
            }
            p += width
            y++

            var reached_left = false
            var reached_right = false
            while (y++ < height && same(p)) {

                d[p] = fill
                changed[p] = fill

                if (x > 0) {
                    if (same(p - 1)) {
                        if (!reached_left) {
                            push(x - 1, y)
                            reached_left = true
                        }
                    } else if (reached_left) {
                        reached_left = false
                    }
                }

                if (x < width - 1) {
                    if (same(p + 1)) {
                        if (!reached_right) {
                            push(x + 1, y)
                            reached_right = true
                        }
                    } else if (reached_right) {
                        reached_right = false
                    }
                }

                p += width
            }
        }

        val changedPixels = ImageData(width, height, dev.kidpix.paint.canvas.Uint8Clamped(n * 4, changed))
        KiddoPaint.Display.context.putImageData(changedPixels, 0, 0)
        KiddoPaint.Display.context.globalCompositeOperation = tool.gcop
        KiddoPaint.Display.context.fillStyle = tool.texture(KiddoPaint.Current.color)
        KiddoPaint.Display.context.fillRect(0, 0, width, height)
        KiddoPaint.Display.context.globalCompositeOperation = "source-over"
        KiddoPaint.Display.saveMain() // corrupts alpha
    }

    override fun mousemove(ev: Ev) {}
    override fun mouseup(ev: Ev) {}

    /** Picture-sized int buffers kept between fills (two 3.4 MB arrays a tap were garbage). */
    private val buffers = arrayOfNulls<IntArray>(2)
    private fun buffer(k: Int, n: Int): IntArray = buffers[k]?.takeIf { it.size >= n } ?: IntArray(n).also { buffers[k] = it }
}

val KiddoPaint.Tools.Flood by world { ToolboxFlood() }
