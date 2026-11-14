package com.reivex.browser.downloads

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Private database for ReiVex's download queue and saved per-link credentials. */
internal class ReiVexDownloadsDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "reivex_downloads.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE native_downloads (id TEXT PRIMARY KEY NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE native_download_profiles (id TEXT PRIMARY KEY NOT NULL, secret TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun <T> read(block: (SQLiteDatabase) -> T): T = block(readableDatabase)

    fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }
}
