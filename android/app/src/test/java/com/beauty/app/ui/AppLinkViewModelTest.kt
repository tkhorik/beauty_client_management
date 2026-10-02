package com.beauty.app.ui

import com.beauty.app.BuildConfig
import com.beauty.app.data.FakeBeautyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppLinkViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `inbox consumes each delivery once and does not deduplicate deliberate taps`() {
        val vm = AppLinkViewModel()
        vm.receive("${BuildConfig.APP_WEB_BASE_URL}/reset-password?token=one")
        vm.receive("${BuildConfig.APP_WEB_BASE_URL}/reset-password?token=two")
        vm.receive("${BuildConfig.APP_WEB_BASE_URL}/reset-password?token=two")
        vm.receive("https://evil.example/reset-password?token=evil")
        assertEquals("one", (vm.take() as AppLink.ResetPassword).token)
        assertEquals("two", (vm.take() as AppLink.ResetPassword).token)
        assertEquals("two", (vm.take() as AppLink.ResetPassword).token)
        assertNull(vm.take())
    }

    @Test fun `creation token survives authentication transition and account state is not reused`() {
        val vm = AppLinkViewModel()
        val anonymousKey = vm.organizationKey(null)
        vm.holdOrganization("pending")
        val accountKey = vm.organizationKey("account")
        assertNotEquals(anonymousKey, accountKey)
        assertEquals("pending", vm.pendingOrganizationToken)
        assertEquals(accountKey, vm.organizationKey("account")) // same Activity VM on rotation
        assertEquals("pending", vm.takeOrganization())
        assertNull(vm.takeOrganization())
        vm.organizationKey(null)
        assertNotEquals(accountKey, vm.organizationKey("account"))
    }

    @Test fun `verification redeems once and refreshes signed in profile`() = runTest(dispatcher) {
        val vm = AppLinkViewModel()
        val tokens = mutableListOf<String>()
        var refreshed = false
        val api = object : FakeBeautyApi() {
            override suspend fun verifyEmail(token: String) { tokens += token }
        }
        vm.verify(AppLink.VerifyEmail("email", null), api) { refreshed = true }
        assertTrue(vm.verifying)
        advanceUntilIdle()
        assertEquals(listOf("email"), tokens)
        assertTrue(refreshed)
        assertEquals("Email confirmed.", vm.verificationMessage)
        assertFalse(vm.verifying)
        assertEquals(1, vm.profileRevision)
    }

    @Test fun `invalid and offline verification present safe result and allow next delivery`() = runTest(dispatcher) {
        val vm = AppLinkViewModel()
        val api = object : FakeBeautyApi() {
            override suspend fun verifyEmail(token: String) { error("secret token must not be shown") }
        }
        vm.verify(AppLink.VerifyEmail("email", null), api)
        advanceUntilIdle()
        assertFalse(vm.verifying)
        assertFalse(vm.verificationMessage.orEmpty().contains("secret"))
        vm.verify(AppLink.VerifyEmail(null, "invalid"), api)
        advanceUntilIdle()
        assertTrue(vm.verificationMessage.orEmpty().contains("invalid"))
    }
}
