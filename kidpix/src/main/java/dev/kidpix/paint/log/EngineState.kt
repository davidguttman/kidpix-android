package dev.kidpix.paint.log

import dev.kidpix.paint.Ev
import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KCurrent
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KSounds
import dev.kidpix.paint.KUi
import dev.kidpix.paint.KiddoPaint
import dev.kidpix.paint.PEvent
import dev.kidpix.paint.app.AppState
import dev.kidpix.paint.press_alphabet
import dev.kidpix.paint.KCache
import dev.kidpix.paint.XY
import dev.kidpix.paint.Astroid
import dev.kidpix.paint.StampSetting
import dev.kidpix.paint.show_generic_submenu
import org.json.JSONArray
import org.json.JSONObject

/**
 * The engine state a checkpoint keeps besides the pictures: the app state that already survived
 * restarts (tool, remembered options, colours, pages: [AppState]) plus what a replay of the next
 * actions depends on: modifier keys, wheel ranges, the velocity state, the event counter, the
 * overlay's pen state, the chosen letter or stamp. Taken only between actions (no contact, no
 * timer pending), so nothing in progress has to be kept.
 */
object EngineState {
    fun capture(): JSONObject {
        val c = KCurrent
        val o = JSONObject()
        o.put("app", AppState.save())
        o.put("tool", KAndroid.toolName(c.tool))
        o.put("shown", KUi.currentSubmenu)
        // tools that collect taps across actions (xy-to-xy: three taps make a figure)
        o.put("astroid", JSONArray().also { a -> for (p in KiddoPaint.Tools.Astroid.points) a.put(p.x).put(p.y) })
        o.put("stampSettings", JSONObject().also { j -> for ((k, v) in KCache.StampSettings) j.put(k, JSONArray().put(v.hueShift).put(v.altSize)) })
        o.put("cur", JSONObject()
            .put("globalAlpha", c.globalAlpha).put("scaling", c.scaling)
            .put("modified", c.modified).put("modifiedAlt", c.modifiedAlt).put("modifiedCtrl", c.modifiedCtrl)
            .put("modifiedToggle", c.modifiedToggle).put("modifiedMeta", c.modifiedMeta).put("modifiedTilde", c.modifiedTilde)
            .put("velToggle", c.velToggle).put("multiplier", c.multiplier)
            .put("prevEvTs", c.prevEvTs).put("velocity", c.velocity).put("velocityMultiplier", c.velocityMultiplier)
            .put("modifiedRange", c.modifiedRange).put("modifiedAltRange", c.modifiedAltRange).put("modifiedCtrlRange", c.modifiedCtrlRange)
            .put("prevEv", ev(c.prevEv)).put("ev", ev(c.ev)))
        o.put("display", JSONObject().put("step", KDisplay.step).put("undoOn", KDisplay.undoOn)
            .put("allowClearTmp", KDisplay.allowClearTmp).put("cursor", KDisplay.cursor)
            .put("undoData", KDisplay.undoData != null))
        o.put("ctx", JSONObject().put("main", KDisplay.main_context.exportState()).put("bnim", KDisplay.bnimContext.exportState())
            .put("anim", KDisplay.animContext.exportState()).put("preview", KDisplay.previewContext.exportState()).put("tmp", KDisplay.context.exportState()))
        val s = KAndroid.state
        o.put("android", JSONObject().put("lastPenTs", s.lastPenTs).put("penHovering", s.penHovering)
            .put("undoArmed", s.undoArmed).put("lastXY", pev(s.lastXY)).put("hasPenEraser", KAndroid.penEraser != null))
        return o
    }

    fun apply(o: JSONObject) {
        val wasEnabled = KSounds.enabled
        KSounds.enabled = false
        try {
            KCache.StampSettings.clear()
            o.optJSONObject("stampSettings")?.let { j -> for (k in j.keys()) j.getJSONArray(k).let { a -> KCache.StampSettings[k] = StampSetting(a.getDouble(0), a.getDouble(1)) } }
            AppState.restore(o.optString("app"))
            reapplySelection(o.optString("app"), o.optString("shown"))
        } finally { KSounds.enabled = wasEnabled }
        o.optJSONArray("astroid")?.let { a ->
            KiddoPaint.Tools.Astroid.points = (0 until a.length() / 2).map { XY(a.getDouble(2 * it), a.getDouble(2 * it + 1)) }.toMutableList()
        }
        val c = KCurrent
        o.optJSONObject("cur")?.let { j ->
            c.globalAlpha = j.optDouble("globalAlpha", 1.0); c.scaling = j.optDouble("scaling", 1.0)
            c.modified = j.optBoolean("modified"); c.modifiedAlt = j.optBoolean("modifiedAlt"); c.modifiedCtrl = j.optBoolean("modifiedCtrl")
            c.modifiedToggle = j.optBoolean("modifiedToggle"); c.modifiedMeta = j.optBoolean("modifiedMeta"); c.modifiedTilde = j.optBoolean("modifiedTilde")
            c.velToggle = j.optBoolean("velToggle"); c.multiplier = j.optInt("multiplier", 1)
            c.prevEvTs = j.optLong("prevEvTs"); c.velocity = j.optDouble("velocity", 0.0); c.velocityMultiplier = j.optDouble("velocityMultiplier", 1.0)
            c.modifiedRange = j.optInt("modifiedRange"); c.modifiedAltRange = j.optInt("modifiedAltRange"); c.modifiedCtrlRange = j.optInt("modifiedCtrlRange")
            c.prevEv = ev(j.optJSONObject("prevEv")); c.ev = ev(j.optJSONObject("ev"))
        }
        o.optJSONObject("display")?.let { j ->
            KDisplay.step = j.optInt("step"); KDisplay.undoOn = j.optBoolean("undoOn", true)
            KDisplay.allowClearTmp = j.optBoolean("allowClearTmp", true); KDisplay.cursor = j.optString("cursor", KDisplay.cursor)
            KDisplay.undoData = if (j.optBoolean("undoData")) true else null
        }
        o.optJSONObject("android")?.let { j ->
            val s = KAndroid.state
            s.lastPenTs = j.optLong("lastPenTs"); s.penHovering = j.optBoolean("penHovering"); s.undoArmed = j.optBoolean("undoArmed", true)
            s.lastXY = pev(j.optJSONObject("lastXY"))
            if (j.optBoolean("hasPenEraser")) { KAndroid.startTempEraser(); KAndroid.endTempEraser() }
        }
    }

    /** The layers' drawing state (after their pictures are in place: clearing uses the transform). */
    fun applyContexts(o: JSONObject) {
        o.optJSONObject("ctx")?.let { j ->
            j.optJSONObject("main")?.let { KDisplay.main_context.importState(it) }
            j.optJSONObject("bnim")?.let { KDisplay.bnimContext.importState(it) }
            j.optJSONObject("anim")?.let { KDisplay.animContext.importState(it) }
            j.optJSONObject("preview")?.let { KDisplay.previewContext.importState(it) }
            j.optJSONObject("tmp")?.let { KDisplay.context.importState(it) }
        }
    }

    /**
     * Pressing a tool re-applies its remembered options, but upstream resets the letter (alphabet)
     * and the stamp (sprites) to the first: put back the ones that were chosen.
     */
    private fun reapplySelection(appJson: String, shown: String) {
        val sel = runCatching { JSONObject(appJson).optJSONObject("sel") }.getOrNull() ?: return
        // a submenu other than the tool's own was showing (the spray sizes, after a ctrl-click)
        if (shown.isNotEmpty() && shown != "alphabet" && shown != KUi.currentSubmenu && KiddoPaint.Submenu[shown] != null) {
            show_generic_submenu(shown)
            val items = KiddoPaint.Submenu[shown] ?: emptyList()
            val g = sel.optJSONObject(shown)
            KAndroid.submenuGroups(items).forEachIndexed { gi, grp ->
                val bi = g?.optInt(gi.toString(), -1) ?: -1
                val idx = grp.getOrNull(bi) ?: return@forEachIndexed
                val d = items[idx]
                if (d.handler != null && !KAndroid.isActionOption(shown, d)) d.handler(Ev(type = "click"))
            }
            KAndroid.paintSubSelection(shown)
        }
        when (KUi.currentSubmenu) {
            "alphabet" -> {
                val i = sel.optJSONObject("__alphabet")?.optInt("0", -1) ?: -1
                if (i in 0 until KUi.alphabetLetters.size) { press_alphabet(i); KAndroid.letterClicked(i) }
            }
            "sprites" -> {
                val i = sel.optJSONObject("sprites")?.optInt("0", -1) ?: -1
                val items = KiddoPaint.Submenu["sprites"] ?: return
                val g = KAndroid.submenuGroups(items).firstOrNull() ?: return
                val idx = g.getOrNull(i) ?: return
                val d = items[idx]
                if (d.handler != null && !KAndroid.isActionOption("sprites", d)) {
                    d.handler(Ev(type = "click"))
                    KAndroid.subSelection["sprites"] = mutableMapOf(0 to i)
                    KAndroid.paintSubSelection("sprites")
                }
            }
        }
    }

    private fun ev(e: Ev?): Any = if (e == null) JSONObject.NULL else JSONObject()
        .put("x", e._x).put("y", e._y).put("type", e.type).put("ox", e.offsetX).put("oy", e.offsetY)
        .put("which", e.which).put("button", e.button).put("buttons", e.buttons).put("pressure", e.pressure)
        .put("pointerType", e.pointerType).put("shift", e.shiftKey).put("alt", e.altKey).put("ctrl", e.ctrlKey)
        .put("meta", e.metaKey).put("ts", e.timeStamp)

    private fun ev(j: JSONObject?): Ev? = j?.let {
        Ev(it.getDouble("x"), it.getDouble("y"), it.getString("type")).apply {
            offsetX = it.getDouble("ox"); offsetY = it.getDouble("oy"); which = it.getInt("which"); button = it.getInt("button")
            buttons = it.getInt("buttons"); pressure = it.getDouble("pressure"); pointerType = it.getString("pointerType")
            shiftKey = it.getBoolean("shift"); altKey = it.getBoolean("alt"); ctrlKey = it.getBoolean("ctrl"); metaKey = it.getBoolean("meta")
            timeStamp = it.getDouble("ts")
        }
    }

    private fun pev(e: PEvent?): Any = if (e == null) JSONObject.NULL else JSONObject()
        .put("id", e.pointerId).put("type", e.pointerType).put("x", e.clientX).put("y", e.clientY).put("p", e.pressure)
        .put("buttons", e.buttons).put("primary", e.isPrimary).put("button", e.button).put("ts", e.timeStamp)

    private fun pev(j: JSONObject?): PEvent? = j?.let {
        PEvent(it.getInt("id"), it.getString("type"), it.getDouble("x"), it.getDouble("y"), it.getDouble("p"),
            it.getInt("buttons"), it.getBoolean("primary"), it.getInt("button"), it.getDouble("ts"))
    }
}
