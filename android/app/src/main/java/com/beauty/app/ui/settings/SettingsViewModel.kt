package com.beauty.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.api.ValidationErrorResponse
import com.beauty.app.data.local.TokenStore
import com.beauty.app.ui.auth.AuthValidation
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.launch

/**
 * Backs the account-settings screen: viewing/editing the display name and
 * changing the password. Separate from [com.beauty.app.ui.auth.AuthViewModel]
 * because these routes require an existing session (`authenticate("auth-jwt")`
 * on the backend), unlike login/register.
 */
class SettingsViewModel(
    private val repository: BeautyRepository,
    private val tokenStore: TokenStore
) : ViewModel() {

    sealed interface ProfileState {
        object Idle : ProfileState
        object Loading : ProfileState
        object Success : ProfileState
        data class Error(val message: String? = null, val fieldErrors: Map<String, String> = emptyMap()) : ProfileState
    }

    sealed interface PasswordState {
        object Idle : PasswordState
        object Loading : PasswordState
        object Success : PasswordState
        data class Error(val message: String? = null, val fieldErrors: Map<String, String> = emptyMap()) : PasswordState
    }

    var email by mutableStateOf("")
        private set
    var fullName by mutableStateOf("")
        private set

    var profileState: ProfileState by mutableStateOf(ProfileState.Idle)
        private set
    var passwordState: PasswordState by mutableStateOf(PasswordState.Idle)
        private set

    init {
        viewModelScope.launch {
            // Best-effort: the JWT itself only carries id and email, not the
            // display name, so this is the only way to populate the form. A
            // failure here just leaves the fields blank rather than blocking
            // the screen — the user can still retype their name and save.
            runCatching { repository.getCurrentUser() }.onSuccess {
                email = it.email
                fullName = it.fullName
            }
        }
    }

    fun updateFullName(value: String) {
        fullName = value
    }

    fun saveProfile() {
        val trimmed = fullName.trim()
        AuthValidation.fullNameError(trimmed)?.let {
            profileState = ProfileState.Error(fieldErrors = mapOf("fullName" to it))
            return
        }

        viewModelScope.launch {
            profileState = ProfileState.Loading
            profileState = try {
                val updated = repository.updateProfile(trimmed)
                fullName = updated.fullName
                ProfileState.Success
            } catch (e: ClientRequestException) {
                if (e.response.status == HttpStatusCode.BadRequest) {
                    val parsed = runCatching { e.response.body<ValidationErrorResponse>() }.getOrNull()
                    if (parsed != null && parsed.errors.isNotEmpty()) {
                        ProfileState.Error(fieldErrors = parsed.errors)
                    } else {
                        ProfileState.Error(message = "Please check the details you entered.")
                    }
                } else {
                    ProfileState.Error(message = "Could not save changes. Please try again.")
                }
            } catch (e: Exception) {
                ProfileState.Error(message = "Server could not be reached.")
            }
        }
    }

    fun changePassword(currentPassword: String, newPassword: String, confirmNewPassword: String) {
        val localErrors = buildMap {
            if (currentPassword.isEmpty()) {
                put("currentPassword", "Enter your current password.")
            }
            val newPasswordError = AuthValidation.passwordError(newPassword)
                ?: if (newPassword == currentPassword) {
                    "New password must be different from the current password."
                } else {
                    null
                }
            newPasswordError?.let { put("newPassword", it) }
            AuthValidation.confirmPasswordError(newPassword, confirmNewPassword)
                ?.let { put("confirmNewPassword", it) }
        }
        if (localErrors.isNotEmpty()) {
            passwordState = PasswordState.Error(fieldErrors = localErrors)
            return
        }

        viewModelScope.launch {
            passwordState = PasswordState.Loading
            passwordState = try {
                val response = repository.changePassword(currentPassword, newPassword)
                // The backend just revoked every session tied to this account,
                // including the refresh token this device was holding, and
                // minted a fresh pair for this request. Adopting it here is
                // what keeps this device signed in; skipping it would have the
                // app logged out by its own password change on the next
                // refresh attempt.
                tokenStore.saveSession(response.token, response.refreshToken)
                PasswordState.Success
            } catch (e: ClientRequestException) {
                when (e.response.status) {
                    HttpStatusCode.Unauthorized -> PasswordState.Error(
                        fieldErrors = mapOf("currentPassword" to "Current password is incorrect.")
                    )
                    HttpStatusCode.BadRequest -> {
                        val parsed = runCatching { e.response.body<ValidationErrorResponse>() }.getOrNull()
                        if (parsed != null && parsed.errors.isNotEmpty()) {
                            PasswordState.Error(fieldErrors = parsed.errors)
                        } else {
                            PasswordState.Error(message = "Please check the details you entered.")
                        }
                    }
                    else -> PasswordState.Error(message = "Could not change password. Please try again.")
                }
            } catch (e: Exception) {
                PasswordState.Error(message = "Server could not be reached.")
            }
        }
    }

    /** Clears stale success/error banners when the fields are edited again. */
    fun resetProfileState() {
        profileState = ProfileState.Idle
    }

    fun resetPasswordState() {
        passwordState = PasswordState.Idle
    }
}
