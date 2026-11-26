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
}

class WorldSlot<T>(private val init: () -> T) {
    private var gen = -1
    private var value: T? = null

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
