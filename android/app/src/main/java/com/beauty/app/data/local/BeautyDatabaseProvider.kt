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

    /**
     * Adds `organizationId` to the cached clients and visits.
     *
     * Existing rows get an empty string rather than a guess. There is no honest
     * way to say which organization a client cached before multi-tenancy
     * belongs to, and inventing one would show another salon's records under
     * that name. An empty value matches no real organization id, so those rows
     * are invisible to every scoped query and are cleared by the first
     * successful refresh — which re-downloads them correctly attributed.
     *
     * The one thing this does lose is unsent visits queued before the upgrade:
     * they will carry an organization the server rejects. That is a fair trade
     * against uploading someone's treatment record to the wrong business, and
     * the sync error surfaces in the UI rather than failing silently.
     */
    private val migration3To4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE clients ADD COLUMN organizationId TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE visits ADD COLUMN organizationId TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_clients_organizationId ON clients (organizationId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_visits_organizationId ON visits (organizationId)")
        }
    }

    val migration4To5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS history_snapshots (organizationId TEXT NOT NULL, clientId TEXT NOT NULL, historyJson TEXT NOT NULL, PRIMARY KEY(organizationId, clientId))")
            db.execSQL("CREATE TABLE IF NOT EXISTS photo_drafts (id TEXT NOT NULL PRIMARY KEY, organizationId TEXT NOT NULL, clientId TEXT NOT NULL, localVisitId TEXT NOT NULL, localFilePath TEXT NOT NULL, tag TEXT NOT NULL, syncError TEXT, uploadedAttachmentJson TEXT)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_photo_drafts_organizationId_clientId ON photo_drafts (organizationId, clientId)")
        }
    }

    private val instances = mutableMapOf<String, BeautyDatabase>()

    /** Legacy beauty_db is retained unchanged, but never assigned to a guessed account. */
    fun databaseName(accountId: String?): String {
        if (accountId.isNullOrBlank()) return "beauty_signed_out"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray())
        return "beauty_account_" + digest.joinToString("") { "%02x".format(it) }
    }

    fun get(context: Context, accountId: String? = TokenStore(context).getAccountId()): BeautyDatabase = synchronized(this) {
        val name = databaseName(accountId)
        instances.getOrPut(name) {
            Room.databaseBuilder(context.applicationContext, BeautyDatabase::class.java, name)
                .addMigrations(migration1To2, migration2To3, migration3To4, migration4To5)
                .build()
        }
    }
}
