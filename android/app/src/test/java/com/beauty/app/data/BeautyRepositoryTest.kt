package com.beauty.app.data

import com.beauty.app.data.api.ClientDto
import com.beauty.app.data.api.CreateVisitRequest
import com.beauty.app.data.api.VisitDto
import com.beauty.app.data.api.VisitHistoryDto
import com.beauty.app.data.local.ClientDao
import com.beauty.app.data.local.OrganizationCacheDao
import com.beauty.app.data.local.VisitDao
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import com.beauty.app.data.local.VisitEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
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
    private val cache = mock<OrganizationCacheDao>()

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
    fun `history pass through preserves the requested organization and client`() = runTest {
        var requestedOrg: String? = null
        var requestedClient: String? = null
        val expected = listOf(
            VisitHistoryDto("visit-1", "client-1", "2026-09-01T10:00:00", 30, "Treatment", "COMPLETED")
        )
        val api = object : FakeBeautyApi() {
            override suspend fun getVisitsForClient(orgId: String, clientId: String): List<VisitHistoryDto> {
                requestedOrg = orgId
                requestedClient = clientId
                return expected
            }
        }

        val history = BeautyRepository(api, clientDao, visitDao).getVisitsForClient(ORG_B, "client-1")

        assertEquals(expected, history)
        assertEquals(ORG_B, requestedOrg)
        assertEquals("client-1", requestedClient)
    }

    @Test
    fun `successful upload records backend id`() = runTest {
        val visit = pendingVisit()
        whenever(visitDao.getUnsyncedVisits()).thenReturn(listOf(visit))
        val api = visitApi { VisitDto("remote-1") }

        val outcome = BeautyRepository(api, clientDao, visitDao).syncPendingVisits()

        assertEquals(VisitSyncOutcome.SUCCESS, outcome)
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

        val outcome = BeautyRepository(api, clientDao, visitDao).syncPendingVisits()

        assertEquals(VisitSyncOutcome.RETRY, outcome)
        verify(visitDao).markVisitSyncFailed(eq("local-1"), any())
        org.mockito.kotlin.verify(visitDao, org.mockito.kotlin.never()).markVisitSynced(any(), any())
    }

    @Test
    fun `no pending visits means no duplicate upload`() = runTest {
        whenever(visitDao.getUnsyncedVisits()).thenReturn(emptyList())
        val api = visitApi { error("API must not be called") }

        assertEquals(
            VisitSyncOutcome.SUCCESS,
            BeautyRepository(api, clientDao, visitDao).syncPendingVisits()
        )
    }

    @Test
    fun `a visit refused with NOT_A_MEMBER purges that organization instead of retrying`() = runTest {
        whenever(visitDao.getUnsyncedVisits()).thenReturn(listOf(pendingVisit(orgId = ORG_A), pendingVisit(orgId = ORG_A).copy(id = "local-2")))
        whenever(cache.localFilesForOrganization(ORG_A)).thenReturn(emptyList())
        val refused = forbidden("""{"error":"Not a member","code":"NOT_A_MEMBER"}""")
        var attempts = 0
        val api = object : FakeBeautyApi() {
            override suspend fun createVisit(orgId: String, request: CreateVisitRequest): VisitDto {
                attempts++
                throw refused
            }
        }

        val outcome = BeautyRepository(api, clientDao, visitDao, organizationCache = cache).syncPendingVisits()

        assertEquals(VisitSyncOutcome.SUCCESS, outcome)
        assertEquals("the second visit of a lost organization is not attempted", 1, attempts)
        verify(cache).purgeOrganization(ORG_A)
        org.mockito.kotlin.verify(visitDao, org.mockito.kotlin.never()).markVisitSyncFailed(any(), any())
    }

    @Test
    fun `other 403s never purge anything`() = runTest {
        whenever(visitDao.getUnsyncedVisits()).thenReturn(listOf(pendingVisit()))
        val refused = forbidden("""{"error":"Admins only","code":"ADMIN_REQUIRED"}""")
        val api = object : FakeBeautyApi() {
            override suspend fun createVisit(orgId: String, request: CreateVisitRequest): VisitDto = throw refused
        }

        val outcome = BeautyRepository(api, clientDao, visitDao, organizationCache = cache).syncPendingVisits()

        assertEquals(VisitSyncOutcome.RETRY, outcome)
        org.mockito.kotlin.verify(cache, org.mockito.kotlin.never()).purgeOrganization(any())
    }

    @Test
    fun `organizations missing from the server list are purged and active ones kept`() = runTest {
        whenever(cache.cachedOrganizationIds()).thenReturn(listOf(ORG_A, ORG_B, ""))
        whenever(cache.localFilesForOrganization(any())).thenReturn(emptyList())

        val purged = BeautyRepository(object : FakeBeautyApi() {}, clientDao, visitDao, organizationCache = cache)
            .purgeOrganizationsExcept(setOf(ORG_A))

        assertEquals(listOf(ORG_B, ""), purged)
        verify(cache).purgeOrganization(ORG_B)
        org.mockito.kotlin.verify(cache, org.mockito.kotlin.never()).purgeOrganization(ORG_A)
    }

    /** A real ClientRequestException carrying a 403 body, as the API client throws it. */
    private suspend fun forbidden(body: String): Exception {
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine {
            respond(body, io.ktor.http.HttpStatusCode.Forbidden, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = true }
        return runCatching { client.get("https://api.test/") }.exceptionOrNull() as Exception
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
