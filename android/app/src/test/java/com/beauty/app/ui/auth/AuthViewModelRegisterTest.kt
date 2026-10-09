package com.beauty.app.ui.auth

import com.beauty.app.data.FakeBeautyApi
import com.beauty.app.data.api.AuthResponse
import com.beauty.app.data.api.RegisterRequest
import com.beauty.app.data.api.UserDto
import com.beauty.app.data.local.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelRegisterTest {
    private val dispatcher = StandardTestDispatcher()
    private val strong = "correct horse battery"

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun recordingApi(sent: MutableList<RegisterRequest>) = object : FakeBeautyApi() {
        override suspend fun register(request: RegisterRequest): AuthResponse {
            sent += request
            return AuthResponse("access", "refresh", 900, UserDto("u1", request.email, request.fullName, "2026-10-09T10:00:00"))
        }
    }

    @Test fun `the organization handle is normalised and sent, then cleared`() = runTest(dispatcher) {
        val sent = mutableListOf<RegisterRequest>()
        val vm = AuthViewModel(recordingApi(sent), mock<TokenStore>())
        vm.registerOrganizationSlug = "  Salon-A "

        vm.register("new@example.com", strong, strong, "Nina")
        advanceUntilIdle()

        assertEquals("salon-a", sent.single().organizationSlug)
        assertEquals("", vm.registerOrganizationSlug)
        assertEquals(AuthViewModel.RegisterState.Success, vm.registerState)
    }

    @Test fun `a blank handle, or one hidden behind a creation link, is not sent`() = runTest(dispatcher) {
        val sent = mutableListOf<RegisterRequest>()
        val vm = AuthViewModel(recordingApi(sent), mock<TokenStore>())

        vm.registerOrganizationSlug = "   "
        vm.register("one@example.com", strong, strong, "One")
        advanceUntilIdle()
        vm.registerOrganizationSlug = "salon-a"
        vm.register("two@example.com", strong, strong, "Two", includeOrganization = false)
        advanceUntilIdle()

        assertEquals(listOf(null, null), sent.map { it.organizationSlug })
    }
}
