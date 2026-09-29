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
import com.beauty.app.ui.auth.AuthValidation
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class EditClientViewModel(
    /** Null while creating a new client; otherwise the immutable record being edited. */
    private val clientId: String? = null,
    /**
     * The organization this client belongs to.
     *
     * Passed in rather than read from the store at save time: the screen was
     * opened against one salon's record, and a switch made in another tab of
     * the user's life must not silently retarget the update.
     */
    private val organizationId: String,
    private val repository: BeautyRepository,
    private val clientDao: ClientDao
) : ViewModel() {

    val isNewClient: Boolean get() = clientId == null

    sealed interface ExistingClientState {
        object NotApplicable : ExistingClientState
        object Loading : ExistingClientState
        object Ready : ExistingClientState
        object Missing : ExistingClientState
    }

    sealed interface SaveState {
        object Idle : SaveState
        object Loading : SaveState
        object Success : SaveState
        data class Error(val message: String) : SaveState
    }

    var saveState: SaveState by mutableStateOf(SaveState.Idle)
        private set

    var existingClientState: ExistingClientState by mutableStateOf(
        if (clientId == null) ExistingClientState.NotApplicable else ExistingClientState.Loading
    )
        private set

    var name by mutableStateOf("")
        private set
    var phone by mutableStateOf("")
        private set
    var email by mutableStateOf("")
        private set

    val tags = mutableStateListOf<String>()

    /**
     * The editable text plus the element read from the server/cache. Keeping
     * that element lets a no-op edit retain JSON number/boolean/string types
     * instead of turning every value into a string on save.
     */
    data class CustomField(
        val key: String,
        val value: String,
        val originalValue: JsonElement? = null
    )

    val customFields = mutableStateListOf<CustomField>()

    init {
        clientId?.let { existingClientId -> viewModelScope.launch {
            val entity: ClientEntity? = clientDao.getClientById(existingClientId, organizationId)
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
                    // JsonPrimitive.content decodes escaped JSON strings (for
                    // example, `\"A\\nB\"` becomes a real newline), unlike
                    // `toString().removeSurrounding(\"\\\"\")`.
                    val display = (v as? JsonPrimitive)?.content ?: v.toString()
                    customFields.add(CustomField(k, display, v))
                }
            }
            existingClientState = if (entity == null) ExistingClientState.Missing else ExistingClientState.Ready
        } }
    }

    fun updateName(v: String) { name = v }
    fun updatePhone(v: String) { phone = v }
    fun updateEmail(v: String) { email = v }
    fun addTag(tag: String) { if (tag.isNotBlank() && !tags.contains(tag)) tags.add(tag) }
    fun removeTag(tag: String) { tags.remove(tag) }
    fun addCustomField() { customFields.add(CustomField("", "")) }
    fun updateCustomField(index: Int, key: String, value: String) {
        if (index in customFields.indices) {
            customFields[index] = customFields[index].copy(key = key, value = value)
        }
    }
    fun removeCustomField(index: Int) {
        if (index in customFields.indices) customFields.removeAt(index)
    }

    fun save() {
        if (saveState is SaveState.Loading) return
        if (!isNewClient && existingClientState !is ExistingClientState.Ready) return
        viewModelScope.launch {
            val trimmedName = name.trim()
            val trimmedPhone = phone.trim()
            val normalizedEmail = email.trim().takeIf { it.isNotEmpty() }?.let(AuthValidation::normaliseEmail)
            when {
                trimmedName.isEmpty() -> {
                    saveState = SaveState.Error("Client name is required.")
                    return@launch
                }
                trimmedPhone.isEmpty() -> {
                    saveState = SaveState.Error("Phone number is required.")
                    return@launch
                }
                normalizedEmail != null -> AuthValidation.emailError(normalizedEmail)?.let {
                    saveState = SaveState.Error(it)
                    return@launch
                }
            }

            val nonBlankCustomFields = customFields.filter { it.key.isNotBlank() }
            val duplicateKey = nonBlankCustomFields
                .groupingBy { it.key.trim() }
                .eachCount()
                .entries
                .firstOrNull { it.value > 1 }
                ?.key

            if (duplicateKey != null) {
                saveState = SaveState.Error(
                    "Custom field \"$duplicateKey\" is entered more than once. Field names must be unique."
                )
                return@launch
            }

            saveState = SaveState.Loading
            saveState = try {
                val cfJsonObject = JsonObject(
                    nonBlankCustomFields.associate { field ->
                        field.key.trim() to if (field.originalValue != null && field.value == displayValue(field.originalValue)) {
                            field.originalValue
                        } else {
                            JsonPrimitive(field.value)
                        }
                    }
                )

                val dto = clientId?.let { id ->
                    repository.updateClient(
                        orgId = organizationId,
                        id = id,
                        name = trimmedName,
                        phone = trimmedPhone,
                        email = normalizedEmail,
                        tags = tags.toList(),
                        customFields = cfJsonObject
                    )
                } ?: repository.createClient(
                    orgId = organizationId,
                    name = trimmedName,
                    phone = trimmedPhone,
                    email = normalizedEmail,
                    tags = tags.toList(),
                    customFields = cfJsonObject
                )

                val updatedEntity = ClientEntity(
                    id = dto.id,
                    organizationId = organizationId,
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
                // Cancellation must reach the parent scope; treating it as a
                // failed save leaves a stale error after navigation.
                if (e is kotlinx.coroutines.CancellationException) throw e
                SaveState.Error(e.message ?: "Server could not be reached.")
            }
        }
    }

    private fun displayValue(element: JsonElement): String =
        (element as? JsonPrimitive)?.content ?: element.toString()
}
