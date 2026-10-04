package com.beauty.app.ui.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.api.safeMessage
import com.beauty.app.data.toEntity
import kotlinx.serialization.json.JsonObject
import com.beauty.app.data.api.VisitHistoryDto
import com.beauty.app.data.local.ClientDao
import com.beauty.app.data.local.ClientEntity
import com.beauty.app.data.local.VisitDao
import com.beauty.app.data.local.VisitEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ClientDetailViewModel(
    private val clientId: String,
    val organizationId: String,
    private val repository: BeautyRepository,
    private val clientDao: ClientDao,
    visitDao: VisitDao
) : ViewModel() {
    var client by mutableStateOf<ClientEntity?>(null)
        private set
    var visits by mutableStateOf<List<VisitHistoryDto>>(emptyList())
        private set
    var localVisits by mutableStateOf<List<VisitEntity>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var historyLoaded by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    var deleting by mutableStateOf(false)
        private set
    var deleteError by mutableStateOf<String?>(null)
        private set
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            visitDao.getVisitsForClient(clientId, organizationId).collect { updated ->
                val uploaded = localVisits.any { old ->
                    old.isPendingSync && updated.any { it.id == old.id && !it.isPendingSync }
                }
                localVisits = updated
                if (uploaded) refresh()
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            loading = true
            error = null
            try {
                client = clientDao.getClientById(clientId, organizationId)
                visits = repository.getVisitsForClient(organizationId, clientId)
                historyLoaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                visits = repository.getCachedVisitsForClient(organizationId, clientId)
                historyLoaded = false
                error = "COULD_NOT_REFRESH_VISITS"
            } finally {
                loading = false
            }
        }
    }

    var savingAttributes by mutableStateOf(false)
        private set
    var attributesError by mutableStateOf<String?>(null)
        private set

    fun clearSaveError() { saveError = null }
    fun clearAttributesError() { attributesError = null }

    /**
     * Replaces the client's custom attributes, keeping every other field as the
     * cache has it — the equivalent of the web's `updateClient(id, { customFields })`.
     * Requires connectivity, like every other client write.
     */
    fun saveAttributes(fields: JsonObject, onSaved: () -> Unit) {
        val current = client ?: return
        if (savingAttributes) return
        savingAttributes = true
        attributesError = null
        viewModelScope.launch {
            try {
                val dto = repository.updateClient(
                    orgId = organizationId,
                    id = current.id,
                    name = current.name,
                    phone = current.phone,
                    email = current.email,
                    tags = decodeTags(current.tagsJson),
                    customFields = fields
                )
                val entity = dto.toEntity(organizationId)
                repository.upsertClientLocally(entity)
                client = entity
                onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                attributesError = error.safeMessage("COULD_NOT_SAVE_ATTRIBUTES")
            } finally {
                savingAttributes = false
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        if (deleting) return
        deleting = true; deleteError = null
        viewModelScope.launch {
            try { repository.deleteClient(organizationId, clientId); onDeleted() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { deleteError = error.safeMessage("COULD_NOT_DELETE_CLIENT") }
            finally { deleting = false }
        }
    }

    fun saveVisit(dateTime: String, duration: String, notes: String, status: String, onSaved: () -> Unit) =
        saveVisitWithPhotos(dateTime, duration, notes, status, onSaved, null, emptyList())

    fun saveVisitWithPhotos(dateTime: String, duration: String, notes: String, status: String, onSaved: () -> Unit,
                            context: Context? = null, photos: List<Pair<String, Uri>> = emptyList()) {
        if (saving) return
        val parsed = duration.trim().toIntOrNull()
        visitDurationError(parsed)?.let {
            saveError = it
            return
        }
        val minutes = parsed ?: return
        if (notes.isBlank()) {
            saveError = "ENTER_PROCEDURE_NOTES"
            return
        }
        saving = true
        saveError = null
        viewModelScope.launch {
            try {
                val localVisitId = repository.enqueueVisit(organizationId, clientId, dateTime, minutes, notes.trim(), status)
                if (context != null) photos.forEach { (tag, uri) ->
                    val file = File(context.filesDir, "photo_${localVisitId}_${tag.lowercase()}_${System.nanoTime()}.jpg")
                    withContext(Dispatchers.IO) { compressPhotoForUpload(context, uri, file) }
                    deleteCapturedPhoto(context, uri)
                    repository.addPhotoDraft(organizationId, clientId, localVisitId, file.absolutePath, tag)
                }
                onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveError = "COULD_NOT_SAVE_VISIT"
            } finally {
                saving = false
            }
        }
    }

    /** Key of the history row whose photo is being prepared or uploaded. */
    var addingPhotoTo by mutableStateOf<String?>(null)
        private set
    /** History row key and message code for the outcome of the last photo added to an existing visit. */
    var photoNotice by mutableStateOf<Pair<String, String>?>(null)
        private set

    fun clearPhotoNotice() { photoNotice = null }

    /**
     * Adds a photo to a visit after it was logged — typically the AFTER shot,
     * taken once the procedure is done.
     *
     * The photo becomes a draft first, so a dropped connection never loses it:
     * the upload is tried at once, and on failure `onPending` lets the caller
     * hand the draft to `SyncWorker`. A visit still waiting in the offline queue
     * has no server id yet; its draft uploads once the visit does.
     */
    fun addPhotoToVisit(
        context: Context,
        rowKey: String,
        remoteVisitId: String?,
        localVisitId: String?,
        tag: String,
        uri: Uri,
        onPending: () -> Unit
    ) {
        if (addingPhotoTo != null) return
        addingPhotoTo = rowKey
        photoNotice = null
        viewModelScope.launch {
            try {
                val file = File(context.filesDir, "photo_${remoteVisitId ?: localVisitId}_${tag.lowercase()}_${System.nanoTime()}.jpg")
                try {
                    withContext(Dispatchers.IO) { compressPhotoForUpload(context, uri, file) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    photoNotice = rowKey to "COULD_NOT_READ_PHOTO"
                    return@launch
                }
                deleteCapturedPhoto(context, uri)
                val draft = repository.addPhotoDraft(organizationId, clientId, localVisitId.orEmpty(), file.absolutePath, tag, remoteVisitId)
                try {
                    repository.uploadPhotoDraft(draft.id)
                    refresh()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    photoNotice = rowKey to "PHOTO_SAVED_UPLOAD_PENDING"
                    onPending()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                photoNotice = rowKey to "COULD_NOT_SAVE_PHOTO"
            } finally {
                addingPhotoTo = null
            }
        }
    }
}

/** The server snapshot wins; local uploads must not appear twice after syncing. */
fun localHistoryExtras(
    remoteVisits: List<VisitHistoryDto>,
    localVisits: List<VisitEntity>,
    useLocalFallback: Boolean
): List<VisitEntity> {
    val remoteIds = remoteVisits.map { it.id }.toSet()
    return localVisits.filter {
        (it.isPendingSync || useLocalFallback) && it.id !in remoteIds && it.remoteId !in remoteIds
    }
}

/** Mirrors the web visit form's `min="15" step="15"` duration input. */
internal const val VISIT_DURATION_STEP_MINUTES = 15

internal fun visitDurationError(minutes: Int?): String? = when {
    minutes == null || minutes < VISIT_DURATION_STEP_MINUTES ->
        "DURATION_MIN:$VISIT_DURATION_STEP_MINUTES"
    minutes % VISIT_DURATION_STEP_MINUTES != 0 ->
        "DURATION_STEP:$VISIT_DURATION_STEP_MINUTES"
    else -> null
}
