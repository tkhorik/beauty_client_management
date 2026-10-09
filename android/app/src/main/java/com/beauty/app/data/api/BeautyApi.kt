package com.beauty.app.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Names the organization a request is scoped to.
 *
 * Must match `ORG_HEADER` in the backend's `plugins/OrgAccess.kt`. Sent
 * explicitly on each data call rather than injected into every request by the
 * HTTP client, because the right organization is not always the one currently
 * selected in the UI — `SyncWorker` uploads visits queued while a *different*
 * organization was active, and an ambient header would file them under the
 * wrong salon.
 */
const val ORG_HEADER = "X-Org-Id"

// ──────────────────────────────────────────────
// DTOs
// ──────────────────────────────────────────────

@Serializable
data class ClientDto(
    val id: String,
    val name: String,
    val phone: String,
    val email: String? = null,
    val tags: List<String> = emptyList(),
    val customFields: JsonObject = JsonObject(emptyMap()),
    val totalVisits: Int = 0,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class CreateVisitRequest(
    val clientId: String,
    val visitDateTime: String,
    val durationMinutes: Int,
    val procedureNotes: String,
    val status: String
)

@Serializable
data class VisitDto(val id: String)

/**
 * A complete visit record returned by the history endpoint.
 *
 * This deliberately differs from [VisitDto], which is the minimal response to
 * creating an offline-queued visit. Keeping those contracts separate means a
 * future addition to the history response cannot accidentally become required
 * for the sync path.
 */
@Serializable
data class VisitHistoryDto(
    val id: String,
    val clientId: String,
    val visitDateTime: String,
    val durationMinutes: Int,
    val procedureNotes: String,
    val status: String,
    val attachments: List<VisitAttachmentDto> = emptyList()
)

/** Metadata supplied with a history entry; file bytes remain on the attachment endpoint. */
@Serializable
data class VisitAttachmentDto(
    val id: String,
    val visitId: String,
    val fileUrl: String,
    val fileType: String,
    val fileSize: Long,
    val caption: String? = null,
    val tag: String = "PROCEDURE",
    val uploadedAt: String
)

@Serializable
data class AuthRequest(val email: String, val password: String)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val fullName: String,
    val languagePreference: String? = null,
    /** Optional handle of an organization to request access to while signing up. */
    val organizationSlug: String? = null
)

/** Account language is a preference, not a detected locale. */
@Serializable
data class LanguagePreferenceRequest(val preference: String, val expectedRevision: Long, val expectedAccountId: String? = null)

@Serializable
data class LanguagePreferenceResponse(val preference: String, val revision: Long)

/**
 * The backend's 400 body for rejected input: `field name -> message`, so the
 * error can be shown against the input that caused it.
 */
@Serializable
data class ValidationErrorResponse(
    val error: String = "Validation failed",
    val errors: Map<String, String> = emptyMap(),
    val code: String = "VALIDATION_FAILED",
    val fieldErrors: Map<String, FieldErrorDto> = emptyMap()
)

@Serializable
data class FieldErrorDto(val code: String, val args: Map<String, Int> = emptyMap())

@Serializable
data class ApiErrorResponse(val code: String? = null, val error: String? = null)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val fullName: String,
    val createdAt: String,
    /**
     * Whether the address has been confirmed. Defaults to true, which is the
     * safe direction for a *client*: a backend that does not send the field
     * should produce no banner rather than nag every user of an older server.
     * The server, not this flag, decides whether a write is accepted.
     */
    val emailVerified: Boolean = true,
    /** ISO timestamp after which unverified accounts become read-only. */
    val verificationDeadline: String? = null,
    /**
     * The account's system-wide privilege level: `USER` or `SUPER_ADMIN`.
     *
     * Distinct from [OrganizationDto.role], which is a capability *within one
     * organization*. A `SUPER_ADMIN` is an administrator of every organization
     * as far as the backend is concerned — `requireOrgAccess()` grants them
     * ORG_ADMIN for any `X-Org-Id` without reading the membership table — so a
     * client that only looks at the membership role hides management it would
     * have been allowed to perform.
     *
     * Defaults to `USER`, the same safe direction as [emailVerified]: a server
     * that does not send the field grants nothing extra rather than everything.
     */
    val globalRole: String = "USER",
    val languagePreference: String = "system",
    val languageRevision: Long = 0
) {
    val isSuperAdmin: Boolean get() = globalRole == "SUPER_ADMIN"
}

@Serializable
data class AuthResponse(
    val token: String,
    /** Null only for browser clients, which receive it as an httpOnly cookie. */
    val refreshToken: String? = null,
    val expiresInSeconds: Long = 0,
    val user: UserDto
)

@Serializable
data class RefreshRequest(val refreshToken: String)

/**
 * Body for `POST /api/auth/forgot-password`.
 *
 * The emailed link points at the web app (`SITE_URL/reset-password?token=…`,
 * see `AccountMailer.sendPasswordReset`). The app completes the reset with
 * [ResetPasswordRequest] from a verified App Link or manual paste. Only the
 * independently configured website origin is trusted.
 */
@Serializable
data class ForgotPasswordRequest(val email: String)

/** Body for public `POST /api/auth/verify-email`. */
@Serializable
data class VerifyEmailRequest(val token: String)

/** Body for `POST /api/auth/reset-password`. The token is spent on success. */
@Serializable
data class ResetPasswordRequest(val token: String, val newPassword: String)

/** Body for `PATCH /api/users/me`. Only the display name is editable — email is the login identifier. */
@Serializable
data class UpdateProfileRequest(val fullName: String)

/**
 * Body for `POST /api/users/me/password`. The current password is required
 * even with a valid access token: a token proves the session was recently
 * authenticated, not that whoever holds the device right now knows the
 * password.
 */
@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class UpdateClientRequest(
    val name: String,
    val phone: String,
    val email: String? = null,
    val tags: List<String>,
    val customFields: JsonObject
)

// ──────────────────────────────────────────────
// Organizations
// ──────────────────────────────────────────────

/**
 * An organization together with *this* user's standing in it.
 *
 * `role` is `ORG_ADMIN` or `ORG_USER`; `status` is `ACTIVE`, `PENDING`,
 * `INVITED`, `SUSPENDED` or `DECLINED`, and only `ACTIVE` grants access to any
 * client or visit data.
 */
@Serializable
data class OrganizationDto(
    val id: String,
    val name: String,
    val slug: String,
    val role: String,
    val status: String,
    val createdAt: String? = null,
    /** Requests awaiting approval; only sent where this user is an active admin. */
    val pendingRequestCount: Int? = null,
    /** For a declined request: the earliest local date-time this user may ask again. */
    val retryAfter: String? = null
) {
    val isActive: Boolean get() = status == "ACTIVE"
    val isAdmin: Boolean get() = role == "ORG_ADMIN"
    val isDeclined: Boolean get() = status == "DECLINED"
    val isAwaitingApproval: Boolean get() = status == "PENDING" || status == "INVITED"
}

/**
 * `creationToken` is the raw token from an administrator-issued creation link
 * (`?orgToken=…`). The backend refuses creation without a redeemable one.
 */
@Serializable
data class CreateOrganizationRequest(
    val name: String,
    val slug: String? = null,
    val creationToken: String? = null
)

/** Advisory only: a valid token can still be spent by the time it is redeemed. */
@Serializable
data class ValidateCreationTokenResponse(val valid: Boolean = false)

@Serializable
data class JoinOrganizationRequest(val slug: String)

@Serializable
data class AcceptInviteLinkRequest(val token: String)

/** An outstanding single-use invite link. The raw token is only in [CreateInviteLinkResponse]. */
@Serializable
data class InviteLinkDto(
    val id: String,
    val createdByName: String,
    val expiresAt: String,
    val createdAt: String
)

@Serializable
data class CreateInviteLinkResponse(val token: String, val url: String, val info: InviteLinkDto)

@Serializable
data class InviteLinkOrganizationDto(val id: String, val name: String, val slug: String)

@Serializable
data class InviteLinkPreviewResponse(val valid: Boolean, val organization: InviteLinkOrganizationDto? = null)

@Serializable
data class InviteMemberRequest(val email: String, val role: String = "ORG_USER")

@Serializable
data class ChangeMemberRoleRequest(val role: String)

// ──────────────────────────────────────────────
// Admin panel — global, cross-organization
// ──────────────────────────────────────────────
//
// These mirror `AdminRoutes.kt` and carry no organization context at all:
// there is nothing to scope them to, only the whole system. Every one of them
// is refused with SUPER_ADMIN_REQUIRED for an ordinary account, which is what
// actually enforces the rule — the screen that draws them only decides whether
// to bother asking.

/** One account, as listed in the admin panel's global user table. */
@Serializable
data class AdminUserDto(
    val id: String,
    val email: String,
    val fullName: String,
    val globalRole: String = "USER",
    val emailVerified: Boolean = true,
    /** Null when the account is in good standing. */
    val suspendedAt: String? = null,
    val organizationCount: Int = 0,
    val createdAt: String
) {
    val isSuperAdmin: Boolean get() = globalRole == "SUPER_ADMIN"
    val isSuspended: Boolean get() = suspendedAt != null
}

/** One organization, system-wide — not "one the caller belongs to". */
@Serializable
data class AdminOrganizationDto(
    val id: String,
    val name: String,
    val slug: String,
    val createdByEmail: String? = null,
    val memberCount: Int = 0,
    val archivedAt: String? = null,
    val createdAt: String
) {
    val isArchived: Boolean get() = archivedAt != null
}

@Serializable
data class ArchiveOrganizationRequest(val confirmationSlug: String)

@Serializable
data class UpdateUserAdminRequest(val suspended: Boolean)

/**
 * An organization-creation link's metadata.
 *
 * Never carries the raw token: the server stores only its hash, so a link is
 * recoverable exactly once, in the response to issuing it — see
 * [CreateOrganizationCreationTokenResponse].
 */
@Serializable
data class OrganizationCreationTokenDto(
    val id: String,
    val label: String? = null,
    val createdByEmail: String? = null,
    val maxUses: Int,
    val usesCount: Int,
    val expiresAt: String,
    val revokedAt: String? = null,
    val createdAt: String
) {
    val isRevoked: Boolean get() = revokedAt != null
    val isExhausted: Boolean get() = usesCount >= maxUses
}

/**
 * Both bounds are mandatory, matching the server: a link with no cap and no
 * expiry is a standing backdoor, not a convenience.
 */
@Serializable
data class CreateOrganizationCreationTokenRequest(
    val label: String? = null,
    val maxUses: Int,
    val expiresInHours: Long
)

/** The one-time response to issuing a link — the only place the raw token appears. */
@Serializable
data class CreateOrganizationCreationTokenResponse(
    val token: String,
    val url: String,
    val info: OrganizationCreationTokenDto
)

@Serializable
data class MemberDto(
    val userId: String,
    val email: String,
    val fullName: String,
    val role: String,
    val status: String,
    val joinedAt: String
)

/** One entry of an organization's membership history, newest first. */
@Serializable
data class AuditEventDto(
    val id: String,
    val action: String,
    val actorUserId: String,
    val actorName: String? = null,
    val targetUserId: String? = null,
    val targetName: String? = null,
    val detail: String? = null,
    val createdAt: String
)

/** The backend's error code for a write refused pending email confirmation. */
const val EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED"

/**
 * Whether this failure is the backend refusing a write because the account's
 * address is unconfirmed.
 *
 * Matched on the error *code* in the body rather than on the 403 alone: the
 * same status also means `NOT_A_MEMBER` and `ADMIN_REQUIRED`, and those are
 * genuinely different situations — one is fixed by clicking a link in an
 * email, the others are not fixable by the user at all.
 *
 * Returns false for anything it cannot read. Guessing "probably verification"
 * from an unparseable body would suppress retries for failures that deserve
 * them, which is a worse error than showing the wrong message once.
 */
suspend fun Throwable.isEmailNotVerified(): Boolean {
    val response = (this as? ResponseException)?.response ?: return false
    if (response.status != HttpStatusCode.Forbidden) return false
    return runCatching { response.bodyAsText().contains(EMAIL_NOT_VERIFIED) }.getOrDefault(false)
}

/**
 * Whether the backend refused a request because this account is not an active
 * member of the organization it named — removed, revoked, or the organization
 * archived since the app last looked. Matched on the code, like
 * [isEmailNotVerified]: a 403 also means `ADMIN_REQUIRED` or
 * `EMAIL_NOT_VERIFIED`, which must not wipe anything.
 */
suspend fun Throwable.isNotAMember(): Boolean {
    val response = (this as? ResponseException)?.response ?: return false
    if (response.status != HttpStatusCode.Forbidden) return false
    return runCatching { response.bodyAsText().contains("\"NOT_A_MEMBER\"") }.getOrDefault(false)
}

// ──────────────────────────────────────────────
// Interface
// ──────────────────────────────────────────────

interface BeautyApi {
    suspend fun login(request: AuthRequest): AuthResponse

    /**
     * Public endpoint — must be called through [com.beauty.app.AppContainer.buildLoginClient],
     * which has no bearer-token plugin installed. There is no token to send yet.
     */
    suspend fun register(request: RegisterRequest): AuthResponse

    /**
     * Revokes the refresh token server-side. Clearing local storage alone
     * leaves the token valid for its full lifetime, so a logout on a device
     * that was handed to someone else would not actually end the session.
     */
    suspend fun logout(request: RefreshRequest)

    /**
     * Asks the backend to mail a password-reset link.
     *
     * Public, and must go through the same token-less client as [register] —
     * the caller has no session, which is the entire premise.
     *
     * Returns [Unit] rather than a result because the endpoint deliberately
     * answers 200 with the same body whether or not the address has an account.
     * Anything a caller could branch on here would be an account-enumeration
     * oracle, so there is nothing to return.
     */
    suspend fun forgotPassword(request: ForgotPasswordRequest)

    /**
     * Sets a new password with a reset-link token. Public, like [forgotPassword].
     *
     * Issues no session and revokes every existing one: the user signs in
     * again with the new password afterwards.
     */
    suspend fun resetPassword(request: ResetPasswordRequest)

    suspend fun verifyEmail(token: String)

    // -- Organization-scoped data ----------------------------------------
    //
    // `orgId` is a parameter on every one of these, not an ambient setting.
    // These endpoints return 400 without it and 403 for an organization the
    // caller does not actively belong to, so making it explicit means a new
    // call site cannot forget it and get a runtime error instead of a
    // compile-time one.

    suspend fun getClients(orgId: String): List<ClientDto>
    suspend fun searchClients(orgId: String, query: String, tag: String?): List<ClientDto>
    suspend fun createClient(orgId: String, request: UpdateClientRequest): ClientDto
    suspend fun deleteClient(orgId: String, id: String)
    suspend fun uploadAttachment(orgId: String, visitId: String, tag: String, bytes: ByteArray, caption: String? = null): VisitAttachmentDto
    suspend fun downloadAttachment(orgId: String, id: String): ByteArray
    suspend fun updateClient(orgId: String, id: String, request: UpdateClientRequest): ClientDto
    suspend fun createVisit(orgId: String, request: CreateVisitRequest): VisitDto
    suspend fun getVisitsForClient(orgId: String, clientId: String): List<VisitHistoryDto>

    // -- Organizations and membership ------------------------------------

    /** Everything the user belongs to or has asked to belong to. Needs no organization context. */
    suspend fun getOrganizations(): List<OrganizationDto>

    /** Creates one; the caller becomes its first administrator. */
    suspend fun createOrganization(request: CreateOrganizationRequest): OrganizationDto

    /** Checks a creation-link token without spending a use. */
    suspend fun validateCreationToken(token: String): Boolean

    /** Asks to join by handle, or accepts a standing invitation. */
    suspend fun requestToJoinOrganization(request: JoinOrganizationRequest): OrganizationDto

    /** Which organization an invite link leads to. Advisory; [acceptInviteLink] re-checks. */
    suspend fun previewInviteLink(token: String): InviteLinkPreviewResponse

    /** Joins through an admin's single-use link; the membership comes back `ACTIVE`. */
    suspend fun acceptInviteLink(request: AcceptInviteLinkRequest): OrganizationDto

    /** Unused invite links. Administrators only. */
    suspend fun getInviteLinks(orgId: String): List<InviteLinkDto>
    suspend fun createInviteLink(orgId: String): CreateInviteLinkResponse
    suspend fun revokeInviteLink(orgId: String, linkId: String)

    /** The roster, including pending requests. Administrators only. */
    suspend fun getMembers(orgId: String): List<MemberDto>

    suspend fun approveMember(orgId: String, userId: String)

    /** Turns down a pending request; the requester may ask again only after a cooldown. */
    suspend fun declineMember(orgId: String, userId: String)

    /** Blocks an active member without deleting them; they cannot re-request until restored. */
    suspend fun revokeMember(orgId: String, userId: String)
    suspend fun restoreMember(orgId: String, userId: String)

    /** Membership history, newest first. Administrators only. */
    suspend fun getOrganizationAudit(orgId: String, before: String? = null): List<AuditEventDto>

    suspend fun inviteMember(orgId: String, request: InviteMemberRequest)
    suspend fun changeMemberRole(orgId: String, userId: String, request: ChangeMemberRoleRequest)

    /**
     * Removes a member. Their access ends on their next request — the backend
     * re-reads membership every time — so there is no token to invalidate here.
     */
    suspend fun removeMember(orgId: String, userId: String)

    /**
     * Asks the backend to mail a fresh verification link to the signed-in
     * user's own address.
     *
     * Takes no parameters: the address comes from the access token. An
     * "resend to this address" endpoint would let any caller make the server
     * mail arbitrary strangers, so there is nothing to pass and nothing to
     * return — the endpoint answers 204 whether or not it sent anything.
     */
    suspend fun resendVerificationEmail()

    /** The signed-in user's own profile. The JWT carries id and email only, not the display name. */
    suspend fun getCurrentUser(): UserDto
    suspend fun updateProfile(request: UpdateProfileRequest): UserDto
    suspend fun updateLanguagePreference(request: LanguagePreferenceRequest): LanguagePreferenceResponse

    /** Returns a brand-new session: the backend revokes every other session on a successful change. */
    suspend fun changePassword(request: ChangePasswordRequest): AuthResponse

    // -- Admin panel (SUPER_ADMIN only) ----------------------------------
    //
    // No `orgId` on any of these, unlike the scoped calls above: they have no
    // organization to be about. The server answers 403 SUPER_ADMIN_REQUIRED
    // for everyone else.

    suspend fun getAdminUsers(): List<AdminUserDto>

    /**
     * Suspends or lifts a suspension.
     *
     * Suspending also revokes every refresh-token family server-side, so there
     * is nothing further for a client to do about the target's existing
     * session — it stops working as soon as its short-lived access token
     * lapses, and cannot be renewed.
     */
    suspend fun setUserSuspended(userId: String, request: UpdateUserAdminRequest)

    suspend fun getAdminOrganizations(): List<AdminOrganizationDto>

    suspend fun archiveOrganization(id: String, request: ArchiveOrganizationRequest)

    suspend fun getCreationTokens(): List<OrganizationCreationTokenDto>

    /** Issues a link. The raw token in the response can never be read again. */
    suspend fun createCreationToken(
        request: CreateOrganizationCreationTokenRequest
    ): CreateOrganizationCreationTokenResponse

    /** Kills a link before its natural expiry. */
    suspend fun revokeCreationToken(id: String)
}

// ──────────────────────────────────────────────
// Ktor implementation
// ──────────────────────────────────────────────

class KtorBeautyApi(private val client: HttpClient) : BeautyApi {

    override suspend fun login(request: AuthRequest): AuthResponse =
        client.post("api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun register(request: RegisterRequest): AuthResponse =
        client.post("api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun logout(request: RefreshRequest) {
        client.post("api/auth/logout") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun forgotPassword(request: ForgotPasswordRequest) {
        client.post("api/auth/forgot-password") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun verifyEmail(token: String) {
        client.post("api/auth/verify-email") {
            contentType(ContentType.Application.Json)
            setBody(VerifyEmailRequest(token))
        }
    }

    override suspend fun resetPassword(request: ResetPasswordRequest) {
        client.post("api/auth/reset-password") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun getClients(orgId: String): List<ClientDto> = searchClients(orgId, "", null)

    override suspend fun searchClients(orgId: String, query: String, tag: String?): List<ClientDto> {
        val clients = mutableListOf<ClientDto>()
        var offset = 0L
        do {
            val page: List<ClientDto> = client.get("api/clients") {
                header(ORG_HEADER, orgId)
                parameter("limit", 100)
                parameter("offset", offset)
                if (query.isNotBlank()) parameter("q", query.trim())
                if (!tag.isNullOrBlank()) parameter("tag", tag.trim())
            }.body()
            clients += page
            offset += page.size
        } while (page.size == 100)
        return clients.distinctBy { it.id }
    }

    override suspend fun createClient(orgId: String, request: UpdateClientRequest): ClientDto =
        client.post("api/clients") {
            header(ORG_HEADER, orgId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun deleteClient(orgId: String, id: String) {
        client.delete("api/clients/$id") { header(ORG_HEADER, orgId) }
    }

    override suspend fun uploadAttachment(orgId: String, visitId: String, tag: String, bytes: ByteArray, caption: String?): VisitAttachmentDto =
        client.post("api/attachments/upload") {
            header(ORG_HEADER, orgId)
            setBody(MultiPartFormDataContent(formData {
                append("visitId", visitId)
                append("tag", tag)
                caption?.let { append("caption", it) }
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=photo.jpg")
                })
            }))
        }.body()

    override suspend fun downloadAttachment(orgId: String, id: String): ByteArray =
        client.get("api/attachments/$id/file") { header(ORG_HEADER, orgId) }.body()

    override suspend fun updateClient(orgId: String, id: String, request: UpdateClientRequest): ClientDto =
        client.put("api/clients/$id") {
            header(ORG_HEADER, orgId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun createVisit(orgId: String, request: CreateVisitRequest): VisitDto {
        val response = client.post("api/visits") {
            header(ORG_HEADER, orgId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        check(response.status == HttpStatusCode.Created) { "Visit was not confirmed by the server." }
        return response.body()
    }

    override suspend fun getVisitsForClient(orgId: String, clientId: String): List<VisitHistoryDto> {
        // `pageLimit()` on the backend caps pages at 100. Continue until its
        // short final page so a long treatment history is never silently cut
        // off by the default server page size (50).
        val pageSize = 100
        var offset = 0L
        val visits = mutableListOf<VisitHistoryDto>()

        do {
            val page: List<VisitHistoryDto> = client.get("api/visits") {
                header(ORG_HEADER, orgId)
                parameter("clientId", clientId)
                parameter("limit", pageSize)
                parameter("offset", offset)
            }.body()
            visits += page
            offset += page.size
        } while (page.size == pageSize)

        return visits
    }

    override suspend fun getOrganizations(): List<OrganizationDto> =
        client.get("api/organizations").body()

    override suspend fun createOrganization(request: CreateOrganizationRequest): OrganizationDto =
        client.post("api/organizations") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun validateCreationToken(token: String): Boolean =
        client.get("api/organizations/creation-tokens/validate") {
            parameter("token", token)
        }.body<ValidateCreationTokenResponse>().valid

    override suspend fun requestToJoinOrganization(request: JoinOrganizationRequest): OrganizationDto =
        client.post("api/organizations/join-requests") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun previewInviteLink(token: String): InviteLinkPreviewResponse =
        client.get("api/organizations/invite-links/preview") { parameter("token", token) }.body()

    override suspend fun acceptInviteLink(request: AcceptInviteLinkRequest): OrganizationDto =
        client.post("api/organizations/invite-links/accept") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun getInviteLinks(orgId: String): List<InviteLinkDto> =
        client.get("api/organizations/$orgId/invite-links") { header(ORG_HEADER, orgId) }.body()

    override suspend fun createInviteLink(orgId: String): CreateInviteLinkResponse =
        client.post("api/organizations/$orgId/invite-links") { header(ORG_HEADER, orgId) }.body()

    override suspend fun revokeInviteLink(orgId: String, linkId: String) {
        client.post("api/organizations/$orgId/invite-links/$linkId/revoke") { header(ORG_HEADER, orgId) }
    }

    override suspend fun getMembers(orgId: String): List<MemberDto> =
        client.get("api/organizations/$orgId/members") { header(ORG_HEADER, orgId) }.body()

    override suspend fun approveMember(orgId: String, userId: String) {
        client.post("api/organizations/$orgId/members/$userId/approval") {
            header(ORG_HEADER, orgId)
        }
    }

    override suspend fun declineMember(orgId: String, userId: String) {
        client.post("api/organizations/$orgId/members/$userId/decline") {
            header(ORG_HEADER, orgId)
        }
    }

    override suspend fun revokeMember(orgId: String, userId: String) {
        client.post("api/organizations/$orgId/members/$userId/revoke") { header(ORG_HEADER, orgId) }
    }

    override suspend fun restoreMember(orgId: String, userId: String) {
        client.post("api/organizations/$orgId/members/$userId/restore") { header(ORG_HEADER, orgId) }
    }

    override suspend fun getOrganizationAudit(orgId: String, before: String?): List<AuditEventDto> =
        client.get("api/organizations/$orgId/audit") {
            header(ORG_HEADER, orgId)
            if (before != null) parameter("before", before)
        }.body()

    override suspend fun inviteMember(orgId: String, request: InviteMemberRequest) {
        client.post("api/organizations/$orgId/members/invitations") {
            header(ORG_HEADER, orgId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun changeMemberRole(orgId: String, userId: String, request: ChangeMemberRoleRequest) {
        client.patch("api/organizations/$orgId/members/$userId") {
            header(ORG_HEADER, orgId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun removeMember(orgId: String, userId: String) {
        client.delete("api/organizations/$orgId/members/$userId") {
            header(ORG_HEADER, orgId)
        }
    }

    override suspend fun resendVerificationEmail() {
        client.post("api/auth/resend-verification")
    }

    override suspend fun getCurrentUser(): UserDto =
        client.get("api/users/me").body()

    override suspend fun updateProfile(request: UpdateProfileRequest): UserDto =
        client.patch("api/users/me") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun updateLanguagePreference(request: LanguagePreferenceRequest): LanguagePreferenceResponse =
        client.put("api/users/me/language") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun changePassword(request: ChangePasswordRequest): AuthResponse =
        client.post("api/users/me/password") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun getAdminUsers(): List<AdminUserDto> =
        client.get("api/admin/users").body()

    override suspend fun setUserSuspended(userId: String, request: UpdateUserAdminRequest) {
        client.patch("api/admin/users/$userId") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun getAdminOrganizations(): List<AdminOrganizationDto> =
        client.get("api/admin/organizations").body()

    override suspend fun archiveOrganization(id: String, request: ArchiveOrganizationRequest) {
        client.post("api/admin/organizations/$id/archive") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun getCreationTokens(): List<OrganizationCreationTokenDto> =
        client.get("api/admin/organization-creation-tokens").body()

    override suspend fun createCreationToken(
        request: CreateOrganizationCreationTokenRequest
    ): CreateOrganizationCreationTokenResponse =
        client.post("api/admin/organization-creation-tokens") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun revokeCreationToken(id: String) {
        client.delete("api/admin/organization-creation-tokens/$id")
    }
}

/** User-facing messages never contain URLs, server bodies, tokens, or database details. */
suspend fun Throwable.safeMessage(fallback: String = "NETWORK_ERROR"): String {
    if (this is kotlinx.coroutines.CancellationException) throw this
    if (this is PendingVisitsException) return "PENDING_UPLOADS"
    if (this is SessionChangedException) return "SESSION_CHANGED"
    if (this is PhotoDraftException) return "PHOTO_UNAVAILABLE"
    if (this is ResponseException) {
        val code = runCatching { response.body<ApiErrorResponse>().code }.getOrNull()
        if (!code.isNullOrBlank()) return code
        return when (response.status.value) {
            401 -> "SESSION_EXPIRED"
            403 -> "ACCESS_DENIED"
            404 -> "RECORD_UNAVAILABLE"
            409 -> "RECORD_CONFLICT"
            413 -> "PHOTO_TOO_LARGE"
            429 -> "TOO_MANY_ATTEMPTS"
            400, 422 -> "VALIDATION_FAILED"
            else -> fallback
        }
    }
    return fallback
}

class PendingVisitsException(message: String = "Upload pending visits before deleting this client.") : IllegalStateException(message)
class SessionChangedException : IllegalStateException("Session changed")
class PhotoDraftException(message: String) : IllegalStateException(message)
