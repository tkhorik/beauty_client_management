package com.beauty.app.ui.client

import kotlinx.serialization.Serializable

/** A new client has no inferred tags or personal/medical attributes. */
@Serializable
internal data class ClientEditorDraft(
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val tags: List<String> = emptyList(),
    val tagInput: String = "",
    val customFields: List<EditClientViewModel.CustomField> = emptyList()
) {
    fun withPendingTag(): ClientEditorDraft {
        val tag = tagInput.trim()
        return copy(tags = if (tag.isEmpty() || tag in tags) tags else tags + tag, tagInput = "")
    }
}

@Serializable
internal data class ClientEditorSnapshot(
    val organizationId: String,
    val clientId: String?,
    val baseline: ClientEditorDraft,
    val draft: ClientEditorDraft
)
