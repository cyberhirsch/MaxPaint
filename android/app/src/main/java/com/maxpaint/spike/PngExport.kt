package com.maxpaint.spike

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * Saves the finished canvas where a gallery app will find it.
 *
 * On Android 10 and up MediaStore takes the file with no permission at all. The
 * older path needs WRITE_EXTERNAL_STORAGE, so if that has not been granted we
 * fall back to the app's own Pictures folder rather than failing outright --
 * the painting is saved either way, only the gallery listing is lost.
 */
object PngExport {

    fun save(ctx: Context, bitmap: Bitmap, name: String): String {
        val fileName = "$name.png"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(ctx, bitmap, fileName)
            } else {
                saveToPublicDir(ctx, bitmap, fileName)
            }
        } catch (e: Exception) {
            "Could not save: ${e.message}"
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveViaMediaStore(ctx: Context, bitmap: Bitmap, fileName: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MaxPaint")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return "Could not save: no media entry"

        resolver.openOutputStream(uri).use { out ->
            if (out == null) return "Could not save: no stream"
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Saved to Pictures/MaxPaint/$fileName"
    }

    private fun saveToPublicDir(ctx: Context, bitmap: Bitmap, fileName: String): String {
        val granted = ctx.checkSelfPermission(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val dir = if (granted) {
            File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "MaxPaint")
        } else {
            File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "MaxPaint")
        }
        dir.mkdirs()
        val file = File(dir, fileName)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        if (granted) {
            // otherwise the gallery does not notice the new file until a rescan
            android.media.MediaScannerConnection.scanFile(
                ctx, arrayOf(file.absolutePath), arrayOf("image/png"), null
            )
        }
        return "Saved to ${file.absolutePath}"
    }
}
