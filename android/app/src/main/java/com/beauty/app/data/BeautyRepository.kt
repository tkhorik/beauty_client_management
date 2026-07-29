package com.beauty.app.data

import com.beauty.app.data.api.AuthResponse
import com.beauty.app.data.api.BeautyApi
import com.beauty.app.data.api.ChangePasswordRequest
import com.beauty.app.data.api.ClientDto
import com.beauty.app.data.api.CreateVisitRequest
import com.beauty.app.data.api.UpdateClientRequest
import com.beauty.app.data.api.UpdateProfileRequest
import com.beauty.app.data.api.UserDto
import com.beauty.app.data.local.ClientDao
import com.beauty.app.data.local.ClientEntity
import com.beauty.app.data.local.VisitDao
import com.beauty.app.data.local.VisitEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

interface VisitSyncRepository {
    suspend fun syncPendingVisits(): Boolean
}

class BeautyRepository(
    private val api: BeautyApi,
    private val clientDao: ClientDao,
    private val visitDao: VisitDao,
    private val json: Json = Json
) : VisitSyncRepository {
    suspend fun refreshClients(): Result<Unit> = runCatching {
        // The API list is the source of truth for downloaded data.  Reconciling
        // it in one Room transaction means a manual refresh also reflects
        // records deleted from the web app, rather than only adding/updating.
        clientDao.reconcileClients(api.getClients().map { it.toEntity(json) })
    }

    /** Update a client on the backend and return the updated ClientDto. */
    suspend fun updateClient(
        id: String,
        name: String,
        phone: String,
        email: String?,
        tags: List<String>,
        customFields: JsonObject
    ): ClientDto = api.updateClient(id, UpdateClientRequest(name, phone, email, tags, customFields))

    /** Write (upsert) a ClientEntity into the local Room cache. */
    suspend fun upsertClientLocally(entity: ClientEntity) = clientDao.insertClient(entity)

    suspend fun enqueueVisit(
        clientId: String,
        visitDateTime: String,
        durationMinutes: Int,
        procedureNotes: String,
        status: String = "COMPLETED"
    ): String {
        val localId = UUID.randomUUID().toString()
        visitDao.insertVisit(
            VisitEntity(
                id = localId,
                clientId = clientId,
                visitDateTime = visitDateTime,
                durationMinutes = durationMinutes,
                procedureNotes = procedureNotes,
                status = status,
                isPendingSync = true
            )
        )
        return localId
    }

    /** The signed-in user's own profile, for populating the Settings screen. */
    suspend fun getCurrentUser(): UserDto = api.getCurrentUser()

    suspend fun updateProfile(fullName: String): UserDto =
        api.updateProfile(UpdateProfileRequest(fullName))

    /** Returns a brand-new session — the caller must persist it, replacing whatever it's holding. */
    suspend fun changePassword(currentPassword: String, newPassword: String): AuthResponse =
        api.changePassword(ChangePasswordRequest(currentPassword, newPassword))

    override suspend fun syncPendingVisits(): Boolean {
        var allSucceeded = true
        visitDao.getUnsyncedVisits().forEach { visit ->
            try {
                val created = api.createVisit(visit.toRequest())
                if (created.id.isBlank()) error("Backend returned a visit without an ID")
                visitDao.markVisitSynced(visit.id, created.id)
            } catch (error: Exception) {
                allSucceeded = false
                visitDao.markVisitSyncFailed(visit.id, error.message ?: "Visit upload failed")
            }
        }
        return allSucceeded
    }
}

private fun ClientDto.toEntity(json: Json) = ClientEntity(
    id = id,
    name = name,
    phone = phone,
    email = email,
    tagsJson = json.encodeToString(tags),
    customFieldsJson = customFields.toString(),
    totalVisits = totalVisits,
    isSynced = true,
    updatedAt = System.currentTimeMillis()
)

private fun VisitEntity.toRequest() = CreateVisitRequest(
    clientId = clientId,
    visitDateTime = visitDateTime,
    durationMinutes = durationMinutes,
    procedureNotes = procedureNotes,
    status = status
)
