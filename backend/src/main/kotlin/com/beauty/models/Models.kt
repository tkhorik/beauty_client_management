package com.beauty.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val fullName: String,
    val createdAt: String
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

/** Body for `/api/auth/refresh` and `/api/auth/logout` from non-browser clients. */
@Serializable
data class RefreshRequest(
    val refreshToken: String? = null
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
