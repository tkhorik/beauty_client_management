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
import kotlinx.coroutines.Job
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

    /**
     * Whether the signed-in account is a `SUPER_ADMIN`.
     *
     * Read from the profile rather than from any organization row, because it
     * is not a property of a membership: the backend grants a super admin
     * ORG_ADMIN in *every* organization, including ones they have never
     * joined. Without this the screen hid member management from them in any
     * salon where they happened to be a plain member, while the server would
     * have accepted every action behind it.
     *
     * Starts false and is only ever raised by a successful profile read, so a
     * failed or slow request shows less, never more.
     */
    var isSuperAdmin by mutableStateOf(false)
        private set

    val activeOrganizations: List<OrganizationDto> get() = organizations.filter { it.isActive }

    val current: OrganizationDto? get() = activeOrganizations.firstOrNull { it.id == activeOrgId }

    /**
     * The in-flight refresh, so overlapping triggers collapse into one request.
     *
     * The screen now asks for a refresh from three places — first composition,
     * every `ON_RESUME`, and the toolbar button — and on a cold start the first
     * two fire within milliseconds of each other. Without this they would issue
     * two identical requests whose responses could be applied in either order.
     */
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            loading = true
            error = null
            try {
                loadOrganizations()
                loadGlobalRole()
            } catch (e: Exception) {
                error = e.friendlyMessage("Could not load your organizations.")
            } finally {
                loading = false
            }
        }
    }

    /**
     * Re-reads the list and reconciles the remembered selection.
     *
     * Split out of [refresh] so it can also be called from a *failure* path
     * without clearing the message that failure produced — see [requestToJoin].
     * It deliberately touches neither [error] nor [loading]; the caller owns
     * those.
     */
    private suspend fun loadOrganizations() {
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
    }

    /**
     * Re-reads the caller's own privilege level.
     *
     * Deliberately swallows its own failure. This is a *capability hint* for
     * what to draw, not an authorization decision — the server re-checks every
     * action regardless — so a profile request that fails should leave the
     * organization list, which did load, on screen rather than replacing it
     * with an error about something the user never asked for.
     */
    private suspend fun loadGlobalRole() {
        runCatching { repository.getCurrentUser() }
            .onSuccess { isSuperAdmin = it.isSuperAdmin }
    }

    /**
     * Whether the caller may manage [org]'s membership.
     *
     * Mirrors the server's rule exactly: an `ORG_ADMIN` of that organization,
     * or a super admin anywhere.
     */
    fun canManage(org: OrganizationDto?): Boolean =
        org != null && (org.isAdmin || isSuperAdmin)

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

                // Re-read the list even though the request failed. A refusal —
                // "you are already a member" above all — is the strongest
                // evidence available that what this device is showing is out of
                // date, and it used to be the one path that did not re-sync.
                // That combination stranded users: approved on an
                // administrator's device, still displaying their old PENDING
                // row, asking again, and getting a 409 that left the stale row
                // exactly where it was. [loadOrganizations] rather than
                // [refresh] so the message above survives the update.
                runCatching { loadOrganizations() }
            }
        }
    }

    /**
     * Loads [orgId]'s roster — including its `PENDING` and `INVITED` rows,
     * which are the approval queue.
     *
     * `orgId` is a parameter rather than read from [activeOrgId] for the same
     * reason `BeautyApi` takes it on every scoped call: an ambient
     * "current organization" is how a roster action lands on the salon that
     * happened to be selected instead of the one on screen.
     */
    fun loadMembers(orgId: String) {
        viewModelScope.launch {
            error = null
            try {
                members = repository.getMembers(orgId)
            } catch (e: Exception) {
                error = e.friendlyMessage("Could not load members.")
            }
        }
    }

    fun approve(orgId: String, userId: String) = memberAction(orgId, "Request approved.") {
        repository.approveMember(orgId, userId)
    }

    fun remove(orgId: String, userId: String) = memberAction(orgId, "Member removed.") {
        repository.removeMember(orgId, userId)
    }

    fun changeRole(orgId: String, userId: String, role: String) = memberAction(orgId, "Role updated.") {
        repository.changeMemberRole(orgId, userId, role)
    }

    fun invite(orgId: String, email: String, role: String) = memberAction(orgId, "Invitation sent.") {
        repository.inviteMember(orgId, email.trim().lowercase(), role)
    }

    fun clearMessages() {
        error = null
        notice = null
    }

    private fun memberAction(orgId: String, success: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            error = null
            notice = null
            try {
                action()
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
