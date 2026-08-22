package com.beauty.app.ui.admin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.api.AdminOrganizationDto
import com.beauty.app.data.api.AdminUserDto
import com.beauty.app.data.api.MemberDto
import com.beauty.app.data.api.OrganizationCreationTokenDto
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.launch

/**
 * The Android side of the admin panel: every account, every organization, and
 * the organization-creation links.
 *
 * Nothing here is a security control. `requireSuperAdmin()` on the backend
 * refuses all of it for an ordinary account, so the worst a bug in this class
 * can do is show the wrong thing to someone who was allowed to look — never
 * grant anything. It is only ever reached from an entry point gated on
 * [com.beauty.app.data.api.UserDto.isSuperAdmin], which is itself just a hint.
 *
 * No caching and no Room: this is a system-wide snapshot an operator acts on
 * immediately, and a stale copy showing an account as active seconds after it
 * was suspended would be worse than a spinner.
 */
class AdminViewModel(private val repository: BeautyRepository) : ViewModel() {

    var users by mutableStateOf<List<AdminUserDto>>(emptyList())
        private set
    var organizations by mutableStateOf<List<AdminOrganizationDto>>(emptyList())
        private set
    var links by mutableStateOf<List<OrganizationCreationTokenDto>>(emptyList())
        private set

    /** The signed-in account's own id, so its row offers no self-suspend button. */
    var selfId by mutableStateOf<String?>(null)
        private set

    /**
     * Only the very first load blanks the screen.
     *
     * Every reload after an action re-uses [loadAll], and flipping a shared
     * loading flag there would replace the list with a placeholder on every
     * suspend or revoke — including, in the past, throwing away
     * [freshToken] mid-flight.
     */
    var initialLoading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    /**
     * The raw token from the link just issued.
     *
     * Held here rather than in the composable that displays it because it is
     * recoverable **exactly once**: the server stores only a hash. A
     * recomposition that dropped it would lose the link for good, with no way
     * to get it back other than issuing another.
     */
    var freshToken by mutableStateOf<String?>(null)
        private set

    /** The organization whose roster is open, and that roster. */
    var managingOrg by mutableStateOf<AdminOrganizationDto?>(null)
        private set
    var members by mutableStateOf<List<MemberDto>>(emptyList())
        private set

    init {
        loadAll()
    }

    fun loadAll() {
        viewModelScope.launch {
            error = null
            try {
                users = repository.getAdminUsers()
                organizations = repository.getAdminOrganizations()
                links = repository.getCreationTokens()
                // Last, and tolerated failing on its own: it decides whether one
                // row shows a button, not whether the panel can be shown.
                runCatching { repository.getCurrentUser() }.onSuccess { selfId = it.id }
            } catch (e: Exception) {
                error = e.friendlyMessage("Could not load admin data.")
            } finally {
                initialLoading = false
            }
        }
    }

    fun setSuspended(user: AdminUserDto, suspended: Boolean) = globalAction(
        if (suspended) "${user.fullName} suspended." else "${user.fullName} unsuspended."
    ) {
        repository.setUserSuspended(user.id, suspended)
    }

    fun issueLink(label: String, maxUses: Int, expiresInHours: Long) {
        viewModelScope.launch {
            error = null
            notice = null
            try {
                val issued = repository.createCreationToken(label, maxUses, expiresInHours)
                // Set before the reload, and never cleared by it.
                freshToken = issued.token
                notice = "Link issued. Copy it now — it cannot be shown again."
                loadAll()
            } catch (e: Exception) {
                error = e.friendlyMessage("Could not issue the link.")
            }
        }
    }

    fun revokeLink(id: String) = globalAction("Link revoked.") { repository.revokeCreationToken(id) }

    fun dismissFreshToken() {
        freshToken = null
    }

    // -- Membership of an arbitrary organization -----------------------------
    //
    // A super admin is an administrator of every organization as far as the
    // backend is concerned — requireOrgAccess() grants them ORG_ADMIN for any
    // X-Org-Id — so these are the ordinary organization endpoints, called with
    // an organization the caller need not belong to. There is no admin-only
    // variant of them and there does not need to be.

    fun manageMembers(org: AdminOrganizationDto) {
        managingOrg = org
        members = emptyList()
        loadMembers(org.id)
    }

    fun stopManagingMembers() {
        managingOrg = null
        members = emptyList()
        // Membership changes move the member counts this panel shows.
        loadAll()
    }

    private fun loadMembers(orgId: String) {
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

    fun clearMessages() {
        error = null
        notice = null
    }

    /**
     * An action on the global lists: run it, then re-read them.
     *
     * Not named `run`: that would shadow the stdlib function of the same name
     * inside this class, which is exactly the kind of thing that reads fine
     * and resolves somewhere surprising.
     */
    private fun globalAction(success: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            error = null
            notice = null
            try {
                action()
                notice = success
                loadAll()
            } catch (e: Exception) {
                error = e.friendlyMessage("That action failed.")
            }
        }
    }

    /** An action on one organization's roster: run it, then re-read that roster. */
    private fun memberAction(orgId: String, success: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            error = null
            notice = null
            try {
                action()
                notice = success
                members = repository.getMembers(orgId)
            } catch (e: Exception) {
                error = e.friendlyMessage("That action failed.")
            }
        }
    }
}

/**
 * Prefers the backend's own message where there is one.
 *
 * These endpoints return text worth showing verbatim — "This is the only
 * administrator", "You cannot suspend your own account" — and replacing it
 * with a generic failure leaves the operator with no idea what to change.
 */
private suspend fun Exception.friendlyMessage(fallback: String): String {
    if (this is ClientRequestException) {
        val body = runCatching { response.body<Map<String, String>>() }.getOrNull()
        body?.get("error")?.let { return it }
        return "$fallback (${response.status.value})"
    }
    return message ?: fallback
}
