package com.beauty.routes

import com.beauty.auth.OrgCreationTokenService
import com.beauty.module
import com.beauty.plugins.ORG_HEADER
import io.ktor.client.request.*
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Regression tests for authenticated, tenant-scoped attachment delivery. */
class AttachmentRouteSecurityTest {
    private fun ApplicationTestBuilder.startApp() {
        environment {
            config = MapApplicationConfig(
                "app.environment" to "development",
                "app.uploadDir" to "build/test-uploads",
                "db.driver" to "org.h2.Driver",
                "db.url" to "jdbc:h2:mem:attachments-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "db.user" to "sa",
                "db.password" to ""
            )
        }
        application { module() }
    }

    private suspend fun ApplicationTestBuilder.register(email: String): String {
        val response = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"a-long-enough-password","fullName":"Test User"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.createOrg(token: String, slug: String): String {
        val me = client.get("/api/users/me") { bearerAuth(token) }
        val userId = Json.parseToJsonElement(me.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val (_, creationToken) = OrgCreationTokenService().issue(
            createdBy = userId,
            label = null,
            maxUses = 1,
            expiresAt = LocalDateTime.now().plusDays(1)
        )
        val response = client.post("/api/organizations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$slug","slug":"$slug","creationToken":"$creationToken"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.createVisit(token: String, orgId: String): String {
        val clientResponse = client.post("/api/clients") {
            bearerAuth(token)
            header(ORG_HEADER, orgId)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Attachment Client","phone":"+1 555 0100"}""")
        }
        val clientId = Json.parseToJsonElement(clientResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val visitResponse = client.post("/api/visits") {
            bearerAuth(token)
            header(ORG_HEADER, orgId)
            contentType(ContentType.Application.Json)
            setBody("""{"clientId":"$clientId","visitDateTime":"2026-08-21T10:00:00","durationMinutes":30,"procedureNotes":"test"}""")
        }
        assertEquals(HttpStatusCode.Created, visitResponse.status)
        return Json.parseToJsonElement(visitResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `attachment bytes require authentication and the owning organization`() = testApplication {
        startApp()
        val alice = register("alice-attachment@example.com")
        val bob = register("bob-attachment@example.com")
        val orgA = createOrg(alice, "attachment-org-a")
        val orgB = createOrg(bob, "attachment-org-b")
        val visitId = createVisit(alice, orgA)

        val uploaded = client.post("/api/attachments/upload") {
            bearerAuth(alice)
            header(ORG_HEADER, orgA)
            setBody(MultiPartFormDataContent(formData {
                append("visitId", visitId)
                append("file", "private photo".encodeToByteArray(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"photo.jpg\"")
                    append(HttpHeaders.ContentType, "image/jpeg")
                })
            }))
        }
        assertEquals(HttpStatusCode.Created, uploaded.status, uploaded.bodyAsText())
        val attachment = Json.parseToJsonElement(uploaded.bodyAsText()).jsonObject
        val attachmentId = attachment["id"]!!.jsonPrimitive.content
        val fileUrl = attachment["fileUrl"]!!.jsonPrimitive.content
        assertEquals("/api/attachments/$attachmentId/file", fileUrl)

        assertEquals(HttpStatusCode.Unauthorized, client.get(fileUrl).status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get(fileUrl) { bearerAuth(bob); header(ORG_HEADER, orgB) }.status
        )
        val ownerDownload = client.get(fileUrl) { bearerAuth(alice); header(ORG_HEADER, orgA) }
        assertEquals(HttpStatusCode.OK, ownerDownload.status)
        assertEquals("private photo", ownerDownload.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/uploads/anything.jpg").status)

        assertEquals(
            HttpStatusCode.OK,
            client.delete("/api/attachments/$attachmentId") { bearerAuth(alice); header(ORG_HEADER, orgA) }.status
        )
        assertEquals(HttpStatusCode.NotFound, client.get(fileUrl) { bearerAuth(alice); header(ORG_HEADER, orgA) }.status)
        assertTrue(File("build/test-uploads").listFiles().orEmpty().none { it.name.startsWith("${attachmentId}_") })
    }
}
