package com.beauty.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

/**
 * The application's Json keeps `encodeDefaults = false`, so any response field
 * whose value equals its default disappears from the payload unless it is
 * marked `@EncodeDefault`. Clients index into these fields unconditionally.
 */
class DtoDefaultsEncodingTest {
    private fun keys(json: String): Set<String> = Json.parseToJsonElement(json).jsonObject.keys

    @Test
    fun `client with no tags, fields or visits still sends them`() {
        val dto = ClientDto(id = "c1", name = "A", phone = "1", createdAt = "t", updatedAt = "t")
        val obj = Json.parseToJsonElement(Json.encodeToString(dto)).jsonObject
        assertEquals("[]", obj["tags"].toString())
        assertEquals(JsonObject(emptyMap()), obj["customFields"])
        assertEquals("0", obj["totalVisits"].toString())
    }

    @Test
    fun `visit and attachment defaults are sent`() {
        val visit = VisitDto("v1", "c1", "t", 30, "", "COMPLETED", createdAt = "t")
        assertTrue("attachments" in keys(Json.encodeToString(visit)))
        val attachment = AttachmentDto("a1", "v1", "u", "image/png", 1, uploadedAt = "t")
        assertTrue("tag" in keys(Json.encodeToString(attachment)))
    }

    @Test
    fun `user and validation defaults are sent`() {
        val user = UserDto(id = "u1", email = "e", fullName = "n", createdAt = "t")
        assertTrue("globalRole" in keys(Json.encodeToString(user)))
        val error = ValidationErrorResponse(errors = emptyMap())
        assertTrue(keys(Json.encodeToString(error)).containsAll(setOf("error", "fieldErrors")))
        assertTrue("args" in keys(Json.encodeToString(FieldError("X"))))
    }
}
