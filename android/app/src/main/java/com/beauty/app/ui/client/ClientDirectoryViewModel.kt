package com.beauty.app.ui.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.api.isNotAMember
import com.beauty.app.data.local.ClientDao
import com.beauty.app.data.local.ClientEntity
import com.beauty.app.data.toEntity
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class ClientDirectoryViewModel(
    private val orgId: String,
    private val repository: BeautyRepository,
    clientDao: ClientDao
) : ViewModel() {
    var clients by mutableStateOf<List<ClientEntity>>(emptyList())
        private set
    var query by mutableStateOf("")
        private set
    var tag by mutableStateOf("")
        private set
    var refreshing by mutableStateOf(false)
        private set
    var searching by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var blocked by mutableStateOf(false)
        private set
    var lastRefresh by mutableStateOf(0L)
        private set

    /**
     * The server said this account is no longer a member of [orgId]. Its cache
     * has been purged; the screen should hand over to organization selection.
     */
    var membershipLost by mutableStateOf(false)
        private set
    private var results by mutableStateOf<List<ClientEntity>?>(null)
    private var searchJob: Job? = null

    val filtered: List<ClientEntity> get() = if (blocked) emptyList() else results ?: clients.filter {
        val q = query.trim()
        val tags = runCatching { Json.decodeFromString<List<String>>(it.tagsJson).joinToString(",") }.getOrDefault("")
        (q.isEmpty() || listOf(it.name, it.phone, it.email.orEmpty(), tags).any { value -> value.contains(q, true) }) &&
            (tag.isEmpty() || tags.contains(tag, true))
    }

    init {
        viewModelScope.launch { clientDao.getAllClients(orgId).collect { clients = it } }
    }

    fun updateQuery(value: String) { query = value; search() }
    fun updateTag(value: String) { tag = value; search() }
    fun clearFilters() { query = ""; tag = ""; search() }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            try {
                repository.refreshClients(orgId).getOrThrow()
                lastRefresh = System.currentTimeMillis()
                message = null
                blocked = false
                search()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showFailure(error)
            } finally { refreshing = false }
        }
    }

    private fun search() {
        searchJob?.cancel()
        results = null
        searching = false
        if (query.isBlank() && tag.isEmpty()) return
        val requestedQuery = query.trim()
        val requestedTag = tag
        searchJob = viewModelScope.launch {
            delay(250)
            searching = true
            try {
                results = repository.searchClients(orgId, requestedQuery, requestedTag).map { it.toEntity(orgId) }
                message = null
                blocked = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showFailure(error)
            } finally { searching = false }
        }
    }

    private suspend fun showFailure(error: Exception) {
        blocked = error is ResponseException && error.response.status.value in listOf(401, 403, 404)
        message = if (blocked) "ACCESS_DENIED"
            else "NO_CACHED_CLIENTS"
        if (error.isNotAMember()) {
            repository.purgeOrganization(orgId)
            membershipLost = true
        }
    }
}
