package app.homeeye.ui.live

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Snapshots {
    /** Saves to Pictures/HomeEye. Needs no permission on Android 10+. */
    fun saveToGallery(context: Context, bitmap: Bitmap, cameraName: String): Uri? {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val safeName = cameraName.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifEmpty { "camera" }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${safeName}_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HomeEye")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Small private preview for the camera list. */
    fun saveThumbnail(bitmap: Bitmap, file: File) {
        val w = 640
        val h = (bitmap.height * w / bitmap.width.toFloat()).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        tmp.renameTo(file)
    }
}
