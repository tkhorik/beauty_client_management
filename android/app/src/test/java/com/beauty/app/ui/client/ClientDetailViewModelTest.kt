package com.beauty.app.ui.client

import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.FakeBeautyApi
import com.beauty.app.data.api.VisitHistoryDto
import com.beauty.app.data.local.ClientDao
import com.beauty.app.data.local.ClientEntity
import com.beauty.app.data.local.VisitDao
import com.beauty.app.data.local.VisitEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ClientDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clientDao = mock<ClientDao>()
    private val visitDao = mock<VisitDao>()
    private val local = MutableStateFlow<List<VisitEntity>>(emptyList())
    private val client = ClientEntity("client", "org", "Ada", "123", null, "[]", "{}", 1)
    private val remote = VisitHistoryDto("remote", "client", "2026-09-29T10:00:00", 60, "Treatment", "COMPLETED")

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        whenever(visitDao.getVisitsForClient("client", "org")).thenReturn(local)
    }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `refresh uses selected client and organization and reloads edited profile`() = runTest(dispatcher) {
        whenever(clientDao.getClientById("client", "org")).thenReturn(client)
        val api = object : FakeBeautyApi() {
            override suspend fun getVisitsForClient(orgId: String, clientId: String): List<VisitHistoryDto> {
                assertEquals("org", orgId)
                assertEquals("client", clientId)
                return listOf(remote)
            }
        }
        val vm = model(api)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(remote), vm.visits)
        assertEquals("Ada", vm.client?.name)
        assertFalse(vm.loading)
        whenever(clientDao.getClientById("client", "org")).thenReturn(client.copy(name = "Updated"))
        vm.refresh()
        advanceUntilIdle()
        assertEquals("Updated", vm.client?.name)
    }

    @Test fun `failed history load is an error not an empty history success`() = runTest(dispatcher) {
        whenever(clientDao.getClientById("client", "org")).thenReturn(client)
        val vm = model(object : FakeBeautyApi("offline") {})
        vm.refresh()
        advanceUntilIdle()
        assertNotNull(vm.error)
        assertFalse(vm.historyLoaded)
        assertFalse(vm.loading)
    }

    @Test fun `sync completion reloads remote history`() = runTest(dispatcher) {
        var reads = 0
        val vm = model(object : FakeBeautyApi() {
            override suspend fun getVisitsForClient(orgId: String, clientId: String): List<VisitHistoryDto> {
                reads++
                return listOf(remote)
            }
        })
        local.value = listOf(queued())
        advanceUntilIdle()
        local.value = listOf(queued().copy(remoteId = "remote", isPendingSync = false))
        advanceUntilIdle()
        assertEquals(1, reads)
        assertEquals(listOf(remote), vm.visits)
    }

    @Test fun `save queues under captured organization and prevents duplicate taps`() = runTest(dispatcher) {
        val vm = model(object : FakeBeautyApi() {})
        var callbacks = 0
        vm.saveVisit("2026-09-29T10:00:00", "60", " Notes ", "SCHEDULED") { callbacks++ }
        vm.saveVisit("2026-09-29T10:00:00", "60", " Notes ", "SCHEDULED") { callbacks++ }
        advanceUntilIdle()
        verify(visitDao).insertVisit(org.mockito.kotlin.check {
            assertEquals("org", it.organizationId)
            assertEquals("client", it.clientId)
            assertEquals("Notes", it.procedureNotes)
            assertEquals("SCHEDULED", it.status)
            assertTrue(it.isPendingSync)
        })
        assertEquals(1, callbacks)
    }

    @Test fun `invalid visit is not queued`() = runTest(dispatcher) {
        val vm = model(object : FakeBeautyApi() {})
        vm.saveVisit("2026-09-29T10:00:00", "0", "Notes", "COMPLETED") { fail("Must not save") }
        advanceUntilIdle()
        assertNotNull(vm.saveError)
        verify(visitDao, never()).insertVisit(any())
    }

    @Test fun `history deduplicates synced visits and preserves offline queue`() {
        val synced = queued().copy(remoteId = "remote", isPendingSync = false)
        val pending = queued().copy(id = "pending")
        assertEquals(listOf(pending), localHistoryExtras(listOf(remote), listOf(synced, pending), true))
        assertEquals(listOf(synced, pending), localHistoryExtras(emptyList(), listOf(synced, pending), true))
        // An authoritative empty server snapshot must not resurrect a deleted synced visit.
        assertEquals(listOf(pending), localHistoryExtras(emptyList(), listOf(synced, pending), false))
    }

    private fun model(api: FakeBeautyApi) = ClientDetailViewModel("client", "org", BeautyRepository(api, clientDao, visitDao), clientDao, visitDao)
    private fun queued() = VisitEntity("local", organizationId = "org", clientId = "client", visitDateTime = remote.visitDateTime,
        durationMinutes = 60, procedureNotes = "Notes", status = "COMPLETED", isPendingSync = true)
}
