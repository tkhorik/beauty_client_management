package com.beauty.routes

import com.beauty.auth.OrgCreationTokenService
import com.beauty.configureApplication
import com.beauty.plugins.ORG_HEADER
import com.beauty.storage.FileStorageService
import com.beauty.storage.StoragePayload
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentS3DeliveryTest {

    class FakeS3StorageService(
        var deliveryMode: String = "redirect"
    ) : FileStorageService {
        val savedFiles = mutableMapOf<String, ByteArray>()

        override suspend fun save(storagePath: String, file: File, contentType: String) {
            savedFiles[storagePath] = file.readBytes()
        }

        override suspend fun save(
            storagePath: String,
            inputStream: InputStream,
            contentLength: Long,
            contentType: String
        ) {
            savedFiles[storagePath] = inputStream.readBytes()
        }

        override suspend fun resolve(storagePath: String): StoragePayload? {
            val bytes = savedFiles[storagePath] ?: return null
            return if (deliveryMode == "redirect") {
                StoragePayload.Redirect("https://s3.amazonaws.com/test-bucket$storagePath?signature=xyz")
            } else {
                StoragePayload.Stream(ByteArrayInputStream(bytes), "image/jpeg", bytes.size.toLong())
            }
        }

        override suspend fun delete(storagePath: String) {
            savedFiles.remove(storagePath)
        }

        override suspend fun deleteMultiple(storagePaths: List<String>) {
            storagePaths.forEach { savedFiles.remove(it) }
        }
    }

    private fun ApplicationTestBuilder.startApp(fakeStorage: FakeS3StorageService) {
        environment {
            config = MapApplicationConfig(
                "app.environment" to "development",
                "db.driver" to "org.h2.Driver",
                "db.url" to "jdbc:h2:mem:s3-attachments-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "db.user" to "sa",
                "db.password" to ""
            )
        }
        application {
            configureApplication(storageOverride = fakeStorage)
        }
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
    fun `attachment download redirects to presigned S3 url in REDIRECT delivery mode`() = testApplication {
        val fakeStorage = FakeS3StorageService(deliveryMode = "redirect")
        startApp(fakeStorage)

        val user = register("s3-user@example.com")
        val orgId = createOrg(user, "s3-org")
        val visitId = createVisit(user, orgId)

        val uploadResponse = client.post("/api/attachments/upload") {
            bearerAuth(user)
            header(ORG_HEADER, orgId)
            setBody(MultiPartFormDataContent(formData {
                append("visitId", visitId)
                append("file", "s3 file content".encodeToByteArray(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"photo.jpg\"")
                    append(HttpHeaders.ContentType, "image/jpeg")
                })
            }))
        }
        assertEquals(HttpStatusCode.Created, uploadResponse.status)
        val attachment = Json.parseToJsonElement(uploadResponse.bodyAsText()).jsonObject
        val fileUrl = attachment["fileUrl"]!!.jsonPrimitive.content

        // Using a client with followRedirects = false to observe 302 Found
        val noRedirectClient = createClient {
            followRedirects = false
        }

        val getResponse = noRedirectClient.get(fileUrl) {
            bearerAuth(user)
            header(ORG_HEADER, orgId)
        }
        assertEquals(HttpStatusCode.Found, getResponse.status)
        val location = getResponse.headers[HttpHeaders.Location]
        assertTrue(location != null && location.startsWith("https://s3.amazonaws.com/test-bucket/uploads/"))
    }

    @Test
    fun `attachment download streams bytes in STREAM delivery mode`() = testApplication {
        val fakeStorage = FakeS3StorageService(deliveryMode = "stream")
        startApp(fakeStorage)

        val user = register("s3-stream-user@example.com")
        val orgId = createOrg(user, "s3-stream-org")
        val visitId = createVisit(user, orgId)

        val uploadResponse = client.post("/api/attachments/upload") {
            bearerAuth(user)
            header(ORG_HEADER, orgId)
            setBody(MultiPartFormDataContent(formData {
                append("visitId", visitId)
                append("file", "streamed s3 bytes".encodeToByteArray(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"photo.jpg\"")
                    append(HttpHeaders.ContentType, "image/jpeg")
                })
            }))
        }
        assertEquals(HttpStatusCode.Created, uploadResponse.status)
        val attachment = Json.parseToJsonElement(uploadResponse.bodyAsText()).jsonObject
        val fileUrl = attachment["fileUrl"]!!.jsonPrimitive.content

        val getResponse = client.get(fileUrl) {
            bearerAuth(user)
            header(ORG_HEADER, orgId)
        }
        assertEquals(HttpStatusCode.OK, getResponse.status)
        assertEquals("streamed s3 bytes", getResponse.bodyAsText())
    }
}
