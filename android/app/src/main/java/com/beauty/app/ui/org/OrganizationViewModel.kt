package com.beauty.app.ui.org

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.api.AuditEventDto
import com.beauty.app.data.api.InviteLinkDto
import com.beauty.app.data.api.MemberDto
import com.beauty.app.data.api.OrganizationDto
import com.beauty.app.data.api.ValidationErrorResponse
import com.beauty.app.data.api.fieldMessageCodes
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

    // Sensitive link is held only in this retained ViewModel, never saved state.
    var creationLinkDraft by mutableStateOf("")

    /** Where the pasted organization-creation link stands. */
    enum class CreationLinkStatus { NONE, CHECKING, VALID, INVALID }

    var creationLinkStatus by mutableStateOf(CreationLinkStatus.NONE)
        private set
    var creating by mutableStateOf(false)
        private set

    /** The backend's per-field 400 messages for the create form (`name`, `slug`). */
    var createFieldErrors by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Held here, not in the screen, so it never ends up in saved UI state. */
    private var creationToken: String? = null

    /** The roster of [activeOrgId], loaded on demand and only for administrators. */
    var members by mutableStateOf<List<MemberDto>>(emptyList())
        private set

    /** The join form's handle. Here rather than in the screen so a `?join=` link can fill it. */
    var joinDraft by mutableStateOf("")

    /** An invite link waiting for the user's yes or no. See [offerInvite]. */
    data class InviteOffer(val token: String, val status: InviteStatus, val organizationName: String? = null)
    enum class InviteStatus { CHECKING, READY, INVALID, JOINING }
    var inviteOffer by mutableStateOf<InviteOffer?>(null)
        private set

    /** The administered organization's unused invite links. */
    var inviteLinks by mutableStateOf<List<InviteLinkDto>>(emptyList())
        private set
    /** The URL of a link issued on this screen — the only time its token is ever visible. */
    var issuedInviteUrl by mutableStateOf<String?>(null)
        private set

    /** [activeOrgId]'s membership history, newest first, loaded on demand for administrators. */
    var auditEvents by mutableStateOf<List<AuditEventDto>>(emptyList())
        private set
    var auditHasMore by mutableStateOf(false)
        private set
    var auditLoading by mutableStateOf(false)
        private set

    /**
     * An organization a "new access request" email asked to open, applied once
     * the list has loaded and only if this account can manage it.
     */
    private var pendingFocus: String? = null
    private var organizationsLoaded = false

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

    /** The signed-in account's id, so the roster can hide "revoke" on the caller's own row. */
    var currentUserId by mutableStateOf<String?>(null)
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
                error = e.friendlyMessage("COULD_NOT_LOAD_ORGANIZATIONS")
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
        // The server just said where this account is an active member; any
        // other organization cached on the device — removed, revoked, archived
        // — is wiped, so it cannot be read offline either.
        runCatching { repository.purgeOrganizationsExcept(list.filter { it.isActive }.map { it.id }.toSet()) }

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
        organizationsLoaded = true
        applyPendingFocus()
    }

    /** Switches to [orgId] for its members screen once it is known to be manageable. */
    fun focusMembers(orgId: String) {
        pendingFocus = orgId
        applyPendingFocus()
    }

    private fun applyPendingFocus() {
        val target = pendingFocus ?: return
        if (!organizationsLoaded) return
        pendingFocus = null
        // Absent from the list means not an active member there; the link is dropped.
        val org = activeOrganizations.firstOrNull { it.id == target } ?: return
        if (canManage(org) && org.id != activeOrgId) select(org.id)
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
            .onSuccess {
                isSuperAdmin = it.isSuperAdmin
                currentUserId = it.id
            }
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
        inviteLinks = emptyList()
        issuedInviteUrl = null
        auditEvents = emptyList()
        auditHasMore = false
    }

    /**
     * Loads a page of [orgId]'s history. With [more], appends the page older
     * than what is already shown.
     */
    fun loadAudit(orgId: String, more: Boolean = false) {
        if (auditLoading) return
        viewModelScope.launch {
            auditLoading = true
            try {
                val before = if (more) auditEvents.lastOrNull()?.createdAt else null
                val page = repository.getOrganizationAudit(orgId, before)
                auditEvents = if (more) auditEvents + page else page
                auditHasMore = page.size == AUDIT_PAGE_SIZE
            } catch (e: Exception) {
                error = e.friendlyMessage("COULD_NOT_LOAD_ACTIVITY")
            } finally {
                auditLoading = false
            }
        }
    }

    /**
     * Checks a token taken from a pasted creation link, so the user hears
     * "invalid link" before filling in a name. Advisory only, like the web
     * onboarding: the token can still be spent before [createOrganization]
     * redeems it, and the server has the final word then.
     */
    fun checkCreationToken(token: String) {
        creationToken = token
        creationLinkStatus = CreationLinkStatus.CHECKING
        createFieldErrors = emptyMap()
        viewModelScope.launch {
            val valid = runCatching { repository.validateCreationToken(token) }.getOrDefault(false)
            // Ignore an answer for a link the user has since replaced.
            if (creationToken == token) {
                creationLinkStatus = if (valid) CreationLinkStatus.VALID else CreationLinkStatus.INVALID
            }
        }
    }

    fun clearCreationLink() {
        creationToken = null
        creationLinkStatus = CreationLinkStatus.NONE
        createFieldErrors = emptyMap()
    }

    fun createOrganization(name: String, slug: String?, onCreated: () -> Unit = {}) {
        val token = creationToken ?: return
        if (creating) return
        viewModelScope.launch {
            creating = true
            error = null
            notice = null
            createFieldErrors = emptyMap()
            try {
                val created = repository.createOrganization(name.trim(), slug?.trim()?.lowercase(), token)
                if (creationToken == token) clearCreationLink()
                select(created.id)
                notice = "ORGANIZATION_CREATED:${created.name}"
                refresh()
                onCreated()
            } catch (e: Exception) {
                val fieldErrors = (e as? ClientRequestException)
                    ?.let { runCatching { it.response.body<ValidationErrorResponse>() }.getOrNull() }
                    ?.fieldMessageCodes().orEmpty()
                if (fieldErrors.isNotEmpty()) {
                    createFieldErrors = fieldErrors
                } else {
                    error = e.friendlyMessage("COULD_NOT_CREATE_ORGANIZATION")
                }
            } finally {
                creating = false
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
                    "ORGANIZATION_JOINED:${result.name}"
                } else {
                    "ORGANIZATION_REQUESTED:${result.name}"
                }
                refresh()
            } catch (e: Exception) {
                // The backend's REQUEST_DECLINED (asked again too soon after a
                // decline) shares its code with this screen's own "you declined
                // a request" notice, so it is renamed before it is shown.
                error = e.friendlyMessage("COULD_NOT_SEND_REQUEST")
                    .let { if (it == "REQUEST_DECLINED") "JOIN_REQUEST_DECLINED" else it }

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
                inviteLinks = repository.getInviteLinks(orgId)
            } catch (e: Exception) {
                error = e.friendlyMessage("COULD_NOT_LOAD_MEMBERS")
            }
        }
    }

    fun approve(orgId: String, userId: String) = memberAction(orgId, "REQUEST_APPROVED") {
        repository.approveMember(orgId, userId)
    }

    fun remove(orgId: String, userId: String) = memberAction(orgId, "MEMBER_REMOVED") {
        repository.removeMember(orgId, userId)
    }

    /**
     * Answers a join request with no. The request is kept as declined, so the
     * requester is told and has to wait out the backend's cooldown before
     * asking again — unlike [remove], which deletes the row.
     */
    fun decline(orgId: String, userId: String) = memberAction(orgId, "REQUEST_DECLINED") {
        repository.declineMember(orgId, userId)
    }

    /** Revokes an active member; unlike [remove], they cannot ask back in until restored. */
    fun revoke(orgId: String, userId: String) = memberAction(orgId, "ACCESS_REVOKED") {
        repository.revokeMember(orgId, userId)
    }

    fun restore(orgId: String, userId: String) = memberAction(orgId, "ACCESS_RESTORED") {
        repository.restoreMember(orgId, userId)
    }

    fun changeRole(orgId: String, userId: String, role: String) = memberAction(orgId, "ROLE_UPDATED") {
        repository.changeMemberRole(orgId, userId, role)
    }

    fun invite(orgId: String, email: String, role: String) = memberAction(orgId, "INVITATION_SENT") {
        repository.inviteMember(orgId, email.trim().lowercase(), role)
    }

    /**
     * Shows the "join this organization?" prompt for an invite link. Nothing
     * is joined until [acceptInvite]; the preview only names the organization.
     */
    fun offerInvite(token: String) {
        inviteOffer = InviteOffer(token, InviteStatus.CHECKING)
        viewModelScope.launch {
            val organization = runCatching { repository.previewInviteLink(token) }.getOrNull()
                ?.takeIf { it.valid }?.organization
            if (inviteOffer?.token == token) {
                inviteOffer = if (organization != null) InviteOffer(token, InviteStatus.READY, organization.name)
                    else InviteOffer(token, InviteStatus.INVALID)
            }
        }
    }

    fun dismissInvite() { inviteOffer = null }

    /** Joins through the offered link. There is no approval step: the result is an active membership. */
    fun acceptInvite() {
        val offer = inviteOffer?.takeIf { it.status == InviteStatus.READY } ?: return
        inviteOffer = offer.copy(status = InviteStatus.JOINING)
        viewModelScope.launch {
            error = null
            notice = null
            try {
                val org = repository.acceptInviteLink(offer.token)
                inviteOffer = null
                notice = "ORGANIZATION_JOINED:${org.name}"
                loadOrganizations()
                select(org.id)
            } catch (e: Exception) {
                inviteOffer = null
                error = e.friendlyMessage("INVITE_LINK_INVALID")
                runCatching { loadOrganizations() }
            }
        }
    }

    fun issueInviteLink(orgId: String) {
        viewModelScope.launch {
            error = null
            notice = null
            try {
                issuedInviteUrl = repository.createInviteLink(orgId).url
                inviteLinks = repository.getInviteLinks(orgId)
                notice = "INVITE_LINK_CREATED"
            } catch (e: Exception) {
                error = e.friendlyMessage("ACTION_FAILED")
            }
        }
    }

    fun revokeInviteLink(orgId: String, linkId: String) = memberAction(orgId, "INVITE_LINK_REVOKED") {
        repository.revokeInviteLink(orgId, linkId)
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
                inviteLinks = repository.getInviteLinks(orgId)
                if (auditEvents.isNotEmpty()) loadAudit(orgId)
                // The action may have changed the caller's own standing —
                // demoting or removing themselves — so the organization list is
                // re-read rather than left stale.
                refresh()
            } catch (e: Exception) {
                error = e.friendlyMessage("ACTION_FAILED")
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
        val body = runCatching { response.body<com.beauty.app.data.api.ApiErrorResponse>() }.getOrNull()
        return body?.code ?: fallback
    }
    return fallback
}

/** The backend's default page size for `GET /organizations/{id}/audit`. */
private const val AUDIT_PAGE_SIZE = 50
