@file:JvmName("util_Display")

package dev.kidpix.paint

import android.graphics.Bitmap
import dev.kidpix.paint.canvas.BitmapSource
import dev.kidpix.paint.canvas.CanvasImageSource
import dev.kidpix.paint.canvas.Ctx2D
import dev.kidpix.paint.canvas.ImageData
import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.canvas.KImage
import java.util.concurrent.Executor

// Port of js/util/display.js, plus the undo/redo the WebView build added in
// overlay/android.js and the layer setup from init_kiddo_paint.

/** Where saveToLocalStorage/loadFromLocalStorage keep the picture (the app: private files). */
interface PictureStore {
    /** Called on the main thread with a private copy; implementations encode/write off-thread. */
    fun save(picture: Bitmap)

    /**
     * The picture changed; [snapshot] (main thread only) makes the private copy. A store may wait
     * and take one copy for several changes. Default: save at once.
     */
    fun changed(snapshot: () -> Bitmap) = save(snapshot())
    fun load(): Bitmap?
    fun clear()
}

/** KiddoPaint.Display */
object KDisplay {
    // layers, created by init_layers (init_kiddo_paint in the JS); z-order bottom to top:
    // main_canvas, bnimCanvas, animCanvas, previewCanvas, canvas (tmp)
    lateinit var main_canvas: KCanvas
    lateinit var main_context: Ctx2D
    lateinit var bnimCanvas: KCanvas
    lateinit var bnimContext: Ctx2D
    lateinit var animCanvas: KCanvas
    lateinit var animContext: Ctx2D
    lateinit var previewCanvas: KCanvas
    lateinit var previewContext: Ctx2D
    /** tmpCanvas: tools draw here, saveMain copies it down onto main. */
    lateinit var canvas: KCanvas
    lateinit var context: Ctx2D

    var step = 0
    /** Upstream checks this for truthiness; with the history it is non-null when undo is possible. */
    var undoData: Any? = null
    var undoOn = true
    var allowClearTmp = true
    /** canvas.classList cursor class. */
    var cursor = "cursor-pencil"

    var store: PictureStore? = null

    fun init_layers(width: Int, height: Int) {
        main_canvas = KCanvas(width, height); main_context = main_canvas.getContext("2d")
        main_context.imageSmoothingEnabled = false
        bnimCanvas = KCanvas(width, height).also { it.id = "bnimCanvas"; it.className = "pixelated" }
        bnimContext = bnimCanvas.getContext("2d"); bnimContext.imageSmoothingEnabled = false
        animCanvas = KCanvas(width, height).also { it.id = "animCanvas"; it.className = "pixelated" }
        animContext = animCanvas.getContext("2d"); animContext.imageSmoothingEnabled = false
        previewCanvas = KCanvas(width, height).also { it.id = "previewCanvas"; it.className = "pixelated" }
        previewContext = previewCanvas.getContext("2d"); previewContext.imageSmoothingEnabled = false
        canvas = KCanvas(width, height).also { it.id = "tmpCanvas"; it.className = "pixelated" }
        context = canvas.getContext("2d"); context.imageSmoothingEnabled = false
        context.globalAlpha = 1.0; previewContext.globalAlpha = 1.0
        bnimContext.globalAlpha = 1.0; animContext.globalAlpha = 1.0
        History.clear()
        undoData = null
        undoOn = true
        allowClearTmp = true
    }

    val isReady get() = ::main_canvas.isInitialized

    fun clearAll() {
        // clearing anim is resp of callers
        saveUndo()
        clearPreview()
        clearTmp()
        clearMain()
        clearLocalStorage()
        dev.kidpix.paint.log.KLog.clearedAll() // a new picture: the action log starts a new segment
    }

    fun clearMain() = main_context.clearRect(0, 0, main_canvas.width, main_canvas.height)

    fun clearTmp() {
        if (allowClearTmp) context.clearRect(0, 0, canvas.width, canvas.height)
    }

    fun clearPreview() = previewContext.clearRect(0, 0, canvas.width, canvas.height)
    fun clearAnim() = animContext.clearRect(0, 0, canvas.width, canvas.height)
    fun clearBnim() = bnimContext.clearRect(0, 0, canvas.width, canvas.height)

    fun clearBeforeSaveMain() {
        if (saveUndo()) {
            clearMain()
            main_context.drawImage(canvas, 0, 0)
            clearTmp()
            saveToLocalStorage()
        }
    }

    fun saveMainGco(op: String) {
        if (saveUndo()) {
            val prevGco = main_context.globalCompositeOperation
            main_context.globalCompositeOperation = op
            main_context.drawImage(canvas, 0, 0)
            main_context.globalCompositeOperation = prevGco
            clearTmp()
            saveToLocalStorage()
        }
    }

    fun saveMainGcoSkipUndo(op: String) {
        val prevGco = main_context.globalCompositeOperation
        main_context.globalCompositeOperation = op
        main_context.drawImage(canvas, 0, 0)
        main_context.globalCompositeOperation = prevGco
        clearTmp()
        saveToLocalStorage()
    }

    fun saveMain() {
        if (saveUndo()) {
            main_context.drawImage(canvas, 0, 0)
            clearTmp()
            saveToLocalStorage()
        }
    }

    fun saveMainSkipUndo() {
        main_context.drawImage(canvas, 0, 0)
        clearTmp()
        saveToLocalStorage()
    }

    fun pauseUndo() { undoOn = false }
    fun resumeUndo() { undoOn = true }
    fun toggleUndo() { undoOn = !undoOn }

    /** Pushes the current main picture onto the history (if undo is on). */
    fun saveUndo(): Boolean {
        if (undoOn) {
            History.push(main_canvas.bitmap)
            undoData = true
        }
        return undoOn
    }

    /** One step back. Returns false when there is nothing to undo. */
    fun undo(doClearMain: Boolean = true): Boolean {
        val snap = History.undo(main_canvas.bitmap) ?: return false
        show(snap, doClearMain)
        undoData = if (History.canUndo) true else null
        return true
    }

    /** One step forward again (Undo Guy double tap in the Android build). */
    fun redo(doClearMain: Boolean = true): Boolean {
        val snap = History.redo(main_canvas.bitmap) ?: return false
        show(snap, doClearMain)
        undoData = true
        return true
    }

    private fun show(bmp: Bitmap, doClearMain: Boolean) {
        // clearMain + drawImage in one copy (snapshots are the canvas's size)
        if (doClearMain && bmp.width == main_canvas.width && bmp.height == main_canvas.height) main_context.replaceWith(BitmapSource(bmp))
        else {
            if (doClearMain) clearMain()
            main_context.drawImage(bmp, 0, 0)
        }
        saveToLocalStorage()
    }

    fun clearLocalStorage() { store?.clear() }

    fun saveToLocalStorage() {
        val s = store ?: return
        s.changed { main_canvas.snapshot() }
    }

    /** Saved picture if any, else the Android build's opening page fitted to the canvas. */
    fun loadFromLocalStorage() {
        val saved = runCatching { store?.load() }.getOrNull()
        if (saved != null) {
            clearMain()
            val c = main_canvas
            if (saved.width <= c.width && saved.height <= c.height) {
                main_context.drawImage(saved, 0, 0)
            } else {
                // Saved on a canvas of another shape (older builds sized it to a portrait window):
                // shrink to fit, as opening a picture does, rather than cut part of it off.
                val s = minOf(c.width.toDouble() / saved.width, c.height.toDouble() / saved.height)
                val w = saved.width * s
                val h = saved.height * s
                main_context.imageSmoothingEnabled = true
                main_context.drawImage(saved, (c.width - w) / 2, (c.height - h) / 2, w, h)
                main_context.imageSmoothingEnabled = false
            }
            return
        }
        if (store == null) return // headless: start blank
        val img = KImage()
        img.onload = {
            val c = main_canvas
            val s = minOf(c.width.toDouble() / img.width, c.height.toDouble() / img.height)
            val w = img.width * s
            val h = img.height * s
            clearMain()
            main_context.imageSmoothingEnabled = true
            main_context.imageSmoothingQuality = "high"
            main_context.drawImage(img, (c.width - w) / 2, (c.height - h) / 2, w, h)
            main_context.imageSmoothingEnabled = false
        }
        img.src = "android/opening.png"
    }

    fun canvasToImageData(canvas: KCanvas): ImageData =
        canvas.getContext("2d").getImageData(0, 0, canvas.width, canvas.height)

    fun imageTypeToCanvas(imageData: ImageData): KCanvas {
        val canvas = KCanvas(imageData.width, imageData.height)
        canvas.getContext("2d").putImageData(imageData, 0, 0)
        return canvas
    }

    /** imageTypeToCanvas(img, true): draw an image/canvas instead of putting pixels. */
    fun imageTypeToCanvas(image: CanvasImageSource, doDraw: Boolean = true): KCanvas {
        val canvas = KCanvas(image.width, image.height)
        canvas.getContext("2d").drawImage(image, 0, 0)
        return canvas
    }
}

/**
 * Undo and redo without a step limit. Every snapshot is a stored frame ([History.frames], see
 * log/Frames.kt: a delta of the pixels that changed, written off the main thread), so the history
 * costs little memory and survives the process (the action log's checkpoints name the frames).
 * The [KEEP] snapshots nearest the current picture on each side stay unpacked in memory (or are
 * unpacked again, in the background, by [loader]), so a tap of the Undo Guy only copies pixels.
 */
object History {
    /** Snapshots kept unpacked at the top of each stack. */
    const val KEEP = 3
    /** Where snapshots are stored. Headless: in memory, written at once. */
    var frames: dev.kidpix.paint.log.FrameStore = dev.kidpix.paint.log.FrameStore(dev.kidpix.paint.log.FrameStore.MemBackend(), null)
    /** Unpacks snapshots near the top in the background (null: on demand). */
    var loader: Executor? = null
    /** Id of the next frame (snapshots and checkpoint pictures share the numbering). */
    var nextId = 1L
    /** Called with every new snapshot's id (the action log counts them). */
    var onPush: ((Long) -> Unit)? = null

    private class Snap(val id: Long, bmp: Bitmap?) {
        @Volatile var bitmap: Bitmap? = bmp
        fun get(): Bitmap? = bitmap ?: frames.get(id)?.also { bitmap = it }
    }

    private val past = ArrayList<Snap>()
    private val future = ArrayList<Snap>()

    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    val pastIds: List<Long> get() = past.map { it.id }
    val futureIds: List<Long> get() = future.map { it.id }

    fun clear() { past.clear(); future.clear(); lastStored = null }

    /** The whole in-memory history as it is now (stacks, next id, frame store), to put back exactly. */
    class Saved internal constructor(
        internal val past: List<Any>, internal val future: List<Any>, internal val nextId: Long,
        internal val lastStored: Any?, internal val frames: dev.kidpix.paint.log.FrameStore,
    )

    fun save(): Saved = Saved(ArrayList(past), ArrayList(future), nextId, lastStored, frames)

    /** The frame [b] is as it is now (its id and copy), if [b] has not changed since it was stored. */
    fun currentFrameOf(b: Bitmap): Pair<Long, Bitmap>? {
        val last = lastStored ?: return null
        return if (last.first === b && last.second == b.generationId) last.third else null
    }

    /** Back to [s] (the play button's replay used the history in between). */
    @Suppress("UNCHECKED_CAST")
    fun restore(s: Saved) {
        past.clear(); future.clear()
        past.addAll(s.past as List<Snap>); future.addAll(s.future as List<Snap>)
        nextId = s.nextId
        lastStored = s.lastStored as Triple<Bitmap, Int, Pair<Long, Bitmap>>?
        frames = s.frames
        settle()
    }

    /** Back to a stored history (a checkpoint): ids oldest first; [next] the next free id. */
    fun restore(pastIds: List<Long>, futureIds: List<Long>, next: Long) {
        clear()
        pastIds.forEach { past.add(Snap(it, null)) }
        futureIds.forEach { future.add(Snap(it, null)) }
        nextId = maxOf(next, (pastIds + futureIds).maxOrNull()?.plus(1) ?: 1L)
        settle()
    }

    /** Forgets the oldest undo step (the storage cap). */
    fun dropOldest(): Boolean { if (past.isEmpty()) return false; past.removeAt(0); return true }

    /** A new frame of [b] (a private copy), stored; its id. Kept if a frame with that id exists (replay). */
    fun store(b: Bitmap): Pair<Long, Bitmap> {
        // unchanged since the last frame (a checkpoint right before the next undo step): the same frame
        val last = lastStored
        if (last != null && last.first === b && last.second == b.generationId && frames.has(last.third.first)) return last.third
        val id = nextId++
        val copy = b.copy(Bitmap.Config.ARGB_8888, false)
        if (!frames.has(id)) frames.put(id, copy)
        onPush?.invoke(id)
        lastStored = Triple(b, b.generationId, id to copy)
        return id to copy
    }
    private var lastStored: Triple<Bitmap, Int, Pair<Long, Bitmap>>? = null

    /**
     * [b] (as it is now) is frame [id] ([copy]): the next [store] of an unchanged [b] reuses it. The
     * action log calls it after a checkpoint and after restoring one, so a replay numbers frames
     * exactly as the live app did.
     */
    fun remember(b: Bitmap, id: Long, copy: Bitmap) { lastStored = Triple(b, b.generationId, id to copy) }

    private fun snap(b: Bitmap): Snap { val (id, copy) = store(b); return Snap(id, copy) }

    /** After a change of the stacks: the top [KEEP] of each unpacked, the rest dropped from memory. */
    private fun settle() {
        for (stack in arrayOf(past, future)) {
            for ((i, s) in stack.withIndex()) {
                val top = i >= stack.size - KEEP
                if (top && s.bitmap == null) loader?.execute { synchronized(s) { s.get() } }
                else if (!top && s.bitmap != null) s.bitmap = null
            }
        }
    }

    private fun take(stack: ArrayList<Snap>): Bitmap? {
        while (stack.isNotEmpty()) {
            val s = stack.removeAt(stack.size - 1)
            synchronized(s) { s.get() }?.let { return it }
            // a frame that cannot be read (damaged storage): skip that step
        }
        return null
    }

    fun push(current: Bitmap) {
        past.add(snap(current))
        future.clear()
        settle()
    }

    fun undo(current: Bitmap): Bitmap? {
        if (past.isEmpty()) return null
        future.add(snap(current))
        val b = take(past)
        if (b == null) future.removeAt(future.size - 1)
        settle()
        return b
    }

    fun redo(current: Bitmap): Bitmap? {
        if (future.isEmpty()) return null
        past.add(snap(current))
        val b = take(future)
        if (b == null) past.removeAt(past.size - 1)
        settle()
        return b
    }
}
