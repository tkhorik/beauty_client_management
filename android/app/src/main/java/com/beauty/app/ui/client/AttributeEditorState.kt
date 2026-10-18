package com.beauty.app.ui.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/** Owned by the screen, not its lazy item: scrolling and refreshes cannot discard edits. */
internal class AttributeEditorState(private val organizationId: String, private val clientId: String) {
    var rows by mutableStateOf<List<AttributeDraft>?>(null)
        private set
    var draftId by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)

    fun start(customFieldsJson: String) {
        if (rows != null) return
        val original = runCatching { Json.parseToJsonElement(customFieldsJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        draftId = UUID.randomUUID().toString()
        rows = original.map { (key, value) -> AttributeDraft(key, displayValue(value), value.toString()) }
    }

    fun update(index: Int, field: AttributeDraft) {
        rows = rows?.toMutableList()?.apply { this[index] = field }
    }

    fun remove(index: Int) {
        rows = rows?.toMutableList()?.apply { removeAt(index) }
    }

    fun add() { rows = rows?.plus(AttributeDraft("", "")) }

    /** Only cancel and a confirmed successful save end the draft. */
    fun clear() { rows = null; draftId = null; error = null }

    fun clearIfSaved(savedDraftId: String?) {
        if (savedDraftId != null && savedDraftId == draftId) clear()
    }

    companion object {
        fun saver(organizationId: String, clientId: String) = listSaver<AttributeEditorState, String>(
            save = {
                listOf(it.organizationId, it.clientId, it.rows?.let { rows -> Json.encodeToString(rows) } ?: "", it.error ?: "", it.draftId ?: "")
            },
            restore = { saved ->
                // rememberSaveable inputs alone do not validate restored state against its owner.
                if (saved[0] != organizationId || saved[1] != clientId) null else {
                    AttributeEditorState(organizationId, clientId).apply {
                        rows = saved[2].takeIf { it.isNotEmpty() }?.let { Json.decodeFromString<List<AttributeDraft>>(it) }
                        error = saved[3].takeIf { it.isNotEmpty() }
                        draftId = saved[4].takeIf { it.isNotEmpty() }
                    }
                }
            }
        )
    }
}

@Serializable
internal data class AttributeDraft(
    val key: String,
    val value: String,
    // JSON text distinguishes an original JSON null from a new row with no original value.
    val originalJson: String? = null,
    val id: String = UUID.randomUUID().toString()
) {
    fun toJson(): JsonElement {
        val original = originalJson?.let(Json::parseToJsonElement)
        return if (original != null && value == displayValue(original)) original else JsonPrimitive(value.trim())
    }
}

internal fun displayValue(element: JsonElement): String = (element as? JsonPrimitive)?.content ?: element.toString()
