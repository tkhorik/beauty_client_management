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
import com.beauty.app.data.VisitSyncRepository

class VisitSyncCoordinator(private val repository: VisitSyncRepository) {
    suspend fun sync(): Result = try {
        if (repository.syncPendingVisits()) Result.success() else Result.retry()
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
