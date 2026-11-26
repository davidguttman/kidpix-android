package dev.kidpix.paint.canvas

import android.graphics.Typeface
import java.io.File
import java.io.RandomAccessFile

/**
 * Chrome's font fallback for `sans-serif` text, as far as the colour emoji font is concerned.
 *
 * Chrome (Skia's Android font manager) takes, for a character the primary font (Roboto) lacks,
 * the first fallback family in /system/etc/fonts.xml that has it. On Android 15 the order is
 * Roboto, ..., NotoSansSymbols-Regular-Subsetted, ..., NotoColorEmoji, NotoSansSymbols-Regular-
 * Subsetted2. So ♠♣♥♦❄ (in the emoji font and in Symbols2) come out as emoji glyphs in Chrome,
 * while Android's own text stack (minikin) prefers the text font Symbols2 for text-default emoji.
 * Upstream's card brush and the paw prints draw such characters and then recolour them, so the
 * glyph shape and size must be the emoji font's, as in the WebView.
 *
 * [useEmoji] says whether Chrome would draw a code point with the emoji font: Roboto and
 * NotoSansSymbols-Regular-Subsetted lack it and NotoColorEmoji has it (read from the fonts' cmap
 * tables). Ctx2D draws those runs with [emojiTypeface]. When the font files are missing (old
 * devices, other layouts) nothing changes.
 */
object EmojiFallback {
    /** Where Roboto-Regular.ttf, NotoSansSymbols-Regular-Subsetted.ttf, NotoColorEmoji.ttf live. */
    @Volatile var fontDir: File = File("/system/fonts")
    /** The emoji font file, when it is not `fontDir/NotoColorEmoji.ttf` (the headless harness). */
    @Volatile var emojiFile: File? = null

    private class Loaded(val text: List<Cmap>, val emoji: Cmap, val typeface: Typeface)

    private var loaded: Loaded? = null
    private var loadedKey: Pair<File, File?>? = null

    private fun load(): Loaded? = synchronized(this) {
        val key = fontDir to emojiFile
        if (loadedKey != key) {
            loadedKey = key
            loaded = try {
                val ef = emojiFile ?: File(fontDir, "NotoColorEmoji.ttf")
                val emoji = Cmap.read(ef)
                val text = listOf("Roboto-Regular.ttf", "NotoSansSymbols-Regular-Subsetted.ttf").mapNotNull { Cmap.read(File(fontDir, it)) }
                if (emoji == null || text.isEmpty()) null else Loaded(text, emoji, Typeface.createFromFile(ef))
            } catch (e: Exception) {
                null
            }
        }
        loaded
    }

    val emojiTypeface: Typeface? get() = load()?.typeface

    fun useEmoji(cp: Int): Boolean {
        if (cp < 0x80) return false
        val l = load() ?: return false
        return l.emoji.has(cp) && l.text.none { it.has(cp) }
    }

    /** Code points that belong to the previous character's run (selectors, joiners, skin tones, tags). */
    fun isJoining(cp: Int): Boolean =
        cp in 0xFE00..0xFE0F || cp == 0x200D || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F

    /** Splits [text] into runs (start, end, emoji?) for Chrome's emoji fallback; one run when none is needed. */
    fun runs(text: String): List<Triple<Int, Int, Boolean>> {
        val out = ArrayList<Triple<Int, Int, Boolean>>()
        var i = 0
        var start = 0
        var cur: Boolean? = null
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            if (cur == null || (!isJoining(cp) && useEmoji(cp) != cur)) {
                if (cur != null) out.add(Triple(start, i, cur))
                start = i
                cur = if (isJoining(cp)) false else useEmoji(cp)
            }
            i += n
        }
        if (cur != null) out.add(Triple(start, text.length, cur))
        return out
    }
}

/** Code point coverage of a TrueType/OpenType font, from its cmap (formats 4 and 12). */
class Cmap private constructor(private val ranges: IntArray) {
    fun has(cp: Int): Boolean {
        var lo = 0
        var hi = ranges.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = ranges[2 * mid]
            val e = ranges[2 * mid + 1]
            if (cp < s) hi = mid - 1 else if (cp > e) lo = mid + 1 else return true
        }
        return false
    }

    companion object {
        fun read(f: File): Cmap? {
            if (!f.isFile) return null
            RandomAccessFile(f, "r").use { r ->
                fun u16(o: Long): Int { r.seek(o); return r.readUnsignedShort() }
                fun u32(o: Long): Long { r.seek(o); return r.readInt().toLong() and 0xFFFFFFFFL }
                val numTables = u16(4)
                var cmap = -1L
                for (t in 0 until numTables) {
                    val rec = 12L + 16 * t
                    r.seek(rec)
                    val tag = ByteArray(4).also { r.readFully(it) }
                    if (String(tag, Charsets.US_ASCII) == "cmap") cmap = u32(rec + 8)
                }
                if (cmap < 0) return null
                val n = u16(cmap + 2)
                var best = -1L
                var bestScore = 0
                for (k in 0 until n) {
                    val rec = cmap + 4 + 8 * k
                    val pid = u16(rec); val eid = u16(rec + 2)
                    val off = cmap + u32(rec + 4)
                    val fmt = u16(off)
                    val score = when {
                        fmt == 12 && (pid == 3 && eid == 10 || pid == 0) -> 3
                        fmt == 4 && (pid == 3 && eid == 1 || pid == 0) -> 2
                        else -> 0
                    }
                    if (score > bestScore) { bestScore = score; best = off }
                }
                if (best < 0) return null
                val list = ArrayList<Int>()
                if (u16(best) == 12) {
                    val groups = u32(best + 12)
                    for (g in 0 until groups) {
                        val o = best + 16 + 12 * g
                        list.add(u32(o).toInt()); list.add(u32(o + 4).toInt())
                    }
                } else {
                    val segX2 = u16(best + 6)
                    val ends = best + 14
                    val starts = ends + segX2 + 2
                    val deltas = starts + segX2
                    val rangeOffs = deltas + segX2
                    for (s in 0 until segX2 / 2) {
                        val end = u16(ends + 2L * s); val start = u16(starts + 2L * s)
                        if (start == 0xFFFF) continue
                        val delta = u16(deltas + 2L * s); val ro = u16(rangeOffs + 2L * s)
                        if (ro == 0) {
                            // every code point maps unless start+delta wraps to glyph 0
                            for (c in start..end) if ((c + delta) and 0xFFFF != 0) addCp(list, c)
                        } else {
                            for (c in start..end) {
                                val gi = u16(rangeOffs + 2L * s + ro + 2L * (c - start))
                                if (gi != 0) addCp(list, c)
                            }
                        }
                    }
                }
                // sort and merge
                val pairs = (0 until list.size / 2).map { list[2 * it] to list[2 * it + 1] }.sortedBy { it.first }
                val merged = ArrayList<Int>()
                for ((s, e) in pairs) {
                    if (merged.isNotEmpty() && s <= merged[merged.size - 1] + 1) {
                        merged[merged.size - 1] = max(merged[merged.size - 1], e)
                    } else { merged.add(s); merged.add(e) }
                }
                return Cmap(merged.toIntArray())
            }
        }

        private fun max(a: Int, b: Int) = if (a > b) a else b

        private fun addCp(list: ArrayList<Int>, c: Int) {
            if (list.size >= 2 && list[list.size - 1] == c - 1) list[list.size - 1] = c else { list.add(c); list.add(c) }
        }
    }
}
