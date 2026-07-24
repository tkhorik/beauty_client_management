package com.beauty.app.sync

import androidx.work.ListenableWorker.Result
import com.beauty.app.data.VisitSyncRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VisitSyncCoordinatorTest {
    @Test
    fun `returns success after all uploads succeed`() = runTest {
        val result = VisitSyncCoordinator(repository(true)).sync()
        assertEquals(Result.success(), result)
    }

    @Test
    fun `returns retry when upload fails or throws`() = runTest {
        assertEquals(Result.retry(), VisitSyncCoordinator(repository(false)).sync())
        assertEquals(Result.retry(), VisitSyncCoordinator(object : VisitSyncRepository {
            override suspend fun syncPendingVisits(): Boolean = error("network")
        }).sync())
    }

    private fun repository(success: Boolean) = object : VisitSyncRepository {
        override suspend fun syncPendingVisits() = success
    }
}
