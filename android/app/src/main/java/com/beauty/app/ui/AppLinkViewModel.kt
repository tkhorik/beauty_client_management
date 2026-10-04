package com.beauty.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beauty.app.data.api.BeautyApi
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Activity-scoped in-memory inbox. No tokens in saved state, routes, or diagnostic output. */
internal class AppLinkViewModel : ViewModel() {
    var inbox by mutableStateOf<List<AppLink>>(emptyList())
        private set
    var resetToken by mutableStateOf<String?>(null)
        private set
    var resetGeneration by mutableStateOf(0)
        private set
    var pendingOrganizationToken by mutableStateOf<String?>(null)
        private set
    var verificationMessage by mutableStateOf<String?>(null)
        private set
    var verifying by mutableStateOf(false)
        private set
    var profileRevision by mutableStateOf(0)
        private set

    private var boundAccount: String? = null
    private var organizationSession = 0
    fun organizationKey(accountId: String?): String {
        if (boundAccount != accountId) { boundAccount = accountId; organizationSession++ }
        return "organizations_${accountId}_$organizationSession"
    }

    fun receive(raw: String) { parseAppLink(raw)?.let(::receive) }
    fun receive(link: AppLink) { inbox = inbox + link }
    fun take(): AppLink? = inbox.firstOrNull()?.also { inbox = inbox.drop(1) }
    fun reset(token: String?) { resetToken = token; resetGeneration++ }
    fun clearReset() { resetToken = null }
    fun holdOrganization(token: String) { pendingOrganizationToken = token }
    fun takeOrganization(): String? = pendingOrganizationToken.also { pendingOrganizationToken = null }

    fun verify(link: AppLink.VerifyEmail, api: BeautyApi, refreshProfile: suspend () -> Unit = {}) {
        verificationMessage = null
        verifying = true
        viewModelScope.launch {
            verificationMessage = try {
                if (link.token != null) {
                    api.verifyEmail(link.token)
                    try { refreshProfile() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    "EMAIL_CONFIRMED"
                } else if (link.status == "success") {
                    "EMAIL_CONFIRMED"
                } else {
                    "INVALID_VERIFICATION_TOKEN"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientRequestException) {
                if (e.response.status == HttpStatusCode.BadRequest)
                    "INVALID_VERIFICATION_TOKEN"
                else "VERIFY_FAILED"
            } catch (e: Exception) {
                "VERIFY_NETWORK"
            } finally {
                verifying = false
                profileRevision++
            }
        }
    }
}
