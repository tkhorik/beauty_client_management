package com.beauty.app.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
    val createdAt: String
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

@Serializable
data class UpdateClientRequest(
    val name: String,
    val phone: String,
    val email: String? = null,
    val tags: List<String>,
    val customFields: JsonObject
)

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
    suspend fun getClients(): List<ClientDto>
    suspend fun updateClient(id: String, request: UpdateClientRequest): ClientDto
    suspend fun createVisit(request: CreateVisitRequest): VisitDto
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

    override suspend fun getClients(): List<ClientDto> =
        client.get("api/clients").body()

    override suspend fun updateClient(id: String, request: UpdateClientRequest): ClientDto =
        client.put("api/clients/$id") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun createVisit(request: CreateVisitRequest): VisitDto =
        client.post("api/visits") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
}
