package com.beauty.routes

import com.beauty.module
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.*

class LanguagePreferenceTest {
    private fun ApplicationTestBuilder.startApp() {
        environment {
            config = MapApplicationConfig(
                "app.environment" to "development",
                "app.uploadDir" to "build/test-uploads",
                "db.driver" to "org.h2.Driver",
                "db.url" to "jdbc:h2:mem:language-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "db.user" to "sa", "db.password" to ""
            )
        }
        application { module() }
    }
    private suspend fun ApplicationTestBuilder.register(email: String, preference: String? = null): JsonObject {
        val response = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("email", email); put("password", "test-password-long"); put("fullName", "Test")
                preference?.let { put("languagePreference", it) }
            }.toString())
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }
    private suspend fun ApplicationTestBuilder.update(token: String, preference: String, revision: Long) =
        client.put("/api/users/me/language") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"preference":"$preference","expectedRevision":$revision}""")
        }

    @Test fun `preference defaults are on wire and edits are scoped revision checked and retry safe`() = testApplication {
        startApp()
        val a = register("a@example.test")
        val b = register("b@example.test", "en")
        assertEquals("system", a["user"]!!.jsonObject["languagePreference"]!!.jsonPrimitive.content)
        assertEquals(0, a["user"]!!.jsonObject["languageRevision"]!!.jsonPrimitive.long)
        val token = a["token"]!!.jsonPrimitive.content
        val changed = update(token, "ru", 0)
        assertEquals(HttpStatusCode.OK, changed.status)
        assertEquals(1, Json.parseToJsonElement(changed.bodyAsText()).jsonObject["revision"]!!.jsonPrimitive.long)
        val retry = update(token, "ru", 0)
        assertEquals(HttpStatusCode.OK, retry.status)
        val stale = update(token, "en", 0)
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("ru", Json.parseToJsonElement(stale.bodyAsText()).jsonObject["preference"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, update(token, "system", 1).status)
        assertEquals(HttpStatusCode.BadRequest, update(token, "de", 2).status)
        assertEquals(HttpStatusCode.BadRequest, update(token, "en", -1).status)
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/users/me/language") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"preference":"ru"}""")
        }.status)
        val accountMismatch = client.put("/api/users/me/language") {
            bearerAuth(b["token"]!!.jsonPrimitive.content); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("preference", "ru"); put("expectedRevision", 0)
                put("expectedAccountId", a["user"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            }.toString())
        }
        assertEquals(HttpStatusCode.Forbidden, accountMismatch.status)
        assertEquals("ACCOUNT_CHANGED", Json.parseToJsonElement(accountMismatch.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
        val other = client.get("/api/users/me") { bearerAuth(b["token"]!!.jsonPrimitive.content) }
        assertEquals("en", Json.parseToJsonElement(other.bodyAsText()).jsonObject["languagePreference"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/users/me/language") {
            contentType(ContentType.Application.Json); setBody("""{"preference":"ru","expectedRevision":0}""")
        }.status)
    }

    @Test fun `registration and login preserve explicit preference without requiring an organization`() = testApplication {
        startApp()
        val registered = register("ru@example.test", "ru")
        val user = registered["user"]!!.jsonObject
        assertEquals("ru", user["languagePreference"]!!.jsonPrimitive.content)
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"ru@example.test","password":"test-password-long"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        assertEquals("ru", Json.parseToJsonElement(login.bodyAsText()).jsonObject["user"]!!.jsonObject["languagePreference"]!!.jsonPrimitive.content)
    }
}
