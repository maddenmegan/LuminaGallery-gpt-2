package com.lumina.gallery.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlin.math.max

 data class MediaAsset(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val relativePath: String,
    val size: Long,
    val modifiedSeconds: Long,
    val dateTakenMillis: Long,
    val mediaType: Int,
    val isTrashed: Boolean = false
) {
    val folder: String get() = relativePath.trimEnd('/').substringAfterLast('/').ifBlank { "Internal storage" }
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isGif: Boolean get() = mimeType.equals("image/gif", ignoreCase = true)
    val isPdf: Boolean get() = mimeType.equals("application/pdf", ignoreCase = true)
    val isScreenshot: Boolean get() = relativePath.contains("screenshot", true) || name.contains("screenshot", true)
}

object MediaRepository {
    fun query(context: Context, includeTrash: Boolean = false): List<MediaAsset> {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val projection = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.Images.ImageColumns.DATE_TAKEN)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.MediaColumns.RELATIVE_PATH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.MediaColumns.IS_TRASHED)
        }.toTypedArray()

        val clauses = mutableListOf("(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.MediaColumns.MIME_TYPE}=?)")
        val args = mutableListOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            "application/pdf"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            clauses += "${MediaStore.MediaColumns.IS_TRASHED}=?"
            args += if (includeTrash) "1" else "0"
        }
        val sort = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC, ${MediaStore.Files.FileColumns._ID} DESC"
        val result = ArrayList<MediaAsset>(4096)
        resolver.query(collection, projection, clauses.joinToString(" AND "), args.toTypedArray(), sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val takenCol = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN)
            val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val trashCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED) else -1
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val mime = cursor.getString(mimeCol).orEmpty()
                val path = if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else ""
                result += MediaAsset(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = cursor.getString(nameCol).orEmpty(),
                    mimeType = mime,
                    relativePath = path,
                    size = max(0L, cursor.getLong(sizeCol)),
                    modifiedSeconds = cursor.getLong(dateCol),
                    dateTakenMillis = if (takenCol >= 0 && !cursor.isNull(takenCol)) cursor.getLong(takenCol) else 0L,
                    mediaType = cursor.getInt(typeCol),
                    isTrashed = trashCol >= 0 && cursor.getInt(trashCol) == 1
                )
            }
        }
        return result
    }

    fun observe(context: Context, onChanged: () -> Unit): ContentObserver {
        val handler = Handler(Looper.getMainLooper())
        var pending: Runnable? = null
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                pending?.let(handler::removeCallbacks)
                val task = Runnable { onChanged() }
                pending = task
                handler.postDelayed(task, 1_500L)
            }
        }
        context.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
        return observer
    }

    fun unregister(context: Context, observer: ContentObserver?) {
        if (observer != null) runCatching { context.contentResolver.unregisterContentObserver(observer) }
    }

    fun updateDisplayName(context: Context, asset: MediaAsset, newName: String): Boolean {
        val safeName = newName.trim().takeIf { it.isNotBlank() && !it.contains('/') && !it.contains('\\') } ?: return false
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, safeName) }
        return runCatching { context.contentResolver.update(asset.uri, values, null, null) > 0 }.getOrDefault(false)
    }
}
