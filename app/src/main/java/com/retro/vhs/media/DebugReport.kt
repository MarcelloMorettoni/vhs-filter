package com.retro.vhs.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a plain text diagnostic dump somewhere the user can actually get at it.
 * Everything needed to work out why a particular device frames the picture wrong
 * lives in here, so nobody has to guess from a screenshot.
 */
object DebugReport {

    private const val FOLDER = "VHS-88"

    class Saved(val uri: Uri?, val path: String, val text: String)

    fun save(context: Context, text: String): Saved {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "vhs88-debug-$stamp.txt"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("could not create the report")
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: throw IllegalStateException("could not write the report")
            return Saved(uri, "Download/$FOLDER/$name", text)
        }

        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER
        )
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("could not create $dir")
        val file = File(dir, name)
        file.writeText(text)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        return Saved(null, file.absolutePath, text)
    }
}
