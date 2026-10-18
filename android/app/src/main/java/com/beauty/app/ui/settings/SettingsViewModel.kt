package com.beauty.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.api.ValidationErrorResponse
import com.beauty.app.data.api.fieldMessageCodes
import com.beauty.app.data.local.TokenStore
import com.beauty.app.ui.auth.AuthValidation
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Backs the account-settings screen: viewing/editing the display name,
 * changing the password. Separate from [com.beauty.app.ui.auth.AuthViewModel]
 * because these routes require an existing session (`authenticate("auth-jwt")`
 * on the backend), unlike login/register.
 */
class SettingsViewModel(
    private val repository: BeautyRepository,
    private val tokenStore: TokenStore
) : ViewModel() {

    // In-memory only: retain through locale recreation, never through process death.
    private var currentPasswordValue by mutableStateOf("")
    private var newPasswordValue by mutableStateOf("")
    private var confirmPasswordValue by mutableStateOf("")
    var currentPasswordDraft: String
        get() = currentPasswordValue
        set(value) { if (passwordState !is PasswordState.Loading) currentPasswordValue = value }
    var newPasswordDraft: String
        get() = newPasswordValue
        set(value) { if (passwordState !is PasswordState.Loading) newPasswordValue = value }
    var confirmPasswordDraft: String
        get() = confirmPasswordValue
        set(value) { if (passwordState !is PasswordState.Loading) confirmPasswordValue = value }

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

    private var fullNameEdited = false

    init {
        viewModelScope.launch {
            // Best-effort: the JWT itself only carries id and email, not the
            // display name, so this is the only way to populate the form. A
            // failure here just leaves the fields blank rather than blocking
            // the screen — the user can still retype their name and save.
            runCatching { repository.getCurrentUser() }.onSuccess {
                email = it.email
                if (!fullNameEdited) fullName = it.fullName
            }
        }
    }

    fun updateFullName(value: String) {
        if (profileState is ProfileState.Loading) return
        fullNameEdited = true
        fullName = value
    }

    fun saveProfile() {
        if (profileState is ProfileState.Loading) return
        val trimmed = fullName.trim()
        AuthValidation.fullNameError(trimmed)?.let {
            profileState = ProfileState.Error(fieldErrors = mapOf("fullName" to it))
            return
        }

        // Set synchronously so a second tap cannot launch another request before dispatch.
        profileState = ProfileState.Loading
        fullNameEdited = true
        viewModelScope.launch {
            profileState = try {
                val updated = repository.updateProfile(trimmed)
                fullName = updated.fullName
                ProfileState.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientRequestException) {
                if (e.response.status == HttpStatusCode.BadRequest) {
                    val parsed = runCatching { e.response.body<ValidationErrorResponse>() }.getOrNull()
                    if (parsed != null && parsed.fieldMessageCodes().isNotEmpty()) {
                        ProfileState.Error(fieldErrors = parsed.fieldMessageCodes())
                    } else {
                        ProfileState.Error(message = "VALIDATION_FAILED")
                    }
                } else {
                    ProfileState.Error(message = "PROFILE_FAILED")
                }
            } catch (e: Exception) {
                ProfileState.Error(message = "NETWORK_ERROR")
            }
        }
    }

    fun changePassword(currentPassword: String, newPassword: String, confirmNewPassword: String) {
        if (passwordState is PasswordState.Loading) return
        val localErrors = buildMap {
            if (currentPassword.isEmpty()) {
                put("currentPassword", "CURRENT_PASSWORD_REQUIRED")
            }
            val newPasswordError = AuthValidation.passwordError(newPassword)
                ?: if (newPassword == currentPassword) {
                    "PASSWORD_UNCHANGED"
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

        passwordState = PasswordState.Loading
        viewModelScope.launch {
            passwordState = try {
                val response = repository.changePassword(currentPassword, newPassword)
                // The backend just revoked every session tied to this account,
                // including the refresh token this device was holding, and
                // minted a fresh pair for this request. Adopting it here is
                // what keeps this device signed in; skipping it would have the
                // app logged out by its own password change on the next
                // refresh attempt.
                tokenStore.saveSession(response.token, response.refreshToken)
                // Clear before publishing success; a delayed UI effect must not erase a newer draft.
                currentPasswordValue = ""
                newPasswordValue = ""
                confirmPasswordValue = ""
                PasswordState.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientRequestException) {
                when (e.response.status) {
                    HttpStatusCode.Unauthorized -> PasswordState.Error(
                        fieldErrors = mapOf("currentPassword" to "CURRENT_PASSWORD_INCORRECT")
                    )
                    HttpStatusCode.BadRequest -> {
                        val parsed = runCatching { e.response.body<ValidationErrorResponse>() }.getOrNull()
                        if (parsed != null && parsed.fieldMessageCodes().isNotEmpty()) {
                            PasswordState.Error(fieldErrors = parsed.fieldMessageCodes())
                        } else {
                            PasswordState.Error(message = "VALIDATION_FAILED")
                        }
                    }
                    else -> PasswordState.Error(message = "PASSWORD_CHANGE_FAILED")
                }
            } catch (e: Exception) {
                PasswordState.Error(message = "NETWORK_ERROR")
            }
        }
    }

    /** Clears stale success/error banners when the fields are edited again. */
    fun resetProfileState() {
        if (profileState !is ProfileState.Loading) profileState = ProfileState.Idle
    }

    fun resetPasswordState() {
        if (passwordState !is PasswordState.Loading) passwordState = PasswordState.Idle
    }
}
