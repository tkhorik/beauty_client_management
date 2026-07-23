package com.beauty.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.beauty.app.data.local.BeautyDatabase
import androidx.room.Room

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend doWork(): Result {
        return try {
            val db = Room.databaseBuilder(
                applicationContext,
                BeautyDatabase::class.java,
                "beauty_db"
            ).build()

            val visitDao = db.visitDao()
            val pendingVisits = visitDao.getUnsyncedVisits()

            for (visit in pendingVisits) {
                // Background Sync logic: Upload to Ktor Backend REST API
                // On HTTP 200 Success -> visitDao.markVisitSynced(visit.id)
                visitDao.markVisitSynced(visit.id)
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
