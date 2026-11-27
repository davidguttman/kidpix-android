package dev.kidpix.paint.engine

import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KiddoPaint
import dev.kidpix.paint.PictureStore
import dev.kidpix.paint.init_kiddo_paint

/** Boots the engine like `init_kiddo_paint()` on page load. */
object Engine {
    var started = false
        private set

    fun start(width: Int, height: Int, store: PictureStore?) {
        KiddoPaint.resetWorld()
        KTime.resetHandles()
        KDisplay.store = store
        init_kiddo_paint(width, height)
        started = true
    }
}
