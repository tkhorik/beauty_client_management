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
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
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

@Serializable
data class AuthRequest(val email: String, val password: String)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val fullName: String
)

/**
 * The backend's 400 body for rejected input: `field name -> message`, so the
 * error can be shown against the input that caused it.
 */
@Serializable
data class ValidationErrorResponse(
    val error: String = "Validation failed",
    val errors: Map<String, String> = emptyMap()
)

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
    val verificationDeadline: String? = null
)

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
 * There is no matching "reset" request here on purpose. The emailed link points
 * at the web app (`SITE_URL/reset-password?token=…`), which is where the new
 * password is actually typed — see `AccountMailer.sendPasswordReset`. Adding an
 * in-app token field would mean asking the user to copy a 43-character secret
 * out of their mail client, and a deep link cannot be added safely until the
 * host serves an `assetlinks.json` for Android App Links verification: without
 * it, any installed app can register the same URL pattern and intercept reset
 * links.
 */
@Serializable
data class ForgotPasswordRequest(val email: String)

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
 * `role` is `ORG_ADMIN` or `ORG_USER`; `status` is `ACTIVE`, `PENDING` or
 * `INVITED`, and only `ACTIVE` grants access to any client or visit data.
 */
@Serializable
data class OrganizationDto(
    val id: String,
    val name: String,
    val slug: String,
    val role: String,
    val status: String,
    val createdAt: String? = null
) {
    val isActive: Boolean get() = status == "ACTIVE"
    val isAdmin: Boolean get() = role == "ORG_ADMIN"
}

@Serializable
data class CreateOrganizationRequest(val name: String, val slug: String? = null)

@Serializable
data class JoinOrganizationRequest(val slug: String)

@Serializable
data class InviteMemberRequest(val email: String, val role: String = "ORG_USER")

@Serializable
data class ChangeMemberRoleRequest(val role: String)

@Serializable
data class MemberDto(
    val userId: String,
    val email: String,
    val fullName: String,
    val role: String,
    val status: String,
    val joinedAt: String
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

    // -- Organization-scoped data ----------------------------------------
    //
    // `orgId` is a parameter on every one of these, not an ambient setting.
    // These endpoints return 400 without it and 403 for an organization the
    // caller does not actively belong to, so making it explicit means a new
    // call site cannot forget it and get a runtime error instead of a
    // compile-time one.

    suspend fun getClients(orgId: String): List<ClientDto>
    suspend fun updateClient(orgId: String, id: String, request: UpdateClientRequest): ClientDto
    suspend fun createVisit(orgId: String, request: CreateVisitRequest): VisitDto

    // -- Organizations and membership ------------------------------------

    /** Everything the user belongs to or has asked to belong to. Needs no organization context. */
    suspend fun getOrganizations(): List<OrganizationDto>

    /** Creates one; the caller becomes its first administrator. */
    suspend fun createOrganization(request: CreateOrganizationRequest): OrganizationDto

    /** Asks to join by handle, or accepts a standing invitation. */
    suspend fun requestToJoinOrganization(request: JoinOrganizationRequest): OrganizationDto

    /** The roster, including pending requests. Administrators only. */
    suspend fun getMembers(orgId: String): List<MemberDto>

    suspend fun approveMember(orgId: String, userId: String)
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

    /** Returns a brand-new session: the backend revokes every other session on a successful change. */
    suspend fun changePassword(request: ChangePasswordRequest): AuthResponse
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

    override suspend fun getClients(orgId: String): List<ClientDto> =
        client.get("api/clients") { header(ORG_HEADER, orgId) }.body()

    override suspend fun updateClient(orgId: String, id: String, request: UpdateClientRequest): ClientDto =
        client.put("api/clients/$id") {
            header(ORG_HEADER, orgId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun createVisit(orgId: String, request: CreateVisitRequest): VisitDto =
        client.post("api/visits") {
            header(ORG_HEADER, orgId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun getOrganizations(): List<OrganizationDto> =
        client.get("api/organizations").body()

    override suspend fun createOrganization(request: CreateOrganizationRequest): OrganizationDto =
        client.post("api/organizations") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun requestToJoinOrganization(request: JoinOrganizationRequest): OrganizationDto =
        client.post("api/organizations/join-requests") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun getMembers(orgId: String): List<MemberDto> =
        client.get("api/organizations/$orgId/members") { header(ORG_HEADER, orgId) }.body()

    override suspend fun approveMember(orgId: String, userId: String) {
        client.post("api/organizations/$orgId/members/$userId/approval") {
            header(ORG_HEADER, orgId)
        }
    }

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

    override suspend fun changePassword(request: ChangePasswordRequest): AuthResponse =
        client.post("api/users/me/password") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
}
