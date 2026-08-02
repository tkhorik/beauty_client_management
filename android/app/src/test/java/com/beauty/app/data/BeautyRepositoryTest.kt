package com.beauty.app.data

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

private const val ORG_A = "org-a"
private const val ORG_B = "org-b"

class BeautyRepositoryTest {
    private val clientDao = mock<ClientDao>()
    private val visitDao = mock<VisitDao>()

    @Test
    fun `refresh stores backend clients under the requested organization`() = runTest {
        val api = object : FakeBeautyApi() {
            override suspend fun getClients(orgId: String) = listOf(
                ClientDto("client-1", "Ada", "+100", tags = listOf("VIP"), customFields = JsonObject(emptyMap()), totalVisits = 2, createdAt = "now", updatedAt = "now")
            )
        }

        val result = BeautyRepository(api, clientDao, visitDao).refreshClients(ORG_A)

        assertTrue(result.isSuccess)
        verify(clientDao).reconcileClients(eq(ORG_A), org.mockito.kotlin.check { clients ->
            assertEquals(1, clients.size)
            assertEquals("client-1", clients.single().id)
            assertEquals("[\"VIP\"]", clients.single().tagsJson)
            // The organization is stamped from the request, not from anything
            // the server sent back — the cache must never hold a row whose
            // owner is unknown.
            assertEquals(ORG_A, clients.single().organizationId)
        })
    }

    @Test
    fun `refresh asks the backend for the organization it was given`() = runTest {
        var requestedOrg: String? = null
        val api = object : FakeBeautyApi() {
            override suspend fun getClients(orgId: String): List<ClientDto> {
                requestedOrg = orgId
                return emptyList()
            }
        }

        BeautyRepository(api, clientDao, visitDao).refreshClients(ORG_B)

        assertEquals(ORG_B, requestedOrg)
    }

    @Test
    fun `failed refresh does not write or clear cached clients`() = runTest {
        val api = failingApi()

        val result = BeautyRepository(api, clientDao, visitDao).refreshClients(ORG_A)

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
    fun `a queued visit uploads to the organization it was recorded in`() = runTest {
        // The device has since switched to ORG_B; this visit was queued while
        // ORG_A was active. Uploading it under the currently selected
        // organization would file a client's treatment record with the wrong
        // business — silently, and permanently.
        whenever(visitDao.getUnsyncedVisits()).thenReturn(listOf(pendingVisit(orgId = ORG_A)))

        var uploadedTo: String? = null
        val api = object : FakeBeautyApi() {
            override suspend fun createVisit(orgId: String, request: CreateVisitRequest): VisitDto {
                uploadedTo = orgId
                return VisitDto("remote-1")
            }
        }

        BeautyRepository(api, clientDao, visitDao).syncPendingVisits()

        assertEquals(ORG_A, uploadedTo)
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

    private fun pendingVisit(orgId: String = ORG_A) = VisitEntity(
        id = "local-1",
        remoteId = null,
        organizationId = orgId,
        clientId = "client-1",
        visitDateTime = "2026-07-24T10:00:00",
        durationMinutes = 45,
        procedureNotes = "Treatment",
        status = "COMPLETED",
        isPendingSync = true
    )

    private fun failingApi() = object : FakeBeautyApi("Network unavailable") {}

    private fun visitApi(create: suspend (CreateVisitRequest) -> VisitDto) = object : FakeBeautyApi() {
        override suspend fun getClients(orgId: String) = emptyList<ClientDto>()
        override suspend fun createVisit(orgId: String, request: CreateVisitRequest) = create(request)
    }
}
