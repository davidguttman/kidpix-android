package dev.kidpix.paint.app

import dev.kidpix.paint.KAlphabet
import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KColors
import dev.kidpix.paint.KCurrent
import dev.kidpix.paint.KSounds
import dev.kidpix.paint.KSprite
import dev.kidpix.paint.KStamps
import dev.kidpix.paint.KUi
import dev.kidpix.paint.set_colors_to_current_palette
import org.json.JSONObject

/**
 * What survives a process kill besides the picture (FilePictureStore): the tool, the remembered
 * options (the overlay's subSelection, re-applied as when the tool button is pressed again), the
 * colors, the palette page and the letter/stamp pages. The undo history does not survive, as in
 * the WebView build (its history lived in page memory).
 */
object AppState {
    fun save(): String {
        val sel = JSONObject()
        for ((name, groups) in KAndroid.subSelection) {
            val g = JSONObject()
            for ((gi, bi) in groups) g.put(gi.toString(), bi)
            sel.put(name ?: NULL_KEY, g)
        }
        return JSONObject()
            .put("v", 1)
            .put("tool", KAndroid.currentToolId)
            .put("sel", sel)
            .put("palette", KColors.Current.PaletteNumber)
            .put("color", KCurrent.color)
            .put("altColor", KCurrent.altColor)
            .put("terColor", KCurrent.terColor)
            .put("alphabetPage", KAlphabet.page)
            .put("spritePage", KSprite.page)
            .put("spriteSheetPage", KSprite.sheetPage)
            .put("stampPage", KStamps.page)
            .toString()
    }

    /** After Engine.start + kidpix_android_boot. Silent: no menu clicks on launch. */
    fun restore(json: String?) {
        if (json.isNullOrEmpty()) return
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val wasEnabled = KSounds.enabled
        KSounds.enabled = false
        try {
            val pal = o.optInt("palette", 0)
            if (pal in KColors.All.indices) KColors.Current.PaletteNumber = pal
            set_colors_to_current_palette()
            o.optString("color").takeIf { it.isNotEmpty() }?.let { KCurrent.color = it }
            o.optString("altColor").takeIf { it.isNotEmpty() }?.let { KCurrent.altColor = it }
            o.optString("terColor").takeIf { it.isNotEmpty() }?.let { KCurrent.terColor = it }
            KUi.ui.currentColorChanged(KCurrent.color)
            KAlphabet.page = o.optInt("alphabetPage", KAlphabet.page)
            KSprite.page = o.optInt("spritePage", KSprite.page)
            KSprite.sheetPage = o.optInt("spriteSheetPage", KSprite.sheetPage)
            KStamps.page = o.optInt("stampPage", KStamps.page)
            o.optJSONObject("sel")?.let { sel ->
                val m = HashMap<String?, MutableMap<Int, Int>>()
                for (name in sel.keys()) {
                    val g = sel.getJSONObject(name)
                    val groups = HashMap<Int, Int>()
                    for (k in g.keys()) groups[k.toInt()] = g.getInt(k)
                    m[if (name == NULL_KEY) null else name] = groups
                }
                KAndroid.subSelection = m
            }
            val tool = o.optString("tool", "pencil")
            if (tool in KAndroid.TOOL_IDS) KAndroid.pressMain(tool)
        } catch (t: Throwable) {
            android.util.Log.e("KidPix", "state restore failed", t)
        } finally {
            KSounds.enabled = wasEnabled
        }
    }

    private const val NULL_KEY = "\u0000null"
}
