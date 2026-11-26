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

    /** Inserts a PNG as `kidpix-<ms>.png`; false if anything failed. */
    fun savePng(bytes: ByteArray): Boolean = try {
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
        true
    } catch (e: Exception) {
        false
    }

    private fun uri(id: Long) = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    /** Newest first, like /pictures/list. */
    fun list(): List<Pic> {
        val out = ArrayList<Pic>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.IS_PENDING} = 0",
            arrayOf("$SAVE_DIR/"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) out.add(Pic(c.getLong(0), c.getLong(1)))
        }
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

    /** The saved picture as it is (an <img> of /pictures/full/<id>). */
    fun full(id: Long): Bitmap {
        val b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri(id))) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
    }

    companion object {
        val SAVE_DIR = Environment.DIRECTORY_PICTURES + "/Kid Pix"

        /** save_to_file: white paper behind the drawing, so it looks right in the gallery and the picker. */
        fun flatten(picture: Bitmap): ByteArray {
            val flat = Bitmap.createBitmap(picture.width, picture.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(picture, 0f, 0f, null) }
            return ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }
}
