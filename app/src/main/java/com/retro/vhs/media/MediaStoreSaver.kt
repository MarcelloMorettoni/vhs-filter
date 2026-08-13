package com.retro.vhs.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileDescriptor

/**
 * A place to put the finished tape. On Android 10 and up we hand MediaMuxer a
 * MediaStore file descriptor directly, so nothing is ever copied twice.
 */
object MediaStoreSaver {

    private const val TAG = "MediaStoreSaver"
    const val ALBUM = "VHS-88"

    class Output(
        val uri: Uri?,
        val parcel: ParcelFileDescriptor?,
        val path: String?
    ) {
        val fileDescriptor: FileDescriptor? get() = parcel?.fileDescriptor
    }

    fun createVideoOutput(context: Context, displayName: String): Output {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
                put(MediaStore.Video.Media.IS_PENDING, 1)
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("could not create a MediaStore entry")
            val parcel = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("could not open the MediaStore entry")
            return Output(uri, parcel, null)
        }

        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), ALBUM
        )
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("could not create $dir")
        val file = File(dir, displayName)
        return Output(null, null, file.absolutePath)
    }

    /** Publishes the entry so it shows up in the gallery. */
    fun finish(context: Context, output: Output, success: Boolean) {
        try {
            output.parcel?.close()
        } catch (e: Exception) {
            Log.w(TAG, "closing descriptor failed", e)
        }

        val uri = output.uri
        if (uri != null) {
            if (success) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, values, null, null)
            } else {
                context.contentResolver.delete(uri, null, null)
            }
            return
        }

        val path = output.path ?: return
        if (success) {
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("video/mp4"), null)
        } else {
            File(path).delete()
        }
    }

    fun fileName(prefix: String): String {
        val stamp = android.text.format.DateFormat.format("yyyyMMdd_HHmmss", System.currentTimeMillis())
        return "${prefix}_$stamp.mp4"
    }
}
