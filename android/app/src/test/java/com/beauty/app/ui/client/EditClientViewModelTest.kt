package com.beauty.app.ui.client

import androidx.lifecycle.SavedStateHandle
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.FakeBeautyApi
import com.beauty.app.data.api.ClientDto
import com.beauty.app.data.api.UpdateClientRequest
import com.beauty.app.data.local.ClientDao
import com.beauty.app.data.local.ClientEntity
import com.beauty.app.data.local.VisitDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class EditClientViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clientDao = mock<ClientDao>()
    private val visitDao = mock<VisitDao>()
    private val stored = ClientEntity("client", "org", "Ada", "legacy number", "ada@example.com",
        "[\"VIP\"]", """{"number":12,"boolean":true,"null":null,"object":{"x":1},"array":[1,false],"string":"12"}""", 1)

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `new client is clean with no invented tags or medical data and pending tag is dirty`() {
        val vm = model()
        assertFalse(vm.isDirty)
        assertTrue(vm.tags.isEmpty())
        assertTrue(vm.customFields.isEmpty())
        vm.updateTagInput("not yet added")
        assertTrue(vm.isDirty)
        assertTrue(vm.tags.isEmpty())
        vm.updateTagInput("")
        assertFalse(vm.isDirty)
        vm.updateName("Ada")
        assertTrue(vm.isDirty)
        vm.updateName("")
        assertFalse(vm.isDirty)
        vm.addCustomField()
        assertTrue(vm.isDirty)
        vm.removeCustomField(0)
        assertFalse(vm.isDirty)
    }

    @Test fun `restoration retains pending tag all edited fields and original baseline`() = runTest(dispatcher) {
        whenever(clientDao.getClientById("client", "org")).thenReturn(stored)
        val handle = SavedStateHandle()
        val vm = model("client", handle)
        advanceUntilIdle()
        assertFalse(vm.isDirty)
        vm.updateName("Edited")
        vm.updatePhone("+15551234567")
        vm.updateEmail("edited@example.com")
        vm.updateTagInput("unfinished")
        vm.removeTag("VIP")
        vm.updateCustomField(0, "renamed", "12")
        vm.removeCustomField(5)
        vm.addCustomField()
        vm.updateCustomField(5, "new", "typed")
        val restored = model("client", restoredHandle(handle))
        // A cache refresh must not overwrite the restored edit or its original baseline.
        whenever(clientDao.getClientById("client", "org")).thenReturn(stored.copy(name = "Refreshed cache"))
        advanceUntilIdle()
        assertEquals(vm.name, restored.name)
        assertEquals(vm.phone, restored.phone)
        assertEquals(vm.email, restored.email)
        assertEquals(vm.tags, restored.tags)
        assertEquals(vm.tagInput, restored.tagInput)
        assertEquals(vm.customFields, restored.customFields)
        assertTrue(restored.isDirty)
        val ids = restored.customFields.map { it.id }
        restored.addCustomField()
        assertFalse(restored.customFields.last().id in ids)
    }

    @Test fun `restored clean baseline remains clean and changes reverted to it become clean`() = runTest(dispatcher) {
        whenever(clientDao.getClientById("client", "org")).thenReturn(stored)
        val handle = SavedStateHandle()
        val vm = model("client", handle)
        advanceUntilIdle()
        val restored = model("client", restoredHandle(handle))
        advanceUntilIdle()
        assertFalse(restored.isDirty)
        restored.updateEmail("other@example.com")
        restored.updateEmail(vm.email)
        assertFalse(restored.isDirty)
    }

    @Test fun `saved draft never restores into another client organization or new client`() = runTest(dispatcher) {
        whenever(clientDao.getClientById("client", "org")).thenReturn(stored)
        whenever(clientDao.getClientById("other", "org")).thenReturn(stored.copy(id = "other", name = "Other"))
        whenever(clientDao.getClientById("client", "other-org")).thenReturn(stored.copy(organizationId = "other-org", name = "Other org"))
        val handle = SavedStateHandle()
        val vm = model("client", handle)
        advanceUntilIdle()
        vm.updateName("Secret draft")
        val otherClient = model("other", restoredHandle(handle))
        val otherOrg = model("client", restoredHandle(handle), org = "other-org")
        val newClient = model(null, restoredHandle(handle))
        advanceUntilIdle()
        assertEquals("Other", otherClient.name)
        assertEquals("Other org", otherOrg.name)
        assertEquals("", newClient.name)
        assertFalse(otherClient.isDirty)
        assertFalse(otherOrg.isDirty)
        assertFalse(newClient.isDirty)
    }

    @Test fun `save locks synchronously includes unfinished tag and preserves existing JSON types after restoration`() = runTest(dispatcher) {
        whenever(clientDao.getClientById("client", "org")).thenReturn(stored)
        val handle = SavedStateHandle()
        val vm = model("client", handle)
        advanceUntilIdle()
        vm.updateTagInput("  new tag  ")
        vm.updateCustomField(0, "renamed number", "12")
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var submitted: UpdateClientRequest? = null
        val api = object : FakeBeautyApi() {
            override suspend fun updateClient(orgId: String, id: String, request: UpdateClientRequest): ClientDto {
                assertEquals("org", orgId)
                assertEquals("client", id)
                calls++
                submitted = request
                release.await()
                return ClientDto(id, request.name, request.phone, tags = request.tags, customFields = request.customFields,
                    createdAt = "2026-10-01", updatedAt = "2026-10-01")
            }
        }
        val restored = model("client", restoredHandle(handle), api)
        advanceUntilIdle()
        restored.save()
        assertTrue(restored.isSaving)
        assertFalse(restored.canEdit)
        restored.save()
        restored.updateName("must not replace submitted name")
        restored.updateTagInput("must not add")
        restored.removeCustomField(0)
        runCurrent()
        assertEquals(1, calls)
        assertEquals("Ada", restored.name)
        assertEquals(listOf("VIP", "new tag"), submitted!!.tags)
        assertEquals("", restored.tagInput)
        val original = Json.parseToJsonElement(stored.customFieldsJson).jsonObject
        assertEquals(original["number"], submitted!!.customFields["renamed number"])
        original.filterKeys { it != "number" }.forEach { (key, value) -> assertEquals(value, submitted!!.customFields[key]) }
        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(restored.saveState is EditClientViewModel.SaveState.Success)
    }

    @Test fun `failed save retains editable dirty draft and edited typed value becomes JSON string`() = runTest(dispatcher) {
        whenever(clientDao.getClientById("client", "org")).thenReturn(stored)
        var submitted: UpdateClientRequest? = null
        val vm = model("client", api = object : FakeBeautyApi() {
            override suspend fun updateClient(orgId: String, id: String, request: UpdateClientRequest): ClientDto {
                submitted = request
                error("offline")
            }
        })
        advanceUntilIdle()
        vm.updateName("Draft name")
        vm.updateCustomField(0, "number", "13")
        vm.updateTagInput("pending")
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.saveState is EditClientViewModel.SaveState.Error)
        assertTrue(vm.canEdit)
        assertTrue(vm.isDirty)
        assertEquals("Draft name", vm.name)
        assertEquals(listOf("VIP", "pending"), vm.tags)
        assertEquals(JsonPrimitive("13"), submitted!!.customFields["number"])
    }

    @Test fun `new clients still require phone validation`() = runTest(dispatcher) {
        val vm = model()
        vm.updateName("Ada")
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.saveState is EditClientViewModel.SaveState.Error)
        assertNotEquals("NETWORK_ERROR", (vm.saveState as EditClientViewModel.SaveState.Error).message)
    }

    private fun restoredHandle(handle: SavedStateHandle) = SavedStateHandle(mapOf(
        EditClientViewModel.DRAFT_KEY to handle.get<String>(EditClientViewModel.DRAFT_KEY)
    ))
    private fun model(id: String? = null, handle: SavedStateHandle = SavedStateHandle(),
                      api: FakeBeautyApi = object : FakeBeautyApi() {}, org: String = "org") =
        EditClientViewModel(id, org, BeautyRepository(api, clientDao, visitDao), clientDao, handle)
}
