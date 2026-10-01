package com.beauty.app.ui.auth

import com.beauty.app.data.FakeBeautyApi
import com.beauty.app.data.api.ResetPasswordRequest
import com.beauty.app.data.local.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
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
class AuthViewModelResetPasswordTest {
    private val dispatcher = StandardTestDispatcher()
    private val strong = "correct horse battery"

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `rejects a missing link and a weak password without calling the server`() = runTest(dispatcher) {
        val vm = AuthViewModel(object : FakeBeautyApi() {}, mock<TokenStore>())

        vm.resetPassword(token = null, newPassword = "short", confirmPassword = "other")
        advanceUntilIdle()

        val state = vm.resetPasswordState as AuthViewModel.ResetPasswordState.Error
        assertEquals(setOf("link", "newPassword", "confirmPassword"), state.fieldErrors.keys)
    }

    @Test fun `sends the token and new password and finishes without a session`() = runTest(dispatcher) {
        var sent: ResetPasswordRequest? = null
        val vm = AuthViewModel(object : FakeBeautyApi() {
            override suspend fun resetPassword(request: ResetPasswordRequest) { sent = request }
        }, mock<TokenStore>())

        vm.resetPassword("tok", strong, strong)
        advanceUntilIdle()

        assertEquals(ResetPasswordRequest("tok", strong), sent)
        assertEquals(AuthViewModel.ResetPasswordState.Done, vm.resetPasswordState)
    }

    @Test fun `a flat 400 marks the link as spent`() = runTest(dispatcher) {
        val failure = clientError("""{"error":"This reset link is invalid or has expired."}""")
        val vm = AuthViewModel(object : FakeBeautyApi() {
            override suspend fun resetPassword(request: ResetPasswordRequest) { throw failure }
        }, mock<TokenStore>())

        vm.resetPassword("tok", strong, strong)
        advanceUntilIdle()

        val state = vm.resetPasswordState as AuthViewModel.ResetPasswordState.Error
        assertNotNull(state.fieldErrors["link"])
    }

    @Test fun `field errors from the server stay on their fields`() = runTest(dispatcher) {
        val failure = clientError("""{"error":"Validation failed","errors":{"newPassword":"Too common."}}""")
        val vm = AuthViewModel(object : FakeBeautyApi() {
            override suspend fun resetPassword(request: ResetPasswordRequest) { throw failure }
        }, mock<TokenStore>())

        vm.resetPassword("tok", strong, strong)
        advanceUntilIdle()

        val state = vm.resetPasswordState as AuthViewModel.ResetPasswordState.Error
        assertEquals(mapOf("newPassword" to "Too common."), state.fieldErrors)
    }

    /** A real [io.ktor.client.plugins.ClientRequestException] with a 400 body, as the login client throws it. */
    private suspend fun clientError(body: String): Exception {
        val client = HttpClient(MockEngine {
            respond(body, HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }) {
            expectSuccess = true
            install(ContentNegotiation) { json() }
        }
        return runCatching { client.post("api/auth/reset-password") }.exceptionOrNull() as Exception
    }
}
