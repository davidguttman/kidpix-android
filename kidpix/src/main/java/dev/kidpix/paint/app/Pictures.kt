package dev.kidpix.paint.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream

/**
 * The pictures Kid Pix saves, in the shared Pictures collection under Pictures/Kid Pix, the same
 * place and naming as the WebView build (app/.../Pictures.kt + MainActivity.savePng), so after the
 * takeover (applicationId dev.kidpix.tablet) the pictures it saved open here too. Only pictures
 * this app saved are visible to it (no photo permission), so kids can't browse other photos.
 */
class Pictures(private val context: Context) {
    class Pic(val id: Long, val added: Long)

    /** Inserts a PNG as `kidpix-<ms>.png`; its MediaStore id, or null if anything failed. */
    fun savePng(bytes: ByteArray): Long? = try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "kidpix-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, SAVE_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        ContentUris.parseId(uri)
    } catch (e: Exception) {
        null
    }

    private fun uri(id: Long) = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    /** Newest first, like /pictures/list. */
    fun list(): List<Pic> = listOrNull() ?: emptyList()

    /** [list], or null when the gallery could not be asked (nothing can be concluded from it). */
    fun listOrNull(): List<Pic>? {
        val out = ArrayList<Pic>()
        val c = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.IS_PENDING} = 0",
            arrayOf("$SAVE_DIR/"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        ) ?: return null
        c.use { while (it.moveToNext()) out.add(Pic(it.getLong(0), it.getLong(1))) }
        return out
    }

    /**
     * The thumbnail, decoded ourselves on white: Android's cached thumbnails turn transparent
     * pixels (pictures saved before 0.3.0) black.
     */
    fun thumb(id: Long): Bitmap {
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri(id))) { d, info, _ ->
            val scale = minOf(1f, 480f / info.size.width, 360f / info.size.height)
            d.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
        return bmp
    }

    /** The saved picture as it is, and the hash of its file (a save point is only for that exact file). */
    fun fullWithHash(id: Long): Pair<Bitmap, String> {
        val bytes = context.contentResolver.openInputStream(uri(id))!!.use { it.readBytes() }
        val b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return (if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)) to hash(bytes)
    }

    /** The saved picture as it is (an <img> of /pictures/full/<id>). */
    fun full(id: Long): Bitmap {
        val b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri(id))) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
    }

    companion object {
        val SAVE_DIR = Environment.DIRECTORY_PICTURES + "/Kid Pix"

        /** SHA-256 of a saved file, hex. */
        fun hash(bytes: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * A save point's picture (the canvas, possibly with see-through parts) as the picker shows
         * pictures: on white, at most 480 x 360.
         */
        fun thumbOf(picture: Bitmap): Bitmap {
            val s = minOf(1f, 480f / picture.width, 360f / picture.height)
            val w = maxOf(1, (picture.width * s).toInt()); val h = maxOf(1, (picture.height * s).toInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply {
                drawColor(Color.WHITE)
                drawBitmap(picture, null, android.graphics.Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
            return bmp
        }

        /** save_to_file: white paper behind the drawing, so it looks right in the gallery and the picker. */
        fun flatten(picture: Bitmap): ByteArray {
            val flat = Bitmap.createBitmap(picture.width, picture.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(picture, 0f, 0f, null) }
            return ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }
}
