package dev.kidpix.paint.engine

/**
 * The one random source. Every `Math.random()` in upstream becomes `KRandom.random()`.
 * mulberry32, same as the WebView reference capture, so seeded runs match.
 */
object KRandom {
    private var a: Int = 1234

    fun seed(s: Int) { a = s }

    /** The generator's whole state (the action log records it per action and restores it). */
    var state: Int
        get() = a
        set(v) { a = v }

    fun random(): Double {
        a += 0x6D2B79F5
        var t = a
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + ((t xor (t ushr 7)) * (t or 61)))
        return ((t xor (t ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0
    }
}
