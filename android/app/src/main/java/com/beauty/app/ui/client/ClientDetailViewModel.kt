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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.FileOutputStream

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
                error = "Could not refresh visit history. Previously loaded visits and visits saved on this device may be incomplete."
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
                attributesError = error.safeMessage("Could not save attributes. Check your connection and try again.")
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
            catch (error: Exception) { deleteError = error.safeMessage("Could not delete this client.") }
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
            saveError = "Enter procedure notes."
            return
        }
        saving = true
        saveError = null
        viewModelScope.launch {
            try {
                val localVisitId = repository.enqueueVisit(organizationId, clientId, dateTime, minutes, notes.trim(), status)
                if (context != null) photos.forEach { (tag, uri) ->
                    val file = File(context.filesDir, "photo_${localVisitId}_${tag.lowercase()}_${System.nanoTime()}.jpg")
                    val compressed = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    if (compressed == null) throw IllegalStateException("Could not read selected photo")
                    val scale = minOf(1f, 1200f / compressed.width.toFloat())
                    val output = if (scale < 1f) Bitmap.createScaledBitmap(compressed, (compressed.width * scale).toInt(), (compressed.height * scale).toInt(), true) else compressed
                    FileOutputStream(file).use { output.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    if (output !== compressed) output.recycle()
                    compressed.recycle()
                    repository.addPhotoDraft(organizationId, clientId, localVisitId, file.absolutePath, tag)
                }
                onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveError = "Could not save this visit on the device. Please try again."
            } finally {
                saving = false
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
        "Duration must be at least $VISIT_DURATION_STEP_MINUTES minutes."
    minutes % VISIT_DURATION_STEP_MINUTES != 0 ->
        "Duration must be in $VISIT_DURATION_STEP_MINUTES-minute steps (15, 30, 45, 60…)."
    else -> null
}
