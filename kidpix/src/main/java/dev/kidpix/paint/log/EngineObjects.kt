package dev.kidpix.paint.log

import dev.kidpix.paint.engine.Engine
import dev.kidpix.paint.KAlphabet
import dev.kidpix.paint.KCache
import dev.kidpix.paint.KColors
import dev.kidpix.paint.KCurrent
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KSounds
import dev.kidpix.paint.KSprite
import dev.kidpix.paint.KStamps
import dev.kidpix.paint.KUi
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.WorldSlot
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.PriorityQueue

/**
 * The engine's state outside the `by world { }` slots: the fields of the shared objects (current
 * tool and colours, the layer canvases, pages, caches, the clock), copied so they can be put
 * back exactly (the play button's replay runs the engine in a fresh world and then comes back).
 * Collections are copied (the replay clears and refills some of them in place); everything else
 * is kept by reference: the replay works on new objects, so the live ones stay untouched.
 */
internal class EngineObjects private constructor(private val fields: List<Triple<Field, Any?, Any?>>) {

    fun restore() {
        for ((f, target, v) in fields) {
            if (Modifier.isFinal(f.modifiers)) {
                val cur = f.get(target)
                @Suppress("UNCHECKED_CAST")
                when {
                    cur is MutableMap<*, *> && v is Map<*, *> -> { (cur as MutableMap<Any?, Any?>).clear(); cur.putAll(copy(v) as Map<Any?, Any?>) }
                    cur is MutableCollection<*> && v is Collection<*> -> { (cur as MutableCollection<Any?>).clear(); cur.addAll(copy(v) as Collection<Any?>) }
                }
            } else f.set(target, copy(v))
        }
    }

    companion object {
        /** Fields left alone: the app's UI hook, the sound player (a replay swaps it and puts it back itself). */
        private val SKIP = setOf("ui", "player")

        private fun targets(): List<Any> = listOf(KCurrent, KStamps, KAlphabet, KSprite, KUi, KDisplay, KColors.Current,
            KCache, KCache.Defaults, KTime, KSounds, Engine, KTime.backend)

        fun take(): EngineObjects {
            val out = ArrayList<Triple<Field, Any?, Any?>>()
            for (obj in targets()) {
                var c: Class<*>? = obj.javaClass
                while (c != null && c != Any::class.java) {
                    for (f in c.declaredFields) {
                        if (f.isSynthetic || f.name == "INSTANCE" || f.name in SKIP || f.name.endsWith("\$delegate")) continue
                        val static = Modifier.isStatic(f.modifiers)
                        // a Kotlin object keeps its properties in static fields; a plain instance (the clock) in instance fields
                        val target = if (static) null else obj
                        if (static && obj === KTime.backend) continue
                        runCatching { f.isAccessible = true }.getOrNull() ?: continue
                        val v = f.get(target)
                        if (v is WorldSlot<*>) continue
                        if (Modifier.isFinal(f.modifiers) && v !is MutableMap<*, *> && v !is MutableCollection<*>) continue
                        out.add(Triple(f, target, copy(v)))
                    }
                    c = if (obj === KTime.backend) c.superclass else null
                }
            }
            return EngineObjects(out)
        }

        private fun copy(v: Any?): Any? = when (v) {
            is PriorityQueue<*> -> PriorityQueue(v)
            is LinkedHashMap<*, *> -> LinkedHashMap<Any?, Any?>().also { m -> v.forEach { (k, x) -> m[k] = copy(x) } }
            is HashMap<*, *> -> HashMap<Any?, Any?>().also { m -> v.forEach { (k, x) -> m[k] = copy(x) } }
            is java.util.TreeMap<*, *> -> java.util.TreeMap(v)
            is LinkedHashSet<*> -> LinkedHashSet(v)
            is HashSet<*> -> HashSet(v)
            is ArrayList<*> -> ArrayList(v.map { copy(it) })
            is java.util.ArrayDeque<*> -> java.util.ArrayDeque(v)
            else -> v
        }
    }
}
