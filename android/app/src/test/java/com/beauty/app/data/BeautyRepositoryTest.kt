package com.beauty.app.data

import com.beauty.app.data.api.AuthRequest
import com.beauty.app.data.api.AuthResponse
import com.beauty.app.data.api.BeautyApi
import com.beauty.app.data.api.ChangePasswordRequest
import com.beauty.app.data.api.ClientDto
import com.beauty.app.data.api.CreateVisitRequest
import com.beauty.app.data.api.RefreshRequest
import com.beauty.app.data.api.RegisterRequest
import com.beauty.app.data.api.UpdateClientRequest
import com.beauty.app.data.api.UpdateProfileRequest
import com.beauty.app.data.api.UserDto
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
            override suspend fun login(request: AuthRequest) = error("not used in this test")
            override suspend fun register(request: RegisterRequest) = error("not used in this test")
            override suspend fun logout(request: RefreshRequest) = error("not used in this test")
            override suspend fun getClients() = listOf(
                ClientDto("client-1", "Ada", "+100", tags = listOf("VIP"), customFields = JsonObject(emptyMap()), totalVisits = 2, createdAt = "now", updatedAt = "now")
            )
            override suspend fun updateClient(id: String, request: UpdateClientRequest) = error("not used in this test")
            override suspend fun createVisit(request: CreateVisitRequest) = VisitDto("unused")
            override suspend fun getCurrentUser(): UserDto = error("not used in this test")
            override suspend fun updateProfile(request: UpdateProfileRequest): UserDto = error("not used in this test")
            override suspend fun changePassword(request: ChangePasswordRequest): AuthResponse = error("not used in this test")
        }

        val result = BeautyRepository(api, clientDao, visitDao).refreshClients()

        assertTrue(result.isSuccess)
        verify(clientDao).reconcileClients(org.mockito.kotlin.check { clients ->
            assertEquals(1, clients.size)
            assertEquals("client-1", clients.single().id)
            assertEquals("[\"VIP\"]", clients.single().tagsJson)
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
        override suspend fun login(request: AuthRequest): AuthResponse = error("Network unavailable")
        override suspend fun register(request: RegisterRequest): AuthResponse = error("Network unavailable")
        override suspend fun logout(request: RefreshRequest) = error("Network unavailable")
        override suspend fun getClients(): List<ClientDto> = error("Network unavailable")
        override suspend fun updateClient(id: String, request: UpdateClientRequest): ClientDto = error("Network unavailable")
        override suspend fun createVisit(request: CreateVisitRequest): VisitDto = error("Network unavailable")
        override suspend fun getCurrentUser(): UserDto = error("Network unavailable")
        override suspend fun updateProfile(request: UpdateProfileRequest): UserDto = error("Network unavailable")
        override suspend fun changePassword(request: ChangePasswordRequest): AuthResponse = error("Network unavailable")
    }

    private fun visitApi(create: suspend (CreateVisitRequest) -> VisitDto) = object : BeautyApi {
        override suspend fun login(request: AuthRequest) = error("not used in this test")
        override suspend fun register(request: RegisterRequest) = error("not used in this test")
        override suspend fun logout(request: RefreshRequest) = error("not used in this test")
        override suspend fun getClients() = emptyList<ClientDto>()
        override suspend fun updateClient(id: String, request: UpdateClientRequest) = error("not used in this test")
        override suspend fun createVisit(request: CreateVisitRequest) = create(request)
        override suspend fun getCurrentUser(): UserDto = error("not used in this test")
        override suspend fun updateProfile(request: UpdateProfileRequest): UserDto = error("not used in this test")
        override suspend fun changePassword(request: ChangePasswordRequest): AuthResponse = error("not used in this test")
    }
}
