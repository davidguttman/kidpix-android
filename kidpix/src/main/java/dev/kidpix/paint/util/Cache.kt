@file:JvmName("util_Cache")

package dev.kidpix.paint

// Port of js/util/cache.js

class StampSetting(var hueShift: Double, var altSize: Double) {
    operator fun set(setting: String, value: Double) {
        when (setting) { "hueShift" -> hueShift = value; "altSize" -> altSize = value }
    }
    operator fun get(setting: String): Double = if (setting == "hueShift") hueShift else altSize
}

/** KiddoPaint.Cache */
object KCache {
    val StampSettings = HashMap<String, StampSetting>()

    object Defaults {
        var hueShift = 0.0
        var altSize = 144.0
    }

    fun getStampSettings(stamp: String): StampSetting =
        StampSettings.getOrPut(stamp) { StampSetting(Defaults.hueShift, Defaults.altSize) }

    fun setStampSetting(stamp: String, setting: String, value: Double) {
        getStampSettings(stamp)[setting] = value
    }
}
