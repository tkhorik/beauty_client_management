package com.beauty.app.data

import com.beauty.app.data.api.BeautyApi
import com.beauty.app.data.api.ClientDto
import com.beauty.app.data.api.CreateVisitRequest
import com.beauty.app.data.api.VisitDto
import com.beauty.app.data.local.ClientDao
import com.beauty.app.data.local.VisitDao
import com.beauty.app.data.local.VisitEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BeautyRepositoryTest {
    private val clientDao = mock<ClientDao>()
    private val visitDao = mock<VisitDao>()

    @Test
    fun `refresh stores backend clients`() = runTest {
        val api = object : BeautyApi {
            override suspend fun getClients() = listOf(
                ClientDto("client-1", "Ada", "+100", tags = listOf("VIP"), customFields = JsonObject(emptyMap()), totalVisits = 2, createdAt = "now", updatedAt = "now")
            )
            override suspend fun createVisit(request: CreateVisitRequest) = VisitDto("unused")
        }

        val result = BeautyRepository(api, clientDao, visitDao).refreshClients()

        assertTrue(result.isSuccess)
        verify(clientDao).insertClient(org.mockito.kotlin.check { entity ->
            assertEquals("client-1", entity.id)
            assertEquals("[\"VIP\"]", entity.tagsJson)
        })
    }

    @Test
    fun `failed refresh does not write or clear cached clients`() = runTest {
        val api = failingApi()

        val result = BeautyRepository(api, clientDao, visitDao).refreshClients()

        assertTrue(result.isFailure)
        org.mockito.kotlin.verifyNoInteractions(clientDao)
    }

    @Test
    fun `successful upload records backend id`() = runTest {
        val visit = pendingVisit()
        whenever(visitDao.getUnsyncedVisits()).thenReturn(listOf(visit))
        val api = visitApi { VisitDto("remote-1") }

        val succeeded = BeautyRepository(api, clientDao, visitDao).syncPendingVisits()

        assertTrue(succeeded)
        verify(visitDao).markVisitSynced("local-1", "remote-1")
    }

    @Test
    fun `failed upload remains pending and records error`() = runTest {
        val visit = pendingVisit()
        whenever(visitDao.getUnsyncedVisits()).thenReturn(listOf(visit))
        val api = failingApi()

        val succeeded = BeautyRepository(api, clientDao, visitDao).syncPendingVisits()

        assertFalse(succeeded)
        verify(visitDao).markVisitSyncFailed(eq("local-1"), any())
        org.mockito.kotlin.verify(visitDao, org.mockito.kotlin.never()).markVisitSynced(any(), any())
    }

    @Test
    fun `no pending visits means no duplicate upload`() = runTest {
        whenever(visitDao.getUnsyncedVisits()).thenReturn(emptyList())
        val api = visitApi { error("API must not be called") }

        assertTrue(BeautyRepository(api, clientDao, visitDao).syncPendingVisits())
    }

    private fun pendingVisit() = VisitEntity("local-1", null, "client-1", "2026-07-24T10:00:00", 45, "Treatment", "COMPLETED", true)

    private fun failingApi() = object : BeautyApi {
        override suspend fun getClients(): List<ClientDto> = error("Network unavailable")
        override suspend fun createVisit(request: CreateVisitRequest): VisitDto = error("Network unavailable")
    }

    private fun visitApi(create: suspend (CreateVisitRequest) -> VisitDto) = object : BeautyApi {
        override suspend fun getClients() = emptyList<ClientDto>()
        override suspend fun createVisit(request: CreateVisitRequest) = create(request)
    }
}
