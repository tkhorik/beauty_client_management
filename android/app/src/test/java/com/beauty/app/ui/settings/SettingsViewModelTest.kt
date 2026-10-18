package com.beauty.app.ui.settings

import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.FakeBeautyApi
import com.beauty.app.data.api.AuthResponse
import com.beauty.app.data.api.ChangePasswordRequest
import com.beauty.app.data.api.UpdateProfileRequest
import com.beauty.app.data.api.UserDto
import com.beauty.app.data.local.TokenStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val tokenStore = mock<TokenStore>()
    private val user = UserDto("user", "user@example.com", "Original", "2026-10-01")
    private val password = "a new strong password"

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `profile loading guards duplicate taps resets and edits before and during dispatch`() = runTest(dispatcher) {
        val result = CompletableDeferred<UserDto>()
        val requests = mutableListOf<UpdateProfileRequest>()
        val vm = model(object : FakeBeautyApi() {
            override suspend fun updateProfile(request: UpdateProfileRequest): UserDto {
                requests += request
                return result.await()
            }
        })
        vm.updateFullName("  Updated  ")
        vm.saveProfile()
        assertEquals(SettingsViewModel.ProfileState.Loading, vm.profileState)
        vm.resetProfileState()
        vm.updateFullName("must not overwrite")
        vm.saveProfile()
        runCurrent()
        vm.resetProfileState()
        vm.saveProfile()
        assertEquals(SettingsViewModel.ProfileState.Loading, vm.profileState)
        assertEquals("  Updated  ", vm.fullName)
        assertEquals(listOf("Updated"), requests.map { it.fullName })
        result.complete(user.copy(fullName = "Updated"))
        advanceUntilIdle()
        assertEquals("Updated", vm.fullName)
        assertEquals(SettingsViewModel.ProfileState.Success, vm.profileState)
        vm.updateFullName("Next edit")
        vm.resetProfileState()
        assertEquals("Next edit", vm.fullName)
        assertEquals(SettingsViewModel.ProfileState.Idle, vm.profileState)
    }

    @Test fun `late initial profile fetch cannot overwrite an edit or a completed save`() = runTest(dispatcher) {
        val initial = CompletableDeferred<UserDto>()
        val vm = model(object : FakeBeautyApi() {
            override suspend fun getCurrentUser() = initial.await()
            override suspend fun updateProfile(request: UpdateProfileRequest) = user.copy(fullName = request.fullName)
        })
        runCurrent()
        vm.updateFullName("New name")
        vm.saveProfile()
        runCurrent()
        assertEquals(SettingsViewModel.ProfileState.Success, vm.profileState)
        initial.complete(user)
        advanceUntilIdle()
        assertEquals("New name", vm.fullName)
        assertEquals(user.email, vm.email)
    }

    @Test fun `initial profile load respects edits made before its coroutine starts`() = runTest(dispatcher) {
        val vm = model(object : FakeBeautyApi() {
            override suspend fun getCurrentUser() = user
        })
        vm.updateFullName("Typed immediately")
        advanceUntilIdle()
        assertEquals("Typed immediately", vm.fullName)
        assertEquals(user.email, vm.email)
    }

    @Test fun `password request guards drafts and duplicate invalid submissions then clears only on success`() = runTest(dispatcher) {
        val result = CompletableDeferred<AuthResponse>()
        val requests = mutableListOf<ChangePasswordRequest>()
        val vm = model(object : FakeBeautyApi() {
            override suspend fun changePassword(request: ChangePasswordRequest): AuthResponse {
                requests += request
                return result.await()
            }
        })
        vm.currentPasswordDraft = "old password"
        vm.newPasswordDraft = password
        vm.confirmPasswordDraft = password
        vm.changePassword(vm.currentPasswordDraft, password, password)
        vm.resetPasswordState()
        vm.changePassword("", "", "")
        assertEquals(SettingsViewModel.PasswordState.Loading, vm.passwordState)
        vm.currentPasswordDraft = "should not change"
        vm.newPasswordDraft = "should not change"
        vm.confirmPasswordDraft = "should not change"
        runCurrent()
        vm.changePassword("old password", password, password)
        assertEquals(1, requests.size)
        assertEquals("old password", vm.currentPasswordDraft)
        assertEquals(password, vm.newPasswordDraft)
        assertEquals(password, vm.confirmPasswordDraft)
        // A password request does not lock the independent profile form.
        vm.updateFullName("Editable")
        assertEquals("Editable", vm.fullName)
        result.complete(AuthResponse("access", "refresh", user = user))
        advanceUntilIdle()
        verify(tokenStore).saveSession("access", "refresh", null)
        assertEquals(SettingsViewModel.PasswordState.Success, vm.passwordState)
        assertEquals("", vm.currentPasswordDraft)
        assertEquals("", vm.newPasswordDraft)
        assertEquals("", vm.confirmPasswordDraft)
        vm.newPasswordDraft = "new draft after success"
        runCurrent()
        assertEquals("new draft after success", vm.newPasswordDraft)
    }

    @Test fun `failed password request retains drafts and releases guard for retry`() = runTest(dispatcher) {
        var calls = 0
        val vm = model(object : FakeBeautyApi() {
            override suspend fun changePassword(request: ChangePasswordRequest): AuthResponse {
                calls++
                error("offline")
            }
        })
        vm.currentPasswordDraft = "old password"
        vm.newPasswordDraft = password
        vm.confirmPasswordDraft = password
        vm.changePassword(vm.currentPasswordDraft, password, password)
        advanceUntilIdle()
        assertTrue(vm.passwordState is SettingsViewModel.PasswordState.Error)
        assertEquals("old password", vm.currentPasswordDraft)
        assertEquals(password, vm.newPasswordDraft)
        assertEquals(password, vm.confirmPasswordDraft)
        vm.resetPasswordState()
        vm.changePassword(vm.currentPasswordDraft, password, password)
        advanceUntilIdle()
        assertEquals(2, calls)
    }

    private fun model(api: FakeBeautyApi) = SettingsViewModel(BeautyRepository(api, mock(), mock()), tokenStore)
}
