package com.lumina.gallery.worker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lumina.gallery.data.MediaAsset

class LocalIndexStore(context: Context) : SQLiteOpenHelper(context, "lumina_media_index.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE media_index (_id INTEGER PRIMARY KEY, uri TEXT NOT NULL, name TEXT NOT NULL, path TEXT NOT NULL, mime TEXT NOT NULL, bytes INTEGER NOT NULL, modified INTEGER NOT NULL, priority INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_media_name ON media_index(name)")
        db.execSQL("CREATE INDEX idx_media_path ON media_index(path)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun saveBatch(assets: List<MediaAsset>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            assets.forEach { asset ->
                val values = ContentValues().apply {
                    put("_id", asset.id)
                    put("uri", asset.uri.toString())
                    put("name", asset.name)
                    put("path", asset.relativePath)
                    put("mime", asset.mimeType)
                    put("bytes", asset.size)
                    put("modified", asset.modifiedSeconds)
                    put("priority", if (asset.isPdf || asset.isScreenshot) 0 else 1)
                }
                db.insertWithOnConflict("media_index", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
