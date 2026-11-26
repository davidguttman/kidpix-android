@file:JvmName("tools_EraserDoorbell")

package dev.kidpix.paint

import android.graphics.BitmapFactory
import android.util.Base64
import dev.kidpix.paint.canvas.ImageData
import dev.kidpix.paint.canvas.KImage
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.world

// Port of js/tools/eraser-doorbell.js

class ToolboxDoorbell : Tool() {
    val tool = this
    var isDown = false
    var leftside: ImageData? = null
    var rightside: ImageData? = null
    val centerGraphic = listOf("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAPoAAADIAQAAAADpJcE2AAAABGdBTUEAALGPC/xhBQAAACBjSFJNAAB6JgAAgIQAAPoAAACA6AAAdTAAAOpgAAA6mAAAF3CculE8AAAAAmJLR0QAAd2KE6QAAAAHdElNRQflCAMBLh5YUprGAAAA3ElEQVRYw+3YPQ6DMAyG4Q8xMPYI3KS5WEU4Wo7CERg9INwB0jb87S+ql0joWSJjy7GEitp9woM4SbX7jAZtv5zV2WUJoBmKrPV8sGYOCGxbRSMUlDl6JB4IknzU6+eXe8JBNHVYsNxuzqAyJFhizpIIbCd5oE25FbPBvnXdAxTljwM5bXcGCmjwmcTcRAXfLnc6wxCAbgOKQfhwKqaB6uiFggBrfpqBC5pBUphy+XfiAtXuxTKCBopvm2c8E0QTFSjmOx4sgjhA7kkKbqf7RwSQgnu6XAYjwD/o8QYamlM2v+DGWAAAACV0RVh0ZGF0ZTpjcmVhdGUAMjAyMS0wOC0wM1QwMTo0NjowOCswMDowMGhjP2IAAAAldEVYdGRhdGU6bW9kaWZ5ADIwMjEtMDgtMDNUMDE6NDY6MDgrMDA6MDAZPofeAAAAAElFTkSuQmCC", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAPEAAAC9AQAAAABKKqazAAAABGdBTUEAALGPC/xhBQAAACBjSFJNAAB6JgAAgIQAAPoAAACA6AAAdTAAAOpgAAA6mAAAF3CculE8AAAAAmJLR0QAAd2KE6QAAAAHdElNRQflCAMBLiXpWXPiAAABOklEQVRYw9XYMXKFIBCA4d+hsOQIHMWLOYTMu5hH4QiUFExIoUEx+JKOhcbis3CdZXcBykrHU3maq7erUL8nzdEO41gY1bGzX/Um0ldITBEVUJ7Zj+YqoIIF9JaYItIcvaEdwAIWec5S9p9xg7k98qPUt9lzzfPePkVUOOuC+Yyl2A3hezwqfwE6pyNeQY55hUt9mOI9Rfq6hfXSF4xr5Y9gb8crx/d+oPJe39LRL8bxvV9Uft+fXf2hn0hxXZJjzufwuAzjZ3wXb8bfycs8c3ojf+R6e54U5BhXn89+1bvOzgoq7ys9xCPaVbicL5/m944u/nzw9/kBPiIqbzzM752ddNSHN/cjXV36/ct/7mfqvibMsbe+NpD/FLeygiCfck7VPHG7Z+rsOmcPS/X5rzyOS/+/4vPzjX8DdoGgLPOs1MUAAAAldEVYdGRhdGU6Y3JlYXRlADIwMjEtMDgtMDNUMDE6NDY6MDgrMDA6MDBoYz9iAAAAJXRFWHRkYXRlOm1vZGlmeQAyMDIxLTA4LTAzVDAxOjQ2OjA4KzAwOjAwGT6H3gAAAABJRU5ErkJggg==")

    override fun mousedown(ev: Ev) {
        tool.isDown = true

        tool.leftside = KiddoPaint.Display.main_context.getImageData(0, 0, KiddoPaint.Display.main_canvas.width / 2.0, KiddoPaint.Display.main_canvas.height)
        tool.rightside = KiddoPaint.Display.main_context.getImageData(KiddoPaint.Display.main_canvas.width / 2.0, 0, KiddoPaint.Display.main_canvas.width / 2.0, KiddoPaint.Display.main_canvas.height)
        tool.animate(ev)
    }

    override fun mousemove(ev: Ev) {}

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.isDown = false
            tool.leftside = null
            tool.rightside = null
        }
    }

    fun animate(ev: Ev) {
        var iter = 1
        val right = flattenImage(tool.rightside!!)
        val left = flattenImage(tool.leftside!!)

        KiddoPaint.Display.bnimContext.fillStyle = "white"
        KiddoPaint.Display.bnimContext.fillRect(0, 0, KiddoPaint.Display.main_canvas.width, KiddoPaint.Display.main_canvas.height)
        val image = KImage()
        image.src = KiddoPaint.Tools.EraserHiddenPicture.hiddenPictures.random()
        image.crossOrigin = "anonymous"
        image.onload = {
            val wrh = image.width.toDouble() / image.height
            var newWidth = KiddoPaint.Display.canvas.width.toDouble()
            var newHeight = newWidth / wrh
            if (newHeight > KiddoPaint.Display.canvas.height) {
                newHeight = KiddoPaint.Display.canvas.height.toDouble()
                newWidth = newHeight * wrh
            }
            val xOffset = if (newWidth < KiddoPaint.Display.canvas.width) ((KiddoPaint.Display.canvas.width - newWidth) / 2) else 0.0
            val yOffset = if (newHeight < KiddoPaint.Display.canvas.height) ((KiddoPaint.Display.canvas.height - newHeight) / 2) else 0.0
            KiddoPaint.Display.bnimContext.imageSmoothingEnabled = false
            KiddoPaint.Display.bnimContext.save()
            KiddoPaint.Display.bnimContext.drawImage(image, xOffset, yOffset, newWidth, newHeight)
            KiddoPaint.Display.bnimContext.globalCompositeOperation = "difference"
            KiddoPaint.Display.bnimContext.fillStyle = "white"
            KiddoPaint.Display.bnimContext.fillRect(0, 0, KiddoPaint.Display.main_canvas.width, KiddoPaint.Display.main_canvas.height)
            KiddoPaint.Display.bnimContext.globalCompositeOperation = "source-over"
            KiddoPaint.Display.bnimContext.restore()

            val cimage = dataUrlImage(tool.centerGraphic.random())
            cimage.onload = {
                val cxOffset = KiddoPaint.Display.canvas.width / 2.0 - cimage.width
                val cyOffset = KiddoPaint.Display.canvas.height / 2.0 - cimage.height
                KiddoPaint.Display.bnimContext.drawImage(cimage, cxOffset, cyOffset, cimage.width * 2, cimage.height * 2)
            }
        }

        KiddoPaint.Display.animContext.putImageData(left, 0, 0)
        KiddoPaint.Display.animContext.putImageData(right, KiddoPaint.Display.main_canvas.width / 2.0, 0)
        KiddoPaint.Display.clearAll()

        pplaySingle("doordingdong") {

            KiddoPaint.Sounds.playSingle("doorcreak") // estimated duration: 2.153560 sec
            var intervalID = 0

            fun drawSlideOut() {
                val totalIter = 107 // 107 frames
                val step = (KiddoPaint.Display.main_canvas.width / 2.0) / totalIter

                KiddoPaint.Display.clearAnim()
                KiddoPaint.Display.animContext.putImageData(left, 0 - (iter * step), 0)
                KiddoPaint.Display.animContext.putImageData(right, (KiddoPaint.Display.main_canvas.width / 2.0) + (iter * step), 0)
                iter++
                if (iter > totalIter) {
                    KTime.clearInterval(intervalID)
                    KiddoPaint.Display.clearAnim()
                    tool.animatenext(ev)
                }
            }

            intervalID = KTime.setInterval({ drawSlideOut() }, 20) // 20ms frames
            drawSlideOut()
        }
    }

    fun animatenext(@Suppress("UNUSED_PARAMETER") ev: Ev) {
        KiddoPaint.Sounds.playSingle("doorwow") // estimated duration: 1.337959 sec

        var iter = 1

        val left = KiddoPaint.Display.bnimContext.getImageData(0, 0, KiddoPaint.Display.main_canvas.width / 2.0, KiddoPaint.Display.main_canvas.height)
        val right = KiddoPaint.Display.bnimContext.getImageData(KiddoPaint.Display.main_canvas.width / 2.0, 0, KiddoPaint.Display.main_canvas.width / 2.0, KiddoPaint.Display.main_canvas.height)

        KiddoPaint.Display.animContext.putImageData(left, 0, 0)
        KiddoPaint.Display.animContext.putImageData(right, KiddoPaint.Display.main_canvas.width / 2.0, 0)

        KiddoPaint.Display.clearBnim()

        var intervalID = 0

        fun drawSlideOut() {
            val totalIter = 67 // 67 frames
            val step = (KiddoPaint.Display.main_canvas.width / 2.0) / totalIter

            KiddoPaint.Display.clearAnim()
            KiddoPaint.Display.animContext.putImageData(left, 0 - (iter * step), 0)
            KiddoPaint.Display.animContext.putImageData(right, (KiddoPaint.Display.main_canvas.width / 2.0) + (iter * step), 0)
            iter++
            if (iter > totalIter) {
                KTime.clearInterval(intervalID)
                KiddoPaint.Display.clearAnim()
            }
        }

        intervalID = KTime.setInterval({ drawSlideOut() }, 20) // 20ms frames
        drawSlideOut()
    }

    /**
     * KiddoPaint.Sounds.Library.pplaySingle(sound).then(next): upstream resolves when the audio
     * element fires `ended`. SoundPlayer has no completion callback, so the continuation runs
     * after the sound's length (doordingdong.wav.mp3 is 0.888 s); with sounds off the JS promise
     * resolves at once.
     */
    private fun pplaySingle(sound: String, next: () -> Unit) {
        if (KSounds.enabled && KSounds.Library[sound] != null) {
            KiddoPaint.Sounds.playSingle(sound)
            KTime.setTimeout(next, PPLAY_MS[sound] ?: 0)
        } else {
            KTime.setTimeout(next, 0)
        }
    }

    /** `new Image()` with a `data:image/png;base64,` src (KAssets only knows asset paths). */
    private fun dataUrlImage(src: String): KImage {
        val bytes = Base64.decode(src.substringAfter("base64,"), Base64.DEFAULT)
        val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888; inScaled = false }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val img = if (bmp != null) KImage(bmp) else KImage()
        KTime.setTimeout({ if (bmp != null) img.onload?.invoke() else img.onerror?.invoke() }, 0)
        return img
    }

    companion object {
        val PPLAY_MS = mapOf("doordingdong" to 888)
    }
}

val KiddoPaint.Tools.Doorbell by world { ToolboxDoorbell() }
