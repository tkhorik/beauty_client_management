package com.beauty.app.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beauty.app.data.api.AuthRequest
import com.beauty.app.data.api.BeautyApi
import com.beauty.app.data.api.RefreshRequest
import com.beauty.app.data.api.RegisterRequest
import com.beauty.app.data.api.ValidationErrorResponse
import com.beauty.app.data.local.TokenStore
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.launch

class AuthViewModel(
    private val api: BeautyApi,
    private val tokenStore: TokenStore
) : ViewModel() {

    sealed interface LoginState {
        object Idle : LoginState
        object Loading : LoginState
        object Success : LoginState
        data class Error(val message: String) : LoginState
    }

    sealed interface RegisterState {
        object Idle : RegisterState
        object Loading : RegisterState
        object Success : RegisterState

        /**
         * [fieldErrors] carries the backend's per-field 400 messages so each can
         * be rendered against its own input; [message] is for failures that
         * belong to no single field (network down, unexpected status).
         */
        data class Error(
            val message: String? = null,
            val fieldErrors: Map<String, String> = emptyMap()
        ) : RegisterState
    }

    var loginState: LoginState by mutableStateOf(LoginState.Idle)
        private set

    var registerState: RegisterState by mutableStateOf(RegisterState.Idle)
        private set

    fun login(email: String, password: String) {
        viewModelScope.launch {
            loginState = LoginState.Loading
            loginState = try {
                // Normalised to match how the backend stores addresses, so an
                // account created as "Owner@x.com" is still reachable.
                val response = api.login(AuthRequest(AuthValidation.normaliseEmail(email), password))
                // Both halves together: the access token expires in minutes,
                // and the refresh token is what keeps the user signed in.
                tokenStore.saveSession(response.token, response.refreshToken)
                LoginState.Success
            } catch (e: ClientRequestException) {
                if (e.response.status == HttpStatusCode.Unauthorized) {
                    LoginState.Error("Invalid email or password.")
                } else {
                    LoginState.Error("Login failed. Please try again.")
                }
            } catch (e: Exception) {
                LoginState.Error("Server could not be reached.")
            }
        }
    }

    /**
     * Validates locally first, then registers. On success the returned token is
     * persisted exactly as it is after login, so the user lands in the app
     * signed in rather than being bounced back to a login form.
     */
    fun register(email: String, password: String, confirmPassword: String, fullName: String) {
        val normalisedEmail = AuthValidation.normaliseEmail(email)
        val trimmedName = fullName.trim()

        val localErrors = buildMap {
            AuthValidation.fullNameError(trimmedName)?.let { put("fullName", it) }
            AuthValidation.emailError(normalisedEmail)?.let { put("email", it) }
            AuthValidation.passwordError(password)?.let { put("password", it) }
            AuthValidation.confirmPasswordError(password, confirmPassword)
                ?.let { put("confirmPassword", it) }
        }
        if (localErrors.isNotEmpty()) {
            registerState = RegisterState.Error(fieldErrors = localErrors)
            return
        }

        viewModelScope.launch {
            registerState = RegisterState.Loading
            registerState = try {
                val response = api.register(
                    RegisterRequest(
                        email = normalisedEmail,
                        password = password,
                        fullName = trimmedName
                    )
                )
                tokenStore.saveSession(response.token, response.refreshToken)
                RegisterState.Success
            } catch (e: ClientRequestException) {
                when (e.response.status) {
                    HttpStatusCode.BadRequest -> {
                        // The server may enforce rules this client does not know
                        // about yet, so surface its messages rather than a
                        // generic one.
                        val parsed = runCatching { e.response.body<ValidationErrorResponse>() }.getOrNull()
                        if (parsed != null && parsed.errors.isNotEmpty()) {
                            RegisterState.Error(fieldErrors = parsed.errors)
                        } else {
                            RegisterState.Error(message = "Please check the details you entered.")
                        }
                    }
                    HttpStatusCode.Conflict -> RegisterState.Error(
                        fieldErrors = mapOf("email" to "An account with this email already exists.")
                    )
                    HttpStatusCode.TooManyRequests -> RegisterState.Error(
                        message = "Too many attempts. Please wait a moment and try again."
                    )
                    else -> RegisterState.Error(message = "Registration failed. Please try again.")
                }
            } catch (e: Exception) {
                RegisterState.Error(message = "Server could not be reached.")
            }
        }
    }

    /**
     * Ends the session: revokes the refresh token server-side, then clears
     * local storage.
     *
     * Local state is cleared in `finally` so that logout always succeeds from
     * the user's point of view. If the revoke call fails because the device is
     * offline, refusing to log out would be worse than a token that stays
     * valid until it expires — and [onComplete] still fires either way.
     */
    fun logout(onComplete: () -> Unit) {
        val refreshToken = tokenStore.getRefreshToken()
        viewModelScope.launch {
            try {
                if (!refreshToken.isNullOrBlank()) {
                    api.logout(RefreshRequest(refreshToken))
                }
            } catch (e: Exception) {
                // Best effort. The token expires on its own regardless.
            } finally {
                tokenStore.clearToken()
                onComplete()
            }
        }
    }

    /** Clears transient auth state when moving between the login and register screens. */
    fun resetState() {
        loginState = LoginState.Idle
        registerState = RegisterState.Idle
    }
}
