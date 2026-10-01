package com.beauty.app.data

import com.beauty.app.data.api.AdminOrganizationDto
import com.beauty.app.data.api.AdminUserDto
import com.beauty.app.data.api.AuthResponse
import com.beauty.app.data.api.BeautyApi
import com.beauty.app.data.api.ChangeMemberRoleRequest
import com.beauty.app.data.api.ChangePasswordRequest
import com.beauty.app.data.api.ClientDto
import com.beauty.app.data.api.CreateOrganizationCreationTokenRequest
import com.beauty.app.data.api.CreateOrganizationCreationTokenResponse
import com.beauty.app.data.api.CreateOrganizationRequest
import com.beauty.app.data.api.CreateVisitRequest
import com.beauty.app.data.api.InviteMemberRequest
import com.beauty.app.data.api.JoinOrganizationRequest
import com.beauty.app.data.api.MemberDto
import com.beauty.app.data.api.OrganizationCreationTokenDto
import com.beauty.app.data.api.OrganizationDto
import com.beauty.app.data.api.UpdateClientRequest
import com.beauty.app.data.api.UpdateProfileRequest
import com.beauty.app.data.api.UpdateUserAdminRequest
import com.beauty.app.data.api.UserDto
import com.beauty.app.data.api.VisitHistoryDto
import com.beauty.app.data.api.isEmailNotVerified
import com.beauty.app.data.local.ClientDao
import com.beauty.app.data.local.ClientEntity
import com.beauty.app.data.local.VisitDao
import com.beauty.app.data.local.VisitEntity
import com.beauty.app.data.local.ParityDao
import com.beauty.app.data.local.PhotoDraftEntity
import com.beauty.app.data.local.HistorySnapshotEntity
import com.beauty.app.data.api.VisitAttachmentDto
import com.beauty.app.data.api.PendingVisitsException
import com.beauty.app.data.api.PhotoDraftException
import com.beauty.app.data.api.safeMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val json: Json = Json,
    private val parityDao: ParityDao? = null
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

    suspend fun searchClients(orgId: String, query: String, tag: String?): List<ClientDto> {
        val result = api.searchClients(orgId, query, tag)
        clientDao.insertClients(result.map { it.toEntity(orgId, json) })
        return result
    }

    suspend fun createClient(orgId: String, name: String, phone: String, email: String?, tags: List<String>, customFields: JsonObject): ClientDto =
        api.createClient(orgId, UpdateClientRequest(name, phone, email, tags, customFields))

    suspend fun deleteClient(orgId: String, id: String) {
        if (visitDao.countPendingVisits(orgId, id) > 0) throw PendingVisitsException()
        parityDao?.getPhotoDrafts(orgId, id)?.takeIf { it.isNotEmpty() }?.let { throw PendingVisitsException("Upload pending photos before deleting this client.") }
        api.deleteClient(orgId, id)
        clientDao.deleteClient(id)
        parityDao?.deleteHistory(orgId, id)
        parityDao?.deleteClientPhotoDrafts(orgId, id)
    }

    suspend fun getCachedVisitsForClient(orgId: String, clientId: String): List<VisitHistoryDto> =
        parityDao?.getHistory(orgId, clientId)?.let { runCatching { json.decodeFromString<List<VisitHistoryDto>>(it.historyJson) }.getOrDefault(emptyList()) } ?: emptyList()

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

    /** Read-through history: the backend remains the source of truth for visit records and attachments. */
    suspend fun getVisitsForClient(orgId: String, clientId: String): List<VisitHistoryDto> =
        api.getVisitsForClient(orgId, clientId).also { visits ->
            parityDao?.saveHistory(HistorySnapshotEntity(orgId, clientId, json.encodeToString(visits)))
        }

    suspend fun addPhotoDraft(orgId: String, clientId: String, localVisitId: String, localFilePath: String, tag: String): PhotoDraftEntity {
        val draft = PhotoDraftEntity(UUID.randomUUID().toString(), orgId, clientId, localVisitId, localFilePath, tag)
        requireNotNull(parityDao) { "Photo drafts require the current database" }.savePhotoDraft(draft)
        return draft
    }

    suspend fun getPhotoDrafts(orgId: String, clientId: String): List<PhotoDraftEntity> = parityDao?.getPhotoDrafts(orgId, clientId).orEmpty()

    suspend fun uploadPhotoDraft(draftId: String): VisitAttachmentDto {
        val dao = requireNotNull(parityDao) { "Photo drafts require the current database" }
        val draft = dao.getPhotoDraft(draftId) ?: throw PhotoDraftException("Photo draft is no longer available.")
        val visit = visitDao.getVisitById(draft.localVisitId) ?: throw PhotoDraftException("Visit is no longer available.")
        val remoteVisitId = visit.remoteId ?: throw PhotoDraftException("The visit must finish uploading before its photo can upload.")
        val bytes = java.io.File(draft.localFilePath).takeIf { it.exists() }?.readBytes() ?: throw PhotoDraftException("Photo file is no longer available.")
        val uploaded = api.uploadAttachment(draft.organizationId, remoteVisitId, draft.tag, bytes, defaultPhotoCaption(draft.tag))
        dao.deletePhotoDraft(draft.id)
        return uploaded
    }

    /** Uploads only drafts whose visit has already received a server id. */
    suspend fun syncPendingPhotos(): Boolean {
        val dao = parityDao ?: return true
        var allUploaded = true
        dao.getAllPendingPhotoDrafts().forEach { draft ->
            try { uploadPhotoDraft(draft.id) }
            catch (error: Exception) {
                allUploaded = false
                dao.savePhotoDraft(draft.copy(syncError = error.safeMessage("Photo upload is pending.")))
            }
        }
        return allUploaded
    }

    suspend fun downloadAttachment(orgId: String, id: String): ByteArray = api.downloadAttachment(orgId, id)

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

    // -- Admin panel (SUPER_ADMIN only) --------------------------------------
    //
    // Pass-throughs with no local caching, deliberately: this data is a
    // system-wide snapshot an operator acts on immediately, and a stale copy in
    // Room would be worse than a spinner — it would show an account as active
    // seconds after it was suspended. Nothing here touches the offline path.

    suspend fun getAdminUsers(): List<AdminUserDto> = api.getAdminUsers()

    suspend fun setUserSuspended(userId: String, suspended: Boolean) =
        api.setUserSuspended(userId, UpdateUserAdminRequest(suspended))

    suspend fun getAdminOrganizations(): List<AdminOrganizationDto> = api.getAdminOrganizations()

    suspend fun getCreationTokens(): List<OrganizationCreationTokenDto> = api.getCreationTokens()

    suspend fun createCreationToken(
        label: String?,
        maxUses: Int,
        expiresInHours: Long
    ): CreateOrganizationCreationTokenResponse =
        api.createCreationToken(
            CreateOrganizationCreationTokenRequest(
                label = label?.takeIf { it.isNotBlank() },
                maxUses = maxUses,
                expiresInHours = expiresInHours
            )
        )

    suspend fun revokeCreationToken(id: String) = api.revokeCreationToken(id)

    override suspend fun syncPendingVisits(): VisitSyncOutcome = syncMutex.withLock { syncPendingVisitsUnsafe() }

    private suspend fun syncPendingVisitsUnsafe(): VisitSyncOutcome {
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

    companion object {
        private val syncMutex = Mutex()
    }
}

/**
 * The captions the web app attaches to its BEFORE/AFTER uploads, so a photo
 * reads the same whichever client logged it. Derived from the tag at upload
 * time rather than stored on the draft, which keeps the Room schema unchanged.
 */
internal fun defaultPhotoCaption(tag: String): String? = when (tag) {
    "BEFORE" -> "Baseline before procedure photo"
    "AFTER" -> "Finished procedure photo result"
    else -> null
}

/**
 * Epoch millis for a backend `LocalDateTime.toString()` value.
 *
 * Only the minute prefix is parsed: `LocalDateTime` drops the seconds field
 * entirely when it is zero, so the full string has no single fixed pattern.
 * `java.time` is avoided because minSdk 24 predates it without desugaring.
 */
internal fun parseServerTimestamp(value: String): Long? = runCatching {
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US)
        .apply { isLenient = false }
        .parse(value.take(16))?.time
}.getOrNull()

/**
 * Keeps the server's `updatedAt`, which the directory shows as "Updated …"
 * and sorts by, as the web app does. Falls back to now only for a value the
 * backend should never send.
 */
internal fun ClientDto.toEntity(organizationId: String, json: Json = Json) = ClientEntity(
    id = id,
    organizationId = organizationId,
    name = name,
    phone = phone,
    email = email,
    tagsJson = json.encodeToString(tags),
    customFieldsJson = customFields.toString(),
    totalVisits = totalVisits,
    isSynced = true,
    updatedAt = parseServerTimestamp(updatedAt) ?: System.currentTimeMillis()
)

private fun VisitEntity.toRequest() = CreateVisitRequest(
    clientId = clientId,
    visitDateTime = visitDateTime,
    durationMinutes = durationMinutes,
    procedureNotes = procedureNotes,
    status = status
)
