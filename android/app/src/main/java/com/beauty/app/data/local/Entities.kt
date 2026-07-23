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
    val clientId: String,
    val visitDateTime: String,
    val durationMinutes: Int,
    val procedureNotes: String,
    val status: String,
    val isPendingSync: Boolean = false,
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClient(client: ClientEntity)

    @Query("DELETE FROM clients WHERE id = :id")
    suspend fun deleteClient(id: String)
}

@Dao
interface VisitDao {
    @Query("SELECT * FROM visits WHERE clientId = :clientId ORDER BY createdAt DESC")
    fun getVisitsForClient(clientId: String): Flow<List<VisitEntity>>

    @Query("SELECT * FROM visits WHERE isPendingSync = 1")
    suspend fun getUnsyncedVisits(): List<VisitEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVisit(visit: VisitEntity)

    @Query("UPDATE visits SET isPendingSync = 0 WHERE id = :visitId")
    suspend fun markVisitSynced(visitId: String)
}

@Database(
    entities = [ClientEntity::class, VisitEntity::class, AttachmentEntity::class],
    version = 1,
    exportSchema = false
)
abstract class BeautyDatabase : RoomDatabase() {
    abstract fun clientDao(): ClientDao
    abstract fun visitDao(): VisitDao
}
