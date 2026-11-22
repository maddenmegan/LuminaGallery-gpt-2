package com.lumina.gallery.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lumina.gallery.data.MediaAsset
import com.lumina.gallery.data.MediaRepository

class MediaIndexWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("lumina_index", Context.MODE_PRIVATE)
        val store = LocalIndexStore(applicationContext)
        return try {
            val items = MediaRepository.query(applicationContext, includeTrash = false)
                .sortedWith(compareBy<MediaAsset> { if (it.isPdf || it.isScreenshot) 0 else 1 }.thenByDescending { it.modifiedSeconds })
            val previousTotal = prefs.getInt("total", -1)
            val savedProgress = prefs.getInt("indexed", 0)
            val startAt = if (previousTotal == items.size && !prefs.getBoolean("complete", false)) savedProgress.coerceIn(0, items.size) else 0
            prefs.edit().putInt("total", items.size).putInt("indexed", startAt).putBoolean("complete", false).apply()
            val batch = ArrayList<MediaAsset>(200)
            for (index in startAt until items.size) {
                if (isStopped) {
                    if (batch.isNotEmpty()) store.saveBatch(batch)
                    store.close()
                    return Result.retry()
                }
                batch += items[index]
                if (batch.size >= 200 || index == items.lastIndex) {
                    store.saveBatch(batch)
                    batch.clear()
                    val done = index + 1
                    prefs.edit().putInt("indexed", done).apply()
                    setProgressAsync(workDataOf("indexed" to done, "total" to items.size))
                }
            }
            store.close()
            prefs.edit().putBoolean("complete", true).putInt("indexed", items.size).apply()
            Result.success(workDataOf("indexed" to items.size))
        } catch (error: SecurityException) {
            prefs.edit().putString("error", "Media permission was revoked").apply()
            store.close()
            Result.failure()
        } catch (error: Exception) {
            prefs.edit().putString("error", error.localizedMessage ?: "Index failed").apply()
            store.close()
            Result.retry()
        }
    }
}
