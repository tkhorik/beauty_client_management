package com.beauty.app.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beauty.app.data.api.AuthRequest
import com.beauty.app.data.api.BeautyApi
import com.beauty.app.data.api.ForgotPasswordRequest
import com.beauty.app.data.api.RefreshRequest
import com.beauty.app.data.api.RegisterRequest
import com.beauty.app.data.api.ResetPasswordRequest
import com.beauty.app.data.api.ValidationErrorResponse
import com.beauty.app.data.api.fieldMessageCodes
import com.beauty.app.data.local.OrgStore
import com.beauty.app.data.local.TokenStore
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

class AuthViewModel(
    private val api: BeautyApi,
    private val tokenStore: TokenStore,
    /**
     * Cleared on logout alongside the tokens. Nullable so the existing tests,
     * which have no Android context to build one from, need no change.
     */
    private val orgStore: OrgStore? = null,
    private val registrationLanguage: () -> String? = { null }
) : ViewModel() {

    // Volatile drafts survive Activity recreation but are never written to saved state.
    var loginPassword by mutableStateOf("")
    var registerPassword by mutableStateOf("")
    var registerConfirmPassword by mutableStateOf("")

    /**
     * Optional handle of an organization to ask to join while signing up.
     * Held here rather than in the screen so a `?join=` link can pre-fill it
     * before the screen exists.
     */
    var registerOrganizationSlug by mutableStateOf("")
    var resetLink by mutableStateOf("")
    var resetNewPassword by mutableStateOf("")
    var resetConfirmPassword by mutableStateOf("")

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

    /**
     * Note what is missing: any state meaning "no account for that address".
     *
     * The backend answers `/forgot-password` with the same 200 and the same
     * body for a registered address, an unregistered one and a malformed one,
     * so that the endpoint cannot be used to test which addresses have
     * accounts. A state this screen could branch on would put that signal back
     * — so [Sent] is the only success, and it says nothing.
     */
    sealed interface ForgotPasswordState {
        object Idle : ForgotPasswordState
        object Loading : ForgotPasswordState

        /** The request was accepted. Carries no claim that mail was actually sent. */
        object Sent : ForgotPasswordState

        /**
         * Reserved for failures that are about *this device*: no network, or a
         * rate limit keyed on this IP. Neither reveals anything about the
         * address, and both are things the user can act on.
         */
        data class Error(val message: String) : ForgotPasswordState
    }

    sealed interface ResetPasswordState {
        object Idle : ResetPasswordState
        object Loading : ResetPasswordState

        /** Password changed; every session was revoked and none was issued. */
        object Done : ResetPasswordState

        /**
         * [fieldErrors] is keyed `link`, `newPassword` and `confirmPassword`;
         * [message] is for failures that belong to no single field.
         */
        data class Error(
            val message: String? = null,
            val fieldErrors: Map<String, String> = emptyMap()
        ) : ResetPasswordState
    }

    var loginState: LoginState by mutableStateOf(LoginState.Idle)
        private set

    var registerState: RegisterState by mutableStateOf(RegisterState.Idle)
        private set

    var forgotPasswordState: ForgotPasswordState by mutableStateOf(ForgotPasswordState.Idle)
        private set

    var resetPasswordState: ResetPasswordState by mutableStateOf(ResetPasswordState.Idle)
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
                tokenStore.saveSession(response.token, response.refreshToken, response.user.id)
                loginPassword = ""
                LoginState.Success
            } catch (e: ClientRequestException) {
                if (e.response.status == HttpStatusCode.Unauthorized) {
                    LoginState.Error("INVALID_CREDENTIALS")
                } else {
                    LoginState.Error("LOGIN_FAILED")
                }
            } catch (e: Exception) {
                LoginState.Error("NETWORK_ERROR")
            }
        }
    }

    /**
     * Validates locally first, then registers. On success the returned token is
     * persisted exactly as it is after login, so the user lands in the app
     * signed in rather than being bounced back to a login form.
     */
    fun register(
        email: String,
        password: String,
        confirmPassword: String,
        fullName: String,
        includeOrganization: Boolean = true
    ) {
        val normalisedEmail = AuthValidation.normaliseEmail(email)
        val trimmedName = fullName.trim()
        val organizationSlug = registerOrganizationSlug.trim().lowercase()
            .takeIf { includeOrganization && it.isNotEmpty() }

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
                        fullName = trimmedName,
                        languagePreference = registrationLanguage(),
                        organizationSlug = organizationSlug
                    )
                )
                tokenStore.saveSession(response.token, response.refreshToken, response.user.id)
                registerPassword = ""
                registerConfirmPassword = ""
                // Filed with the account; the organization screen shows it as
                // pending and must not offer to send it again.
                registerOrganizationSlug = ""
                RegisterState.Success
            } catch (e: ClientRequestException) {
                when (e.response.status) {
                    HttpStatusCode.BadRequest -> {
                        // The server may enforce rules this client does not know
                        // about yet, so surface its messages rather than a
                        // generic one.
                        val parsed = runCatching { e.response.body<ValidationErrorResponse>() }.getOrNull()
                        if (parsed != null && parsed.fieldMessageCodes().isNotEmpty()) {
                            RegisterState.Error(fieldErrors = parsed.fieldMessageCodes())
                        } else {
                            RegisterState.Error(message = "VALIDATION_FAILED")
                        }
                    }
                    HttpStatusCode.Conflict -> RegisterState.Error(
                        fieldErrors = mapOf("email" to "EMAIL_ALREADY_EXISTS")
                    )
                    HttpStatusCode.TooManyRequests -> RegisterState.Error(
                        message = "TOO_MANY_ATTEMPTS"
                    )
                    else -> RegisterState.Error(message = "REGISTER_FAILED")
                }
            } catch (e: Exception) {
                RegisterState.Error(message = "NETWORK_ERROR")
            }
        }
    }

    /**
     * Asks the backend to mail a reset link.
     *
     * The address is checked for *shape* before sending — that is pure syntax
     * and reveals nothing about any account — but the outcome afterwards is
     * uniform. A 5xx or an unexpected status still lands on [Sent]: an error
     * shown only for addresses the server recognises would rebuild the
     * enumeration oracle the endpoint exists to prevent, just by a longer
     * route. Only a failure that cannot depend on the address at all — the
     * request never left the device, or was rate-limited by IP — is surfaced.
     *
     * The new password is set either on the web app, where the emailed link
     * points, or in-app via [resetPassword] with the pasted link.
     */
    fun forgotPassword(email: String) {
        val normalisedEmail = AuthValidation.normaliseEmail(email)
        AuthValidation.emailError(normalisedEmail)?.let {
            forgotPasswordState = ForgotPasswordState.Error(it)
            return
        }

        viewModelScope.launch {
            forgotPasswordState = ForgotPasswordState.Loading
            forgotPasswordState = try {
                api.forgotPassword(ForgotPasswordRequest(normalisedEmail))
                ForgotPasswordState.Sent
            } catch (e: ClientRequestException) {
                if (e.response.status == HttpStatusCode.TooManyRequests) {
                    ForgotPasswordState.Error("TOO_MANY_ATTEMPTS")
                } else {
                    ForgotPasswordState.Sent
                }
            } catch (e: ResponseException) {
                // 5xx. The server was reached, so whether it succeeded is not
                // something the user can usefully be told apart from success.
                ForgotPasswordState.Sent
            } catch (e: Exception) {
                ForgotPasswordState.Error("NETWORK_ERROR")
            }
        }
    }

    /**
     * Completes a reset with the token from a pasted reset link.
     *
     * [token] is null when the pasted text is not a reset link for this
     * deployment; the screen does that parsing. The password is checked locally
     * first so a too-short one is caught before the request — the server
     * validates before spending the token too, so either way the link survives
     * a rejected password.
     */
    private var resetJob: Job? = null

    fun resetPassword(token: String?, newPassword: String, confirmPassword: String) {
        val localErrors = buildMap {
            if (token == null) put("link", "REQUIRED_LINK")
            AuthValidation.passwordError(newPassword)?.let { put("newPassword", it) }
            AuthValidation.confirmPasswordError(newPassword, confirmPassword)
                ?.let { put("confirmPassword", it) }
        }
        if (localErrors.isNotEmpty() || token == null) {
            resetPasswordState = ResetPasswordState.Error(fieldErrors = localErrors)
            return
        }

        resetJob?.cancel()
        resetJob = viewModelScope.launch {
            resetPasswordState = ResetPasswordState.Loading
            resetPasswordState = try {
                api.resetPassword(ResetPasswordRequest(token, newPassword))
                tokenStore.clearToken()
                orgStore?.clear()
                resetLink = ""
                resetNewPassword = ""
                resetConfirmPassword = ""
                ResetPasswordState.Done
            } catch (e: ClientRequestException) {
                when (e.response.status) {
                    HttpStatusCode.BadRequest -> {
                        // Field errors mean the password was refused and the
                        // link is still good. A flat `error` means the token is
                        // unknown, used or expired — one message for all three.
                        val parsed = runCatching { e.response.body<ValidationErrorResponse>() }.getOrNull()
                        if (parsed != null && parsed.fieldMessageCodes().isNotEmpty()) {
                            ResetPasswordState.Error(fieldErrors = parsed.fieldMessageCodes())
                        } else {
                            ResetPasswordState.Error(
                                fieldErrors = mapOf(
                                    "link" to "INVALID_RESET_TOKEN"
                                )
                            )
                        }
                    }
                    HttpStatusCode.TooManyRequests -> ResetPasswordState.Error(
                        message = "TOO_MANY_ATTEMPTS"
                    )
                    else -> ResetPasswordState.Error(message = "RESET_FAILED")
                }
            } catch (e: ResponseException) {
                ResetPasswordState.Error(message = "RESET_FAILED")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ResetPasswordState.Error(message = "NETWORK_ERROR")
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
                // The next account to sign in on this device must not inherit
                // a selected organization it may have no membership of.
                orgStore?.clear()
                onComplete()
            }
        }
    }

    /** Clears transient auth state when moving between the auth screens. */
    fun resetState() {
        resetJob?.cancel()
        loginState = LoginState.Idle
        registerState = RegisterState.Idle
        forgotPasswordState = ForgotPasswordState.Idle
        resetPasswordState = ResetPasswordState.Idle
    }
}
