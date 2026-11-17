package dev.kidpix.tablet

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.provider.MediaStore
import android.util.Size
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Serves the pictures saved by Kid Pix (Pictures/Kid Pix) to the page:
 *   /pictures/list        -> JSON [{id, added}] newest first
 *   /pictures/thumb/<id>  -> JPEG thumbnail
 *   /pictures/full/<id>   -> the saved PNG
 * Only pictures this app saved are visible to it, so kids can't browse other photos.
 */
class PicturesHandler(private val context: Context) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? = try {
        when {
            path == "list" -> respond("application/json", list().toString().toByteArray())
            path.startsWith("thumb/") -> respond("image/jpeg", thumb(path.removePrefix("thumb/").toLong()))
            path.startsWith("full/") -> {
                val uri = uri(path.removePrefix("full/").toLong())
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                respond("image/png", bytes)
            }
            else -> null
        }
    } catch (e: Exception) {
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, ByteArrayInputStream(ByteArray(0)))
    }

    private fun uri(id: Long) = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    private fun list(): JSONArray {
        val out = JSONArray()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.IS_PENDING} = 0",
            arrayOf(SAVE_DIR + "/"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                out.put(JSONObject().put("id", c.getLong(0)).put("added", c.getLong(1)))
            }
        }
        return out
    }

    private fun thumb(id: Long): ByteArray {
        val src = context.contentResolver.loadThumbnail(uri(id), Size(480, 360), null)
        // saved drawings can be transparent; JPEG would turn that black
        val bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    }

    private fun respond(mime: String, bytes: ByteArray) =
        WebResourceResponse(mime, null, ByteArrayInputStream(bytes)).apply {
            responseHeaders = mapOf("Cache-Control" to "no-store")
        }

    companion object {
        val SAVE_DIR = android.os.Environment.DIRECTORY_PICTURES + "/Kid Pix"
    }
}
