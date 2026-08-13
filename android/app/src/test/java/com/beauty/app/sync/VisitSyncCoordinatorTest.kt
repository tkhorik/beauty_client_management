package com.beauty.app.sync

import androidx.work.ListenableWorker.Result
import com.beauty.app.data.VisitSyncOutcome
import com.beauty.app.data.VisitSyncRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VisitSyncCoordinatorTest {
    @Test
    fun `returns success after all uploads succeed`() = runTest {
        val result = VisitSyncCoordinator(repository(VisitSyncOutcome.SUCCESS)).sync()
        assertEquals(Result.success(), result)
    }

    @Test
    fun `returns retry when upload fails or throws`() = runTest {
        assertEquals(Result.retry(), VisitSyncCoordinator(repository(VisitSyncOutcome.RETRY)).sync())
        assertEquals(Result.retry(), VisitSyncCoordinator(object : VisitSyncRepository {
            override suspend fun syncPendingVisits(): VisitSyncOutcome = error("network")
        }).sync())
    }

    @Test
    fun `does not retry when the account is waiting on email verification`() = runTest {
        // Retrying would wake the device on a backoff schedule to be refused
        // in exactly the same way, for as long as it takes the user to open
        // their inbox. The queued visits are not lost — they stay in Room and
        // go up on the next enqueue.
        assertEquals(
            Result.failure(),
            VisitSyncCoordinator(repository(VisitSyncOutcome.BLOCKED_UNVERIFIED)).sync()
        )
    }

    private fun repository(outcome: VisitSyncOutcome) = object : VisitSyncRepository {
        override suspend fun syncPendingVisits() = outcome
    }
}
