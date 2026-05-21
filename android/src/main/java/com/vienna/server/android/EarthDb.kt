package com.vienna.server.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File

data class DbRecord(val value: JSONObject, val version: Long)

class EarthDb(context: Context, dbFile: File) : SQLiteOpenHelper(context, dbFile.absolutePath, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS objects (
                type TEXT NOT NULL,
                id TEXT NOT NULL,
                value TEXT NOT NULL,
                version INTEGER NOT NULL,
                PRIMARY KEY (type, id)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = onCreate(db)

    fun get(objectType: String, id: String, defaultValue: JSONObject = JSONObject()): DbRecord {
        readableDatabase.rawQuery(
            "SELECT value, version FROM objects WHERE type = ? AND id = ?",
            arrayOf(objectType, id)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return DbRecord(JSONObject(cursor.getString(0)), cursor.getLong(1))
            }
        }
        return DbRecord(defaultValue, 1)
    }

    fun update(objectType: String, id: String, value: JSONObject): Long {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val existing = get(objectType, id)
            val nextVersion = if (existing.version <= 1) 2 else existing.version + 1
            db.execSQL(
                """
                INSERT OR REPLACE INTO objects(type, id, value, version)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(objectType, id, value.toString(), nextVersion)
            )
            db.setTransactionSuccessful()
            nextVersion
        } finally {
            db.endTransaction()
        }
    }
}
