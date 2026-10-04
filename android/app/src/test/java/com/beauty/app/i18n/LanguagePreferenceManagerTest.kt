package com.beauty.app.i18n

import com.beauty.app.data.api.LanguagePreferenceResponse
import com.beauty.app.data.local.LanguageStateStorage
import com.beauty.app.data.local.LanguageStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanguagePreferenceManagerTest {
    private class MemoryStore : LanguageStateStorage {
        val values = mutableMapOf<String?, LanguageStore.State>()
        override fun state(accountId: String?) = values[accountId] ?: LanguageStore.State()
        override fun save(accountId: String?, state: LanguageStore.State) { values[accountId] = state }
        override var observedTags = ""
        override var pendingApplicationTags: String? = null
    }
    private class Locales : AppLocaleBridge {
        var value = ""
        var applications = 0
        override fun tags() = value
        override fun apply(tags: String) { value = tags; applications++ }
    }
    private fun response(preference: String, revision: Long) = LanguagePreferenceResponse(preference, revision)
    private fun saved(preference: String, revision: Long) = LanguagePreferenceManager.WriteResult(response(preference, revision), false)

    @Test fun `system detection never uploads the resolved language and self application never echoes`() = runTest {
        val store = MemoryStore(); val native = Locales(); val manager = LanguagePreferenceManager(store, native)
        manager.activeAccountId = "a"
        manager.synchronize("a", { response("ru", 2) }, { _, _ -> error("Unexpected write") })
        assertEquals("ru", native.value)
        assertFalse(manager.detectExternalOverride("a"))
        native.value = "" // Android Settings: follow the system, regardless of its actual language.
        assertTrue(manager.detectExternalOverride("a"))
        var uploaded: String? = null
        manager.synchronize("a", { response("ru", 2) }, { preference, revision ->
            uploaded = preference; assertEquals(2L, revision); saved(preference, 3)
        })
        assertEquals("system", uploaded)
        assertEquals("", native.value)
        assertFalse(manager.detectExternalOverride("a"))
    }

    @Test fun `returning to a previously programmatic locale is still an external change`() {
        val store = MemoryStore(); val native = Locales(); val manager = LanguagePreferenceManager(store, native)
        manager.activeAccountId = "a"
        manager.select("a", "ru")
        native.value = "en"; assertTrue(manager.detectExternalOverride("a"))
        native.value = "ru"; assertTrue(manager.detectExternalOverride("a"))
        assertEquals("ru", manager.current("a").preference)
    }

    @Test fun `rapid second choice survives first acknowledgement and uploads immediately`() = runTest {
        val store = MemoryStore(); val native = Locales(); val manager = LanguagePreferenceManager(store, native)
        manager.activeAccountId = "a"; manager.select("a", "ru")
        val firstResponse = CompletableDeferred<LanguagePreferenceManager.WriteResult>()
        val writes = mutableListOf<Pair<String, Long>>()
        val job = launch {
            manager.synchronize("a", { response("system", 0) }, { pref, revision ->
                writes += pref to revision
                if (writes.size == 1) firstResponse.await() else saved(pref, 2)
            })
        }
        runCurrent()
        manager.select("a", "en")
        firstResponse.complete(saved("ru", 1)); job.join()
        assertEquals(listOf("ru" to 0L, "en" to 1L), writes)
        assertEquals("en", native.value)
        assertFalse(manager.current("a").pending)
        assertEquals(2L, manager.current("a").revision)
    }

    @Test fun `offline edit remains pending but cannot overwrite a newer account preference`() = runTest {
        val store = MemoryStore(); val native = Locales(); val manager = LanguagePreferenceManager(store, native)
        manager.activeAccountId = "a"; manager.select("a", "ru")
        assertEquals(LanguagePreferenceManager.SyncResult.PendingOffline,
            manager.synchronize("a", { throw java.io.IOException() }, { _, _ -> error("offline") }))
        assertTrue(manager.current("a").pending)
        assertEquals("ru", native.value)
        assertEquals(LanguagePreferenceManager.SyncResult.Conflict,
            manager.synchronize("a", { response("en", 3) }, { _, _ -> error("stale edit must not write") }))
        assertFalse(manager.current("a").pending)
        assertEquals("en", native.value)
        assertEquals(LanguagePreferenceManager.SyncResult.Conflict, manager.notice)
    }

    @Test fun `lost response is acknowledged on reconnect without a conflict or extra write`() = runTest {
        val store = MemoryStore(); val native = Locales(); val manager = LanguagePreferenceManager(store, native)
        manager.activeAccountId = "a"; manager.select("a", "ru")
        manager.synchronize("a", { response("system", 0) }, { _, _ -> throw java.io.IOException("response lost") })
        assertTrue(manager.current("a").pending)
        assertEquals(LanguagePreferenceManager.SyncResult.Uploaded,
            manager.synchronize("a", { response("ru", 1) }, { _, _ -> error("already stored") }))
        assertFalse(manager.current("a").pending)
        assertNull(manager.notice)
    }

    @Test fun `old response after logout and same account login cannot overwrite a fresh choice`() = runTest {
        val store = MemoryStore(); val native = Locales(); val manager = LanguagePreferenceManager(store, native)
        manager.activeAccountId = "a"; manager.select("a", "ru")
        val delayed = CompletableDeferred<LanguagePreferenceManager.WriteResult>()
        val job = launch { manager.synchronize("a", { response("system", 0) }, { _, _ -> delayed.await() }) }
        runCurrent()
        manager.activeAccountId = null; manager.activeAccountId = "a"
        manager.select("a", "en")
        delayed.complete(saved("ru", 1)); job.join()
        assertEquals("en", manager.current("a").preference)
        assertEquals("en", native.value)
        assertTrue(manager.current("a").pending)
    }

    @Test fun `other account is not updated by old response and gets synchronized after mutex release`() = runTest {
        val store = MemoryStore(); val native = Locales(); val manager = LanguagePreferenceManager(store, native)
        manager.activeAccountId = "a"; manager.select("a", "ru")
        val delayed = CompletableDeferred<LanguagePreferenceManager.WriteResult>()
        val a = launch { manager.synchronize("a", { response("system", 0) }, { _, _ -> delayed.await() }) }
        runCurrent()
        manager.activeAccountId = "b"
        val b = launch { manager.synchronize("b", { response("en", 4) }, { _, _ -> error("Unexpected write") }) }
        runCurrent(); delayed.complete(saved("ru", 1)); a.join(); b.join()
        assertEquals("en", native.value)
        assertEquals(4L, manager.current("b").revision)
        assertTrue(manager.current("a").pending)
        assertFalse(manager.current("b").pending)
    }

    @Test fun `killed process own application intent is consumed without being uploaded`() {
        val store = MemoryStore(); val native = Locales()
        store.pendingApplicationTags = "ru"; native.value = "ru"
        val manager = LanguagePreferenceManager(store, native); manager.activeAccountId = "a"
        assertFalse(manager.detectExternalOverride("a"))
        assertNull(store.pendingApplicationTags)
        native.value = "en"
        assertTrue(manager.detectExternalOverride("a"))
        assertTrue(manager.current("a").pending)
    }

    @Test fun `cancellation is never swallowed as an offline error`() = runTest {
        val manager = LanguagePreferenceManager(MemoryStore(), Locales()); manager.activeAccountId = "a"
        try {
            manager.synchronize("a", { throw CancellationException("cancel") }, { _, _ -> error("no write") })
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
