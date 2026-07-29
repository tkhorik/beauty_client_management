package com.beauty.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object BeautyDatabaseProvider {
    private val migration1To2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE visits ADD COLUMN remoteId TEXT")
            db.execSQL("ALTER TABLE visits ADD COLUMN syncError TEXT")
            db.execSQL("ALTER TABLE visits ADD COLUMN syncAttempts INTEGER NOT NULL DEFAULT 0")
        }
    }

    // Room warns about foreign keys without an index because deleting or
    // updating a parent otherwise scans the entire child table. This migration
    // keeps existing installed databases compatible with the new entity schema.
    private val migration2To3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_visits_clientId ON visits (clientId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_visitId ON attachments (visitId)")
        }
    }

    @Volatile private var instance: BeautyDatabase? = null

    fun get(context: Context): BeautyDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, BeautyDatabase::class.java, "beauty_db")
            .addMigrations(migration1To2, migration2To3)
            .build()
            .also { instance = it }
    }
}
