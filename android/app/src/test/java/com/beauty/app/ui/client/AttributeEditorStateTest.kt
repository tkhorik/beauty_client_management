package com.beauty.app.ui.client

import androidx.compose.runtime.saveable.SaverScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AttributeEditorStateTest {
    private val source = """{"number":12,"boolean":true,"null":null,"object":{"x":1},"array":[1,false],"string":"12"}"""

    @Test fun `restoration keeps edited added removed rows and stable ids with original JSON types`() {
        val state = AttributeEditorState("org", "client")
        state.start(source)
        val original = state.rows!!
        state.update(0, original[0].copy(key = "renamed number"))
        state.remove(5)
        state.add()
        state.update(5, state.rows!![5].copy(key = "new", value = "  typed  "))
        state.error = "DUPLICATE_FIELD:new"

        val saver = AttributeEditorState.saver("org", "client")
        val saved = with(saver) { SaverScope { true }.save(state) }!!
        val restored = saver.restore(saved)!!
        assertEquals(state.rows, restored.rows)
        assertEquals(state.error, restored.error)
        val json = Json.parseToJsonElement(source).jsonObject
        restored.rows!!.take(5).forEachIndexed { index, row ->
            assertEquals(original[index].id, row.id)
            assertEquals(json[original[index].key], row.toJson())
        }
        assertEquals(JsonPrimitive("typed"), restored.rows!!.last().toJson())
        val beforeAdd = restored.rows!!.map { it.id }
        restored.add()
        assertFalse(restored.rows!!.last().id in beforeAdd)
        assertEquals(JsonPrimitive("false"), restored.rows!![1].copy(value = "false").toJson())
    }

    @Test fun `refresh does not replace draft and only explicit clear seeds from latest record`() {
        val state = AttributeEditorState("org", "client")
        state.start(source)
        state.update(0, state.rows!![0].copy(value = "unsaved"))
        val saver = AttributeEditorState.saver("org", "client")
        val restored = saver.restore(with(saver) { SaverScope { true }.save(state) }!!)!!
        restored.start("""{"fresh":true}""")
        assertEquals("unsaved", restored.rows!![0].value)
        restored.clear()
        val cleared = saver.restore(with(saver) { SaverScope { true }.save(restored) }!!)!!
        assertNull(cleared.rows)
        cleared.start("""{"fresh":true}""")
        assertEquals(listOf("fresh"), cleared.rows!!.map { it.key })
    }

    @Test fun `empty editing draft restores as editing and never leaks to another client or organization`() {
        val state = AttributeEditorState("org", "client")
        state.start("{}")
        val saver = AttributeEditorState.saver("org", "client")
        val saved = with(saver) { SaverScope { true }.save(state) }!!
        assertEquals(emptyList<AttributeDraft>(), saver.restore(saved)!!.rows)
        assertNull(AttributeEditorState.saver("other", "client").restore(saved))
        assertNull(AttributeEditorState.saver("org", "other").restore(saved))
    }

    @Test fun `successful save clears a recreated draft but not a failed save or a newer edit session`() {
        val original = AttributeEditorState("org", "client")
        original.start(source)
        val submittedDraftId = original.draftId!!
        val saver = AttributeEditorState.saver("org", "client")
        val restored = saver.restore(with(saver) { SaverScope { true }.save(original) }!!)!!
        restored.clearIfSaved(null)
        assertNotNull(restored.rows)
        restored.clearIfSaved(submittedDraftId)
        assertNull(restored.rows)
        restored.start(source)
        restored.clearIfSaved(submittedDraftId)
        assertNotNull(restored.rows)
        assertNotEquals(submittedDraftId, restored.draftId)
    }
}
