package com.beauty.app.ui.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.api.VisitHistoryDto
import com.beauty.app.data.local.ClientDao
import com.beauty.app.data.local.ClientEntity
import com.beauty.app.data.local.VisitDao
import com.beauty.app.data.local.VisitEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ClientDetailViewModel(
    private val clientId: String,
    private val organizationId: String,
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
                error = "Could not refresh visit history. Previously loaded visits and visits saved on this device may be incomplete."
            } finally {
                loading = false
            }
        }
    }

    fun clearSaveError() { saveError = null }

    fun saveVisit(dateTime: String, duration: String, notes: String, status: String, onSaved: () -> Unit) {
        if (saving) return
        val minutes = duration.toIntOrNull()
        if (minutes == null || minutes <= 0 || notes.isBlank()) {
            saveError = "Enter procedure notes and a duration greater than zero."
            return
        }
        saving = true
        saveError = null
        viewModelScope.launch {
            try {
                repository.enqueueVisit(organizationId, clientId, dateTime, minutes, notes.trim(), status)
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
