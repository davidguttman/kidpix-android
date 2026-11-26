package dev.kidpix.paint

import dev.kidpix.paint.app.SoundPoolBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Every file KSounds names is in the copied assets (build/generated/kidpixAssets) and decodes.
 * MediaExtractor is not in the JVM, so decoding is checked with ffmpeg (`-f null`: decode to
 * nothing, no audio device is opened); skipped when ffmpeg is not installed.
 */
class SoundAssetsTest {
    private val assets = File(System.getProperty("kidpix.assets") ?: "build/generated/kidpixAssets")
    private val all = (KSounds.Library.values.flatten() + KSounds.english.values).toSortedSet()

    @Test fun everyFileIsInTheAssetsWithALength() {
        val missing = all.filter { !File(assets, it).isFile }
        assertEquals(emptyList<String>(), missing)
        assertEquals(emptyList<String>(), all.filter { (KSoundDurationsMs[it] ?: 0) <= 0 })
        assertEquals(all, KSoundDurationsMs.keys.toSortedSet())
        // the WAV fallback the SoundPool backend uses when an MP3 does not load
        assertEquals(emptyList<String>(), all.filter { !File(assets, SoundPoolBackend.wavFallback(it)!!).isFile })
    }

    private fun decodes(file: File, vararg fmt: String): Boolean {
        val p = ProcessBuilder(listOf("ffmpeg", "-nostdin", "-v", "error", *fmt, "-i", file.path, "-f", "null", "-"))
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(30, TimeUnit.SECONDS)
        return p.exitValue() == 0 && out.isBlank()
    }

    @Test fun everyFileDecodes() {
        val ff = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("ffmpeg not installed", ff)
        val bad = all.filter { !decodes(File(assets, it)) }
        // kidpix-submenu-brush-northern.wav.mp3 is 313 bytes, too short for format probing; it
        // decodes as MP3 when told so, and its WAV is there as the device fallback
        assertEquals(listOf("sndmp3/kidpix-submenu-brush-northern.wav.mp3"), bad)
        for (b in bad) {
            assertTrue(b, decodes(File(assets, b), "-f", "mp3"))
            assertTrue(b, decodes(File(assets, SoundPoolBackend.wavFallback(b)!!)))
        }
    }
}
