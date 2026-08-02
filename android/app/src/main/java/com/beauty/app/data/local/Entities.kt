package com.beauty.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "clients", indices = [Index(value = ["organizationId"])])
data class ClientEntity(
    @PrimaryKey val id: String,
    /**
     * The owning organization.
     *
     * The cache holds rows for every organization the user has visited on this
     * device, so this column is what keeps them apart. Every query below filters
     * on it — without that, switching salons would briefly show the previous
     * one's clients under the new one's name, which is precisely the confusion
     * multi-tenancy exists to prevent.
     */
    val organizationId: String,
    val name: String,
    val phone: String,
    val email: String?,
    val tagsJson: String, // JSON Array
    val customFieldsJson: String, // JSONB String map
    val totalVisits: Int,
    val isSynced: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "visits",
    foreignKeys = [
        ForeignKey(
            entity = ClientEntity::class,
            parentColumns = ["id"],
            childColumns = ["clientId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["clientId"]), Index(value = ["organizationId"])]
)
data class VisitEntity(
    @PrimaryKey val id: String,
    val remoteId: String? = null,
    /**
     * The organization this visit was recorded against, captured at the moment
     * it was queued.
     *
     * Stored per row rather than read from [OrgStore] at upload time, because
     * those are not the same thing: a visit can sit in the offline queue for
     * days while the user switches to another salon, and uploading it under
     * whichever organization happens to be selected then would file a client's
     * treatment record with the wrong business.
     */
    val organizationId: String,
    val clientId: String,
    val visitDateTime: String,
    val durationMinutes: Int,
    val procedureNotes: String,
    val status: String,
    val isPendingSync: Boolean = false,
    val syncError: String? = null,
    val syncAttempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = VisitEntity::class,
            parentColumns = ["id"],
            childColumns = ["visitId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["visitId"])]
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val visitId: String,
    val localFilePath: String,
    val remoteFileUrl: String?,
    val tag: String, // BEFORE, AFTER, PROCEDURE, DOCUMENT
    val caption: String?,
    val isSynced: Boolean = false
)

@Dao
interface ClientDao {
    /**
     * The directory for one organization.
     *
     * `organizationId` is a required parameter rather than an optional filter:
     * an unscoped "all clients" query would be the single most likely way for
     * one salon's records to appear under another's name, and there is no
     * screen in the app that legitimately wants one.
     */
    @Query("SELECT * FROM clients WHERE organizationId = :organizationId ORDER BY updatedAt DESC")
    fun getAllClients(organizationId: String): Flow<List<ClientEntity>>

    @Query("SELECT * FROM clients WHERE id = :id AND organizationId = :organizationId")
    suspend fun getClientById(id: String, organizationId: String): ClientEntity?

    // REPLACE is implemented as DELETE + INSERT in SQLite, which would fire
    // the visit foreign-key cascade during every directory refresh. Upsert
    // updates the client row in place and keeps the local visit sync queue.
    @Upsert
    suspend fun insertClient(client: ClientEntity)

    @Upsert
    suspend fun insertClients(clients: List<ClientEntity>)

    @Query("DELETE FROM clients WHERE id = :id")
    suspend fun deleteClient(id: String)

    // Do not discard an unsent visit simply because its client was removed on
    // another device.  The pending visit remains available for an explicit
    // retry/error resolution instead of being silently lost through the
    // foreign-key cascade.
    // Both deletes are scoped to the organization being reconciled. Without
    // that, refreshing one salon's directory would delete every other salon's
    // cached clients — the server snapshot only ever describes one organization,
    // so "not in this snapshot" does not mean "deleted".
    @Query("DELETE FROM clients WHERE organizationId = :organizationId AND id NOT IN (:serverIds) AND NOT EXISTS (SELECT 1 FROM visits WHERE visits.clientId = clients.id AND visits.isPendingSync = 1)")
    suspend fun deleteClientsMissingFromSnapshot(organizationId: String, serverIds: List<String>)

    @Query("DELETE FROM clients WHERE organizationId = :organizationId AND NOT EXISTS (SELECT 1 FROM visits WHERE visits.clientId = clients.id AND visits.isPendingSync = 1)")
    suspend fun deleteAllClientsWithoutPendingVisits(organizationId: String)

    /** Atomically make one organization's local directory match the server's snapshot. */
    @Transaction
    suspend fun reconcileClients(organizationId: String, serverClients: List<ClientEntity>) {
        insertClients(serverClients)
        if (serverClients.isEmpty()) {
            deleteAllClientsWithoutPendingVisits(organizationId)
        } else {
            deleteClientsMissingFromSnapshot(organizationId, serverClients.map { it.id })
        }
    }
}

@Dao
interface VisitDao {
    @Query("SELECT * FROM visits WHERE clientId = :clientId AND organizationId = :organizationId ORDER BY createdAt DESC")
    fun getVisitsForClient(clientId: String, organizationId: String): Flow<List<VisitEntity>>

    /**
     * The upload queue, across *all* organizations.
     *
     * Deliberately unscoped: the queue belongs to the device, not to whichever
     * salon is on screen. Each row carries its own `organizationId`, which is
     * what the uploader sends — see `BeautyRepository.syncPendingVisits`.
     */
    @Query("SELECT * FROM visits WHERE isPendingSync = 1 AND remoteId IS NULL ORDER BY createdAt ASC")
    suspend fun getUnsyncedVisits(): List<VisitEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVisit(visit: VisitEntity)

    @Query("UPDATE visits SET remoteId = :remoteId, isPendingSync = 0, syncError = NULL WHERE id = :visitId")
    suspend fun markVisitSynced(visitId: String, remoteId: String)

    @Query("UPDATE visits SET syncError = :error, syncAttempts = syncAttempts + 1 WHERE id = :visitId")
    suspend fun markVisitSyncFailed(visitId: String, error: String)
}

@Database(
    entities = [ClientEntity::class, VisitEntity::class, AttachmentEntity::class],
    version = 4,
    exportSchema = false
)
abstract class BeautyDatabase : RoomDatabase() {
    abstract fun clientDao(): ClientDao
    abstract fun visitDao(): VisitDao
}
