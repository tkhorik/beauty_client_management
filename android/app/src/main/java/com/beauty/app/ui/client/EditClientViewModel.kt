package com.beauty.app.ui.client

import com.beauty.app.data.api.safeMessage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.local.ClientDao
import com.beauty.app.data.local.ClientEntity
import com.beauty.app.data.toEntity
import com.beauty.app.ui.auth.AuthValidation
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
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
    private val clientDao: ClientDao,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
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

    @Serializable
    data class CustomField(
        val key: String,
        val value: String,
        // JSON text distinguishes an original JSON null from a newly added field.
        val originalJson: String? = null,
        val id: String = UUID.randomUUID().toString()
    ) {
        val originalValue: JsonElement? get() = originalJson?.let(Json::parseToJsonElement)
    }

    private val restored = savedStateHandle.get<String>(DRAFT_KEY)?.let { raw ->
        runCatching { Json.decodeFromString<ClientEditorSnapshot>(raw) }.getOrNull()
            ?.takeIf { it.organizationId == organizationId && it.clientId == clientId }
    }
    private var baseline = restored?.baseline ?: ClientEditorDraft()
    private var draft by mutableStateOf(restored?.draft ?: baseline)
    val name get() = draft.name
    val phone get() = draft.phone
    val email get() = draft.email
    val tags get() = draft.tags
    val tagInput get() = draft.tagInput
    val customFields get() = draft.customFields
    val isDirty get() = draft != baseline
    val isSaving get() = saveState is SaveState.Loading
    val canEdit get() = !isSaving && saveState !is SaveState.Success &&
        (isNewClient || existingClientState is ExistingClientState.Ready)

    init {
        clientId?.let { existingClientId -> viewModelScope.launch {
            val entity: ClientEntity? = clientDao.getClientById(existingClientId, organizationId)
            if (entity != null && restored == null) {
                baseline = ClientEditorDraft(
                    name = entity.name,
                    phone = entity.phone,
                    email = entity.email.orEmpty(),
                    tags = runCatching { Json.decodeFromString<List<String>>(entity.tagsJson) }
                        .getOrDefault(emptyList()),
                    customFields = runCatching { Json.parseToJsonElement(entity.customFieldsJson).jsonObject }
                        .getOrNull()?.map { (key, value) -> CustomField(key, displayValue(value), value.toString()) }
                        .orEmpty()
                )
                draft = baseline
            }
            existingClientState = if (entity == null) ExistingClientState.Missing else ExistingClientState.Ready
            persistDraft()
        } }
    }

    private fun persistDraft() {
        savedStateHandle[DRAFT_KEY] = Json.encodeToString(ClientEditorSnapshot(organizationId, clientId, baseline, draft))
    }

    private fun edit(transform: (ClientEditorDraft) -> ClientEditorDraft) {
        if (!canEdit) return
        draft = transform(draft)
        persistDraft()
    }

    fun updateName(v: String) = edit { it.copy(name = v) }
    fun updatePhone(v: String) = edit { it.copy(phone = v) }
    fun updateEmail(v: String) = edit { it.copy(email = v) }
    fun updateTagInput(v: String) = edit { it.copy(tagInput = v) }
    fun addPendingTag() = edit { it.withPendingTag() }
    fun removeTag(tag: String) = edit { it.copy(tags = it.tags - tag) }
    fun addCustomField() = edit { it.copy(customFields = it.customFields + CustomField("", "")) }
    fun updateCustomField(index: Int, key: String, value: String) = edit { current ->
        current.copy(customFields = current.customFields.mapIndexed { i, field ->
            if (i == index) field.copy(key = key, value = value) else field
        })
    }
    fun removeCustomField(index: Int) = edit { current ->
        current.copy(customFields = current.customFields.filterIndexed { i, _ -> i != index })
    }

    fun save() {
        if (!canEdit) return
        // Save includes the tag still in the input; it must not disappear when the form closes.
        draft = draft.withPendingTag()
        persistDraft()
        // Lock synchronously: a second tap or Back must not race coroutine dispatch.
        saveState = SaveState.Loading
        viewModelScope.launch {
            val trimmedName = name.trim()
            // Clients saved before phone validation existed keep their number
            // until someone changes it, exactly as the server allows.
            val phoneChanged = isNewClient || phone.trim() != baseline.phone.trim()
            val phoneIssue = if (phoneChanged) AuthValidation.phoneError(phone) else null
            val trimmedPhone = if (phoneChanged && phoneIssue == null) AuthValidation.formatPhone(phone) else phone.trim()
            val normalizedEmail = email.trim().takeIf { it.isNotEmpty() }?.let(AuthValidation::normaliseEmail)
            when {
                trimmedName.isEmpty() -> {
                    saveState = SaveState.Error("CLIENT_NAME_REQUIRED")
                    return@launch
                }
                phoneIssue != null -> {
                    saveState = SaveState.Error(phoneIssue)
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
                    "DUPLICATE_FIELD:$duplicateKey"
                )
                return@launch
            }

            saveState = SaveState.Loading
            saveState = try {
                val cfJsonObject = JsonObject(
                    nonBlankCustomFields.associate { field ->
                        val original = field.originalValue
                        field.key.trim() to if (original != null && field.value == displayValue(original)) {
                            original
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

                repository.upsertClientLocally(dto.toEntity(organizationId))
                SaveState.Success
            } catch (e: ClientRequestException) {
                SaveState.Error(e.safeMessage("ACTION_FAILED"))
            } catch (e: Exception) {
                // Cancellation must reach the parent scope; treating it as a
                // failed save leaves a stale error after navigation.
                if (e is kotlinx.coroutines.CancellationException) throw e
                SaveState.Error("NETWORK_ERROR")
            }
        }
    }

    private fun displayValue(element: JsonElement): String =
        (element as? JsonPrimitive)?.content ?: element.toString()

    companion object {
        internal const val DRAFT_KEY = "client_editor_draft"
    }
}
