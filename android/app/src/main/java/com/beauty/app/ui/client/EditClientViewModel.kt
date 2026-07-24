package com.beauty.app.ui.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.local.ClientDao
import com.beauty.app.data.local.ClientEntity
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class EditClientViewModel(
    private val clientId: String,
    private val repository: BeautyRepository,
    private val clientDao: ClientDao
) : ViewModel() {

    sealed interface SaveState {
        object Idle : SaveState
        object Loading : SaveState
        object Success : SaveState
        data class Error(val message: String) : SaveState
    }

    var saveState: SaveState by mutableStateOf(SaveState.Idle)
        private set

    var name by mutableStateOf("")
        private set
    var phone by mutableStateOf("")
        private set
    var email by mutableStateOf("")
        private set

    val tags = mutableStateListOf<String>()
    val customFields = mutableStateListOf<Pair<String, String>>()

    init {
        viewModelScope.launch {
            val entity: ClientEntity? = clientDao.getClientById(clientId)
            entity?.let { e ->
                name = e.name
                phone = e.phone
                email = e.email ?: ""

                val parsedTags = runCatching {
                    Json.decodeFromString<List<String>>(e.tagsJson)
                }.getOrDefault(emptyList())
                tags.addAll(parsedTags)

                runCatching {
                    Json.parseToJsonElement(e.customFieldsJson).jsonObject
                }.getOrNull()?.forEach { (k, v) ->
                    customFields.add(k to v.toString().removeSurrounding("\""))
                }
            }
        }
    }

    fun updateName(v: String) { name = v }
    fun updatePhone(v: String) { phone = v }
    fun updateEmail(v: String) { email = v }
    fun addTag(tag: String) { if (tag.isNotBlank() && !tags.contains(tag)) tags.add(tag) }
    fun removeTag(tag: String) { tags.remove(tag) }
    fun addCustomField() { customFields.add("" to "") }
    fun updateCustomField(index: Int, key: String, value: String) {
        if (index in customFields.indices) customFields[index] = key to value
    }
    fun removeCustomField(index: Int) {
        if (index in customFields.indices) customFields.removeAt(index)
    }

    fun save() {
        viewModelScope.launch {
            saveState = SaveState.Loading
            saveState = try {
                val cfJsonObject = JsonObject(
                    customFields
                        .filter { it.first.isNotBlank() }
                        .associate { (k, v) -> k to JsonPrimitive(v) }
                )

                val dto = repository.updateClient(
                    id = clientId,
                    name = name,
                    phone = phone,
                    email = email.ifBlank { null },
                    tags = tags.toList(),
                    customFields = cfJsonObject
                )

                val updatedEntity = ClientEntity(
                    id = dto.id,
                    name = dto.name,
                    phone = dto.phone,
                    email = dto.email,
                    tagsJson = Json.encodeToString(dto.tags),
                    customFieldsJson = dto.customFields.toString(),
                    totalVisits = dto.totalVisits,
                    isSynced = true,
                    updatedAt = System.currentTimeMillis()
                )
                repository.upsertClientLocally(updatedEntity)
                SaveState.Success
            } catch (e: ClientRequestException) {
                SaveState.Error("Save failed: ${e.response.status.value}")
            } catch (e: Exception) {
                SaveState.Error(e.message ?: "Server could not be reached.")
            }
        }
    }
}
