package dev.kidpix.paint.engine

import kotlin.reflect.KProperty

/**
 * Per-world singletons. Upstream objects such as `KiddoPaint.Tools.Pencil = new Toolbox.Pencil()`
 * are created once per page load. Here they are created lazily per "world": [World.reset] (a new
 * scenario, a fresh app start) makes every `by world { }` property re-run its initializer.
 *
 *     val KiddoPaint.Tools.Pencil by world { ToolboxPencil() }
 *     var KiddoPaint.Submenu.sprites by world { mutableListOf<SubmenuItem>() }
 */
object World {
    var generation = 0
        private set

    fun reset() { generation++ }

    /** Every `by world { }` slot (they are created once, when their file's class loads). */
    internal val slots = java.util.Collections.synchronizedList(ArrayList<WorldSlot<*>>())

    /** The values of this world's slots (to come back to this world after a replay ran in another). */
    class Saved internal constructor(internal val values: List<Pair<WorldSlot<*>, Any?>>)

    fun save(): Saved = Saved(synchronized(slots) { slots.filter { it.gen == generation }.map { it to it.value } })

    /** Back to the world [s] was taken of: its very objects (slots it had not created yet start fresh). */
    fun restore(s: Saved) {
        generation++
        for ((slot, v) in s.values) slot.put(v)
    }
}

class WorldSlot<T>(private val init: () -> T) {
    internal var gen = -1
    internal var value: T? = null

    init { World.slots.add(this) }

    @Suppress("UNCHECKED_CAST")
    internal fun put(v: Any?) { gen = World.generation; value = v as T? }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        if (gen != World.generation) { gen = World.generation; value = init() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, v: T) {
        gen = World.generation; value = v
    }
}

fun <T> world(init: () -> T) = WorldSlot(init)
