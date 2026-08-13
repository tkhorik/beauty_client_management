package com.beauty.app.data

import com.beauty.app.data.api.AuthResponse
import com.beauty.app.data.api.BeautyApi
import com.beauty.app.data.api.ChangeMemberRoleRequest
import com.beauty.app.data.api.ChangePasswordRequest
import com.beauty.app.data.api.ClientDto
import com.beauty.app.data.api.CreateOrganizationRequest
import com.beauty.app.data.api.CreateVisitRequest
import com.beauty.app.data.api.InviteMemberRequest
import com.beauty.app.data.api.JoinOrganizationRequest
import com.beauty.app.data.api.MemberDto
import com.beauty.app.data.api.OrganizationDto
import com.beauty.app.data.api.UpdateClientRequest
import com.beauty.app.data.api.UpdateProfileRequest
import com.beauty.app.data.api.UserDto
import com.beauty.app.data.api.isEmailNotVerified
import com.beauty.app.data.local.ClientDao
import com.beauty.app.data.local.ClientEntity
import com.beauty.app.data.local.VisitDao
import com.beauty.app.data.local.VisitEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * What happened to the offline queue on one sync attempt.
 *
 * Three outcomes rather than a boolean, because "did not upload" hides two
 * situations that need opposite handling. A network failure should be retried
 * with backoff; a refusal for want of a confirmed email address will keep being
 * refused until the user clicks a link in their inbox, and retrying it on a
 * WorkManager backoff schedule burns battery for hours to achieve nothing.
 */
enum class VisitSyncOutcome {
    /** Everything queued was accepted. */
    SUCCESS,

    /** At least one upload failed for a reason that may resolve on its own. */
    RETRY,

    /**
     * The backend refused because the account's address is unconfirmed.
     *
     * The visits stay queued and unsynced — they are not dropped and not
     * marked uploaded. They go up on the next sync after the user verifies.
     */
    BLOCKED_UNVERIFIED
}

interface VisitSyncRepository {
    suspend fun syncPendingVisits(): VisitSyncOutcome
}

class BeautyRepository(
    private val api: BeautyApi,
    private val clientDao: ClientDao,
    private val visitDao: VisitDao,
    private val json: Json = Json
) : VisitSyncRepository {
    suspend fun refreshClients(orgId: String): Result<Unit> = runCatching {
        // The API list is the source of truth for downloaded data.  Reconciling
        // it in one Room transaction means a manual refresh also reflects
        // records deleted from the web app, rather than only adding/updating.
        //
        // Both sides of the reconcile are scoped to `orgId`: the snapshot only
        // describes one organization, so rows outside it are not "deleted on
        // the server", they are simply not part of this answer.
        clientDao.reconcileClients(orgId, api.getClients(orgId).map { it.toEntity(orgId, json) })
    }

    /** Update a client on the backend and return the updated ClientDto. */
    suspend fun updateClient(
        orgId: String,
        id: String,
        name: String,
        phone: String,
        email: String?,
        tags: List<String>,
        customFields: JsonObject
    ): ClientDto = api.updateClient(orgId, id, UpdateClientRequest(name, phone, email, tags, customFields))

    // -- Organizations ---------------------------------------------------

    suspend fun getOrganizations(): List<OrganizationDto> = api.getOrganizations()

    suspend fun createOrganization(name: String, slug: String?): OrganizationDto =
        api.createOrganization(CreateOrganizationRequest(name, slug?.takeIf { it.isNotBlank() }))

    suspend fun requestToJoinOrganization(slug: String): OrganizationDto =
        api.requestToJoinOrganization(JoinOrganizationRequest(slug))

    suspend fun getMembers(orgId: String): List<MemberDto> = api.getMembers(orgId)

    suspend fun approveMember(orgId: String, userId: String) = api.approveMember(orgId, userId)

    suspend fun inviteMember(orgId: String, email: String, role: String) =
        api.inviteMember(orgId, InviteMemberRequest(email, role))

    suspend fun changeMemberRole(orgId: String, userId: String, role: String) =
        api.changeMemberRole(orgId, userId, ChangeMemberRoleRequest(role))

    suspend fun removeMember(orgId: String, userId: String) = api.removeMember(orgId, userId)

    /** Write (upsert) a ClientEntity into the local Room cache. */
    suspend fun upsertClientLocally(entity: ClientEntity) = clientDao.insertClient(entity)

    suspend fun enqueueVisit(
        orgId: String,
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
                // Captured now, not read at upload time: this visit may sit in
                // the queue while the user switches to a different salon.
                organizationId = orgId,
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

    /**
     * Requests a fresh verification link.
     *
     * Wrapped in [Result] rather than throwing: the caller is a banner, and a
     * failure to send is worth a line of text, never a crash on a screen the
     * user opened to do something else.
     */
    suspend fun resendVerificationEmail(): Result<Unit> = runCatching { api.resendVerificationEmail() }

    suspend fun updateProfile(fullName: String): UserDto =
        api.updateProfile(UpdateProfileRequest(fullName))

    /** Returns a brand-new session — the caller must persist it, replacing whatever it's holding. */
    suspend fun changePassword(currentPassword: String, newPassword: String): AuthResponse =
        api.changePassword(ChangePasswordRequest(currentPassword, newPassword))

    override suspend fun syncPendingVisits(): VisitSyncOutcome {
        var allSucceeded = true
        var blocked = false

        visitDao.getUnsyncedVisits().forEach { visit ->
            // Once the address is known to be unconfirmed, stop trying. Every
            // remaining visit would be refused for the same reason, and each
            // attempt is a round trip that also spends the caller's rate-limit
            // budget on a certain failure.
            if (blocked) return@forEach

            try {
                // The organization comes from the queued row, not from whatever
                // is currently selected. Uploading a treatment record to the
                // wrong salon would be silent, permanent, and a privacy breach.
                val created = api.createVisit(visit.organizationId, visit.toRequest())
                if (created.id.isBlank()) error("Backend returned a visit without an ID")
                visitDao.markVisitSynced(visit.id, created.id)
            } catch (error: Exception) {
                allSucceeded = false
                if (error.isEmailNotVerified()) {
                    blocked = true
                    // Recorded against the row so the visit list can explain
                    // itself. The row stays unsynced, which is what keeps the
                    // record safe: it is still on the device and will upload
                    // once the address is confirmed.
                    visitDao.markVisitSyncFailed(
                        visit.id,
                        "Waiting for email confirmation before this visit can be uploaded."
                    )
                } else {
                    visitDao.markVisitSyncFailed(visit.id, error.message ?: "Visit upload failed")
                }
            }
        }

        return when {
            blocked -> VisitSyncOutcome.BLOCKED_UNVERIFIED
            allSucceeded -> VisitSyncOutcome.SUCCESS
            else -> VisitSyncOutcome.RETRY
        }
    }
}

private fun ClientDto.toEntity(organizationId: String, json: Json) = ClientEntity(
    id = id,
    organizationId = organizationId,
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
