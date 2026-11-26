package dev.kidpix.paint.canvas

import kotlin.math.roundToInt

/**
 * CSS color strings -> non-premultiplied ARGB ints, like the browser parses fillStyle/strokeStyle.
 * Supports every named color, #rgb #rgba #rrggbb #rrggbbaa, rgb()/rgba() (commas or spaces, % and /
 * alpha), hsl()/hsla(), and 'transparent'. Returns null for unparseable input (the browser then
 * keeps the previous style).
 */
object CssColor {
    private val cache = HashMap<String, Int?>()

    fun parse(input: String): Int? {
        cache[input]?.let { return it }
        if (cache.containsKey(input)) return null
        val v = parseUncached(input.trim().lowercase())
        if (cache.size > 4096) cache.clear()
        cache[input] = v
        return v
    }

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Serialize like the browser's fillStyle getter. */
    fun serialize(c: Int): String {
        val a = (c ushr 24) and 0xff
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        return if (a == 255) String.format("#%02x%02x%02x", r, g, b)
        else {
            var s = (a / 255.0).toString()
            // browsers print the shortest round-tripping alpha, e.g. 0.5
            if (s.length > 6) s = String.format("%.4f", a / 255.0).trimEnd('0').trimEnd('.')
            "rgba($r, $g, $b, $s)"
        }
    }

    private fun parseUncached(s: String): Int? {
        if (s.isEmpty()) return null
        if (s[0] == '#') return parseHex(s.substring(1))
        NAMED[s]?.let { return it }
        val open = s.indexOf('(')
        if (open < 0 || !s.endsWith(")")) return null
        val fn = s.substring(0, open).trim()
        val body = s.substring(open + 1, s.length - 1)
        val parts = splitArgs(body) ?: return null
        return when (fn) {
            "rgb", "rgba" -> parseRgb(parts)
            "hsl", "hsla" -> parseHsl(parts)
            else -> null
        }
    }

    private fun parseHex(h: String): Int? {
        if (!h.all { it in '0'..'9' || it in 'a'..'f' }) return null
        fun d(i: Int) = h[i].digitToInt(16)
        return when (h.length) {
            3 -> argb(255, d(0) * 17, d(1) * 17, d(2) * 17)
            4 -> argb(d(3) * 17, d(0) * 17, d(1) * 17, d(2) * 17)
            6 -> argb(255, d(0) * 16 + d(1), d(2) * 16 + d(3), d(4) * 16 + d(5))
            8 -> argb(d(6) * 16 + d(7), d(0) * 16 + d(1), d(2) * 16 + d(3), d(4) * 16 + d(5))
            else -> null
        }
    }

    /** Splits "1, 2, 3 / 0.5" or "1,2,3,0.5" into tokens; alpha (if any) is last. */
    private fun splitArgs(body: String): List<String>? {
        val t = body.trim()
        if (t.isEmpty()) return null
        return if (t.contains(',')) {
            val p = t.split(',').map { it.trim() }
            if (p.any { it.isEmpty() }) null else p
        } else {
            val slash = t.split('/')
            if (slash.size > 2) return null
            val main = slash[0].trim().split(Regex("\\s+"))
            if (slash.size == 2) main + slash[1].trim() else main
        }
    }

    private fun num(tok: String): Double? = tok.toDoubleOrNull()

    private fun channel(tok: String): Double? =
        if (tok.endsWith("%")) num(tok.dropLast(1))?.let { it * 255.0 / 100.0 } else num(tok)

    private fun alpha(tok: String): Double? =
        if (tok.endsWith("%")) num(tok.dropLast(1))?.let { it / 100.0 } else num(tok)

    private fun parseRgb(p: List<String>): Int? {
        if (p.size != 3 && p.size != 4) return null
        val r = channel(p[0]) ?: return null
        val g = channel(p[1]) ?: return null
        val b = channel(p[2]) ?: return null
        val a = if (p.size == 4) alpha(p[3]) ?: return null else 1.0
        return argb(round255(a.coerceIn(0.0, 1.0) * 255), round255(r), round255(g), round255(b))
    }

    private fun round255(v: Double): Int = v.coerceIn(0.0, 255.0).roundToInt()

    private fun parseHsl(p: List<String>): Int? {
        if (p.size != 3 && p.size != 4) return null
        var hTok = p[0]
        if (hTok.endsWith("deg")) hTok = hTok.dropLast(3)
        val h = num(hTok) ?: return null
        val s = (if (p[1].endsWith("%")) num(p[1].dropLast(1)) else num(p[1])) ?: return null
        val l = (if (p[2].endsWith("%")) num(p[2].dropLast(1)) else num(p[2])) ?: return null
        val a = if (p.size == 4) alpha(p[3]) ?: return null else 1.0
        val rgb = hslToRgb(((h % 360) + 360) % 360 / 360.0, (s / 100).coerceIn(0.0, 1.0), (l / 100).coerceIn(0.0, 1.0))
        return argb(round255(a.coerceIn(0.0, 1.0) * 255), rgb[0], rgb[1], rgb[2])
    }

    private fun hslToRgb(h: Double, s: Double, l: Double): IntArray {
        val q = if (l <= 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(t0: Double): Double {
            var t = t0
            if (t < 0) t += 1
            if (t > 1) t -= 1
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 1.0 / 2 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }
        return intArrayOf(round255(hue(h + 1.0 / 3) * 255), round255(hue(h) * 255), round255(hue(h - 1.0 / 3) * 255))
    }

    private val NAMED: Map<String, Int> = run {
        val m = HashMap<String, Int>()
        val src = """
aliceblue f0f8ff antiquewhite faebd7 aqua 00ffff aquamarine 7fffd4 azure f0ffff beige f5f5dc bisque ffe4c4
black 000000 blanchedalmond ffebcd blue 0000ff blueviolet 8a2be2 brown a52a2a burlywood deb887 cadetblue 5f9ea0
chartreuse 7fff00 chocolate d2691e coral ff7f50 cornflowerblue 6495ed cornsilk fff8dc crimson dc143c cyan 00ffff
darkblue 00008b darkcyan 008b8b darkgoldenrod b8860b darkgray a9a9a9 darkgreen 006400 darkgrey a9a9a9 darkkhaki bdb76b
darkmagenta 8b008b darkolivegreen 556b2f darkorange ff8c00 darkorchid 9932cc darkred 8b0000 darksalmon e9967a
darkseagreen 8fbc8f darkslateblue 483d8b darkslategray 2f4f4f darkslategrey 2f4f4f darkturquoise 00ced1
darkviolet 9400d3 deeppink ff1493 deepskyblue 00bfff dimgray 696969 dimgrey 696969 dodgerblue 1e90ff
firebrick b22222 floralwhite fffaf0 forestgreen 228b22 fuchsia ff00ff gainsboro dcdcdc ghostwhite f8f8ff gold ffd700
goldenrod daa520 gray 808080 green 008000 greenyellow adff2f grey 808080 honeydew f0fff0 hotpink ff69b4
indianred cd5c5c indigo 4b0082 ivory fffff0 khaki f0e68c lavender e6e6fa lavenderblush fff0f5 lawngreen 7cfc00
lemonchiffon fffacd lightblue add8e6 lightcoral f08080 lightcyan e0ffff lightgoldenrodyellow fafad2 lightgray d3d3d3
lightgreen 90ee90 lightgrey d3d3d3 lightpink ffb6c1 lightsalmon ffa07a lightseagreen 20b2aa lightskyblue 87cefa
lightslategray 778899 lightslategrey 778899 lightsteelblue b0c4de lightyellow ffffe0 lime 00ff00 limegreen 32cd32
linen faf0e6 magenta ff00ff maroon 800000 mediumaquamarine 66cdaa mediumblue 0000cd mediumorchid ba55d3
mediumpurple 9370db mediumseagreen 3cb371 mediumslateblue 7b68ee mediumspringgreen 00fa9a mediumturquoise 48d1cc
mediumvioletred c71585 midnightblue 191970 mintcream f5fffa mistyrose ffe4e1 moccasin ffe4b5 navajowhite ffdead
navy 000080 oldlace fdf5e6 olive 808000 olivedrab 6b8e23 orange ffa500 orangered ff4500 orchid da70d6
palegoldenrod eee8aa palegreen 98fb98 paleturquoise afeeee palevioletred db7093 papayawhip ffefd5 peachpuff ffdab9
peru cd853f pink ffc0cb plum dda0dd powderblue b0e0e6 purple 800080 rebeccapurple 663399 red ff0000
rosybrown bc8f8f royalblue 4169e1 saddlebrown 8b4513 salmon fa8072 sandybrown f4a460 seagreen 2e8b57
seashell fff5ee sienna a0522d silver c0c0c0 skyblue 87ceeb slateblue 6a5acd slategray 708090 slategrey 708090
snow fffafa springgreen 00ff7f steelblue 4682b4 tan d2b48c teal 008080 thistle d8bfd8 tomato ff6347
turquoise 40e0d0 violet ee82ee wheat f5deb3 white ffffff whitesmoke f5f5f5 yellow ffff00 yellowgreen 9acd32
"""
        val toks = src.trim().split(Regex("\\s+"))
        var i = 0
        while (i + 1 < toks.size) {
            m[toks[i]] = (0xff shl 24) or toks[i + 1].toInt(16)
            i += 2
        }
        m["transparent"] = 0
        m
    }
}
