package com.lumina.gallery.data

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.security.MessageDigest

object FileActions {
    fun transferToTree(context: Context, assets: List<MediaAsset>, treeUri: Uri, move: Boolean): Pair<Int, String?> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return 0 to "The selected folder is not accessible."
        var copied = 0
        val errors = mutableListOf<String>()
        assets.forEach { asset ->
            runCatching {
                val mime = asset.mimeType.ifBlank { "application/octet-stream" }
                val destination = root.createFile(mime, asset.name) ?: error("Could not create ${asset.name}")
                val input = context.contentResolver.openInputStream(asset.uri) ?: error("Could not read ${asset.name}")
                val output = context.contentResolver.openOutputStream(destination.uri) ?: error("Could not write ${asset.name}")
                input.use { source -> output.use { target -> source.copyTo(target) } }
                copied++
            }.onFailure { errors += "${asset.name}: ${it.localizedMessage ?: "copy failed"}" }
        }
        if (move && copied > 0) {
            val failed = assets.filterNot { asset ->
                runCatching { root.findFile(asset.name)?.let { it.length() > 0L } == true }.getOrDefault(false)
            }
            if (failed.isNotEmpty()) return copied to "Copied $copied item(s), but move verification failed for ${failed.size}. Originals were kept."
        }
        return copied to errors.take(4).joinToString("\n").ifBlank { null }
    }

    fun cleanExifCopy(context: Context, asset: MediaAsset): Uri? = runCatching {
        if (!asset.isImage) return null
        val source = context.contentResolver.openInputStream(asset.uri) ?: return null
        val file = File(context.cacheDir, "safe-${System.currentTimeMillis()}-${asset.name}")
        source.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        runCatching {
            ExifInterface(file.absolutePath).apply {
                listOf(
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_ALTITUDE,
                    ExifInterface.TAG_GPS_ALTITUDE_REF,
                    ExifInterface.TAG_GPS_TIMESTAMP,
                    ExifInterface.TAG_GPS_DATESTAMP,
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_SOFTWARE,
                    ExifInterface.TAG_ARTIST,
                    ExifInterface.TAG_COPYRIGHT,
                    ExifInterface.TAG_IMAGE_DESCRIPTION,
                    ExifInterface.TAG_USER_COMMENT
                ).forEach { tag -> setAttribute(tag, null) }
                saveAttributes()
            }
        }
        val mime = asset.mimeType.ifBlank { "image/jpeg" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Safe_${asset.name}")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Lumina Gallery/Safe Share")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (asset.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val outputUri = context.contentResolver.insert(collection, values) ?: return null
        context.contentResolver.openOutputStream(outputUri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(outputUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        file.delete()
        outputUri
    }.getOrNull()

    fun setWallpaper(context: Context, asset: MediaAsset): Boolean = runCatching {
        val stream = context.contentResolver.openInputStream(asset.uri) ?: return false
        stream.use { WallpaperManager.getInstance(context).setStream(it) }
        true
    }.getOrDefault(false)

    fun saveBitmap(context: Context, bitmap: Bitmap, name: String): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Lumina Gallery/Edited")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        uri
    }.getOrNull()

    fun sha256(context: Context, asset: MediaAsset): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(asset.uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
