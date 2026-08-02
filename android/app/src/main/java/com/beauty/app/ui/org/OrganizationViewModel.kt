package com.beauty.app.ui.org

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.api.MemberDto
import com.beauty.app.data.api.OrganizationDto
import com.beauty.app.data.local.OrgStore
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.launch

/**
 * Organization selection, onboarding and (for administrators) membership
 * management.
 *
 * Nothing here is a security control. Every decision it renders — who is an
 * admin, which organizations exist, whether a removal is allowed — is the
 * server's answer, re-checked by the backend on each call. This ViewModel's job
 * is to make the app's state agree with that answer, not to enforce it.
 */
class OrganizationViewModel(
    private val repository: BeautyRepository,
    private val orgStore: OrgStore
) : ViewModel() {

    var organizations by mutableStateOf<List<OrganizationDto>>(emptyList())
        private set
    var activeOrgId by mutableStateOf(orgStore.getActiveOrgId())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    /** The roster of [activeOrgId], loaded on demand and only for administrators. */
    var members by mutableStateOf<List<MemberDto>>(emptyList())
        private set

    val activeOrganizations: List<OrganizationDto> get() = organizations.filter { it.isActive }

    val current: OrganizationDto? get() = activeOrganizations.firstOrNull { it.id == activeOrgId }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val list = repository.getOrganizations()
                organizations = list

                // Re-validate the remembered choice against what the server just
                // said. A user removed from an organization since last launch
                // still has its id on disk, and keeping it selected would leave
                // every request coming back 403 with nothing on screen to
                // explain why.
                val active = list.filter { it.isActive }
                val stored = orgStore.getActiveOrgId()
                val next = if (active.any { it.id == stored }) stored else active.firstOrNull()?.id
                orgStore.setActiveOrgId(next)
                activeOrgId = next
            } catch (e: Exception) {
                error = e.friendlyMessage("Could not load your organizations.")
            } finally {
                loading = false
            }
        }
    }

    fun select(orgId: String) {
        orgStore.setActiveOrgId(orgId)
        activeOrgId = orgId
        members = emptyList()
    }

    fun createOrganization(name: String, slug: String?) {
        viewModelScope.launch {
            error = null
            notice = null
            try {
                val created = repository.createOrganization(name.trim(), slug?.trim())
                select(created.id)
                refresh()
            } catch (e: Exception) {
                error = e.friendlyMessage("Could not create the organization.")
            }
        }
    }

    fun requestToJoin(slug: String) {
        viewModelScope.launch {
            error = null
            notice = null
            try {
                val result = repository.requestToJoinOrganization(slug.trim().lowercase())
                // ACTIVE means there was a standing invitation and this was the
                // acceptance; PENDING means an administrator still has to act.
                notice = if (result.isActive) {
                    "You have joined ${result.name}."
                } else {
                    "Request sent to ${result.name}. An administrator has to approve it."
                }
                refresh()
            } catch (e: Exception) {
                error = e.friendlyMessage("Could not send the request.")
            }
        }
    }

    fun loadMembers() {
        val orgId = activeOrgId ?: return
        viewModelScope.launch {
            error = null
            try {
                members = repository.getMembers(orgId)
            } catch (e: Exception) {
                error = e.friendlyMessage("Could not load members.")
            }
        }
    }

    fun approve(userId: String) = memberAction("Request approved.") { orgId ->
        repository.approveMember(orgId, userId)
    }

    fun remove(userId: String) = memberAction("Member removed.") { orgId ->
        repository.removeMember(orgId, userId)
    }

    fun changeRole(userId: String, role: String) = memberAction("Role updated.") { orgId ->
        repository.changeMemberRole(orgId, userId, role)
    }

    fun invite(email: String, role: String) = memberAction("Invitation sent.") { orgId ->
        repository.inviteMember(orgId, email.trim().lowercase(), role)
    }

    fun clearMessages() {
        error = null
        notice = null
    }

    private fun memberAction(success: String, action: suspend (String) -> Unit) {
        val orgId = activeOrgId ?: return
        viewModelScope.launch {
            error = null
            notice = null
            try {
                action(orgId)
                notice = success
                members = repository.getMembers(orgId)
                // The action may have changed the caller's own standing —
                // demoting or removing themselves — so the organization list is
                // re-read rather than left stale.
                refresh()
            } catch (e: Exception) {
                error = e.friendlyMessage("That action failed.")
            }
        }
    }
}

/**
 * Prefers the backend's own message where there is one.
 *
 * These endpoints return genuinely useful text — "This is the only
 * administrator", "That organization handle is already taken" — and replacing
 * it with a generic failure leaves the user with no idea what to change.
 */
private suspend fun Exception.friendlyMessage(fallback: String): String {
    if (this is ClientRequestException) {
        val body = runCatching { response.body<Map<String, String>>() }.getOrNull()
        body?.get("error")?.let { return it }
        return "$fallback (${response.status.value})"
    }
    return message ?: fallback
}
