package dev.kidpix.paint.harness

import dev.kidpix.paint.canvas.EmojiFallback
import java.io.File

/** Headless runs draw emoji with the reference device's emoji font (see test resources harness-fonts/README.md). */
object HarnessFonts {
    fun install() {
        System.getProperty("robolectric.nativeruntime.fontdir")?.let { EmojiFallback.fontDir = File(it) }
        val url = HarnessFonts::class.java.classLoader!!.getResource("harness-fonts/NotoColorEmoji.ttf")
        EmojiFallback.emojiFile = File(url.toURI())
    }
}
