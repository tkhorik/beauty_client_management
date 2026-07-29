package com.beauty.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val fullName: String,
    val createdAt: String,
    /**
     * Whether the user has confirmed control of [email].
     *
     * Currently advisory: enforcement is "soft", so an unverified user can still
     * use the app and the clients merely show a banner. It is exposed here
     * rather than behind a separate endpoint so the clients learn about it from
     * the same response that establishes the session, with no extra round trip.
     *
     * Defaults to false so that any code path that forgets to populate it fails
     * closed — showing a nag to a verified user is a cosmetic bug, whereas
     * silently reporting an unverified account as verified defeats the feature.
     */
    val emailVerified: Boolean = false
)

@Serializable
data class AuthRequest(
    val email: String,
    val password: String
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val fullName: String
)

@Serializable
data class AuthResponse(
    /** Short-lived access token. Send as `Authorization: Bearer <token>`. */
    val token: String,
    /**
     * Long-lived refresh token, used to obtain a new access token.
     *
     * Null for browser clients: their refresh token is delivered in an
     * httpOnly cookie instead, so that JavaScript — and therefore any XSS on
     * the page — cannot read it. Native clients get it here and store it in
     * platform-encrypted storage.
     */
    val refreshToken: String? = null,
    /** Access-token lifetime in seconds, so clients can refresh before it lapses. */
    val expiresInSeconds: Long,
    val user: UserDto
)

/** Body for `POST /api/auth/forgot-password`. */
@Serializable
data class ForgotPasswordRequest(
    val email: String
)

/** Body for `POST /api/auth/reset-password`. */
@Serializable
data class ResetPasswordRequest(
    val token: String,
    val newPassword: String
)

/**
 * A deliberately uninformative acknowledgement.
 *
 * `/forgot-password` answers with this identical body whether or not the
 * address has an account. Confirming existence would turn the endpoint into a
 * free account-enumeration oracle — and one that requires no credentials at
 * all, unlike the login endpoint, which already goes to some trouble (see
 * `DUMMY_PASSWORD_HASH`) to avoid being one.
 */
@Serializable
data class MessageResponse(
    val message: String
)

/** Body for `/api/auth/refresh` and `/api/auth/logout` from non-browser clients. */
@Serializable
data class RefreshRequest(
    val refreshToken: String? = null
)

/** Body for `PATCH /api/users/me`. Only the display name is editable here — email is the login identifier and changing it is a separate, verification-gated flow this app does not yet have. */
@Serializable
data class UpdateProfileRequest(
    val fullName: String
)

/**
 * Body for `POST /api/users/me/password`.
 *
 * Requires the current password even though the caller already holds a valid
 * access token: a valid token only proves the session was authenticated
 * recently, not that whoever is driving it right now knows the password. A
 * hijacked-but-unexpired token (XSS, a shared machine left unlocked) must not
 * be enough on its own to lock the real owner out of their own account.
 */
@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

/**
 * A 400 response body for rejected input, keyed by field name so the client
 * can render each message next to the input that caused it. A single flat
 * `error` string forces the user to guess which field was wrong.
 */
@Serializable
data class ValidationErrorResponse(
    val error: String = "Validation failed",
    val errors: Map<String, String>
)

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
data class CreateClientRequest(
    val name: String,
    val phone: String,
    val email: String? = null,
    val tags: List<String> = emptyList(),
    val customFields: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class UpdateClientRequest(
    val name: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val tags: List<String>? = null,
    val customFields: JsonObject? = null
)

@Serializable
data class VisitDto(
    val id: String,
    val clientId: String,
    val visitDateTime: String,
    val durationMinutes: Int,
    val procedureNotes: String,
    val status: String, // COMPLETED, SCHEDULED, CANCELLED
    val attachments: List<AttachmentDto> = emptyList(),
    val createdAt: String
)

@Serializable
data class CreateVisitRequest(
    val clientId: String,
    val visitDateTime: String,
    val durationMinutes: Int,
    val procedureNotes: String,
    val status: String = "COMPLETED"
)

@Serializable
data class UpdateVisitRequest(
    val visitDateTime: String? = null,
    val durationMinutes: Int? = null,
    val procedureNotes: String? = null,
    val status: String? = null
)

@Serializable
data class AttachmentDto(
    val id: String,
    val visitId: String,
    val fileUrl: String,
    val fileType: String,
    val fileSize: Long,
    val caption: String? = null,
    val tag: String = "PROCEDURE", // BEFORE, AFTER, PROCEDURE, DOCUMENT
    val uploadedAt: String
)

@Serializable
data class SearchQuery(
    val q: String? = null,
    val tag: String? = null
)
