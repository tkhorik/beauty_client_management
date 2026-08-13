package com.beauty.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.beauty.app.AppContainer
import com.beauty.app.data.VisitSyncOutcome
import com.beauty.app.data.VisitSyncRepository

class VisitSyncCoordinator(private val repository: VisitSyncRepository) {
    suspend fun sync(): Result = try {
        when (repository.syncPendingVisits()) {
            VisitSyncOutcome.SUCCESS -> Result.success()
            VisitSyncOutcome.RETRY -> Result.retry()

            // `failure`, not `retry`. Retrying is what WorkManager does for a
            // problem that might fix itself — a lost connection, a server
            // restart. This one cannot: every attempt will be refused until the
            // user opens their inbox and clicks a link, and a backoff schedule
            // would wake the device for hours to be told the same thing.
            //
            // Nothing is lost by stopping. The visits stay queued in Room, and
            // `SyncWorker.enqueue` runs again on the next app launch — by which
            // time the address may well be confirmed.
            VisitSyncOutcome.BLOCKED_UNVERIFIED -> Result.failure()
        }
    } catch (_: Exception) {
        Result.retry()
    }
}

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val tokenStore = AppContainer.tokenStore(applicationContext)
        return VisitSyncCoordinator(AppContainer.repository(applicationContext, tokenStore)).sync()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "visit-api-sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
