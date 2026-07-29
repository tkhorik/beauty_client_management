package com.beauty.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "clients")
data class ClientEntity(
    @PrimaryKey val id: String,
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
    ]
)
data class VisitEntity(
    @PrimaryKey val id: String,
    val remoteId: String? = null,
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
    ]
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
    @Query("SELECT * FROM clients ORDER BY updatedAt DESC")
    fun getAllClients(): Flow<List<ClientEntity>>

    @Query("SELECT * FROM clients WHERE id = :id")
    suspend fun getClientById(id: String): ClientEntity?

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
    @Query("DELETE FROM clients WHERE id NOT IN (:serverIds) AND NOT EXISTS (SELECT 1 FROM visits WHERE visits.clientId = clients.id AND visits.isPendingSync = 1)")
    suspend fun deleteClientsMissingFromSnapshot(serverIds: List<String>)

    @Query("DELETE FROM clients WHERE NOT EXISTS (SELECT 1 FROM visits WHERE visits.clientId = clients.id AND visits.isPendingSync = 1)")
    suspend fun deleteAllClientsWithoutPendingVisits()

    /** Atomically make the local directory match the server's authoritative snapshot. */
    @Transaction
    suspend fun reconcileClients(serverClients: List<ClientEntity>) {
        insertClients(serverClients)
        if (serverClients.isEmpty()) {
            deleteAllClientsWithoutPendingVisits()
        } else {
            deleteClientsMissingFromSnapshot(serverClients.map { it.id })
        }
    }
}

@Dao
interface VisitDao {
    @Query("SELECT * FROM visits WHERE clientId = :clientId ORDER BY createdAt DESC")
    fun getVisitsForClient(clientId: String): Flow<List<VisitEntity>>

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
    version = 2,
    exportSchema = false
)
abstract class BeautyDatabase : RoomDatabase() {
    abstract fun clientDao(): ClientDao
    abstract fun visitDao(): VisitDao
}
