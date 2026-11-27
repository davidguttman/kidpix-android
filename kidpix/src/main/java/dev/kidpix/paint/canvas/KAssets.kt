package dev.kidpix.paint.canvas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.InputStream

/**
 * Where upstream-relative paths ('img/foo.png', 'sndmp3/x.mp3', 'android/opening.png') come
 * from. The app sets an AssetManager opener; the headless harness points at the generated
 * assets directory. Decoded bitmaps are cached (they are treated as immutable).
 */
object KAssets {
    var opener: (String) -> InputStream? = { null }
    private val cache = HashMap<String, Bitmap?>()

    fun useDirectory(dir: File) {
        opener = { p -> File(dir, p).takeIf { it.isFile }?.inputStream() }
        cache.clear()
    }

    fun open(path: String): InputStream? = runCatching { opener(normalize(path)) }.getOrNull()

    fun loadBitmap(path: String): Bitmap? = synchronized(cache) {
        val key = normalize(path)
        cache.getOrPut(key) {
            open(key)?.use { s ->
                BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                    inPremultiplied = true
                })
            }
        }
    }

    /** The asset path [b] was loaded from (the very bitmap the cache holds), or null. */
    fun keyOf(b: Bitmap): String? = synchronized(cache) { cache.entries.firstOrNull { it.value === b }?.key }

    private fun normalize(p: String) = p.removePrefix("./").removePrefix("/")
}
