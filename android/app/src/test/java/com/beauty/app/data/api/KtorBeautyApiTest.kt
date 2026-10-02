package com.beauty.app.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.expectSuccess
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorBeautyApiTest {
    @Test
    fun `verification uses public POST body and never a query credential`() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/auth/verify-email", request.url.encodedPath)
            assertEquals("", request.url.encodedQuery)
            val body = request.body as io.ktor.http.content.TextContent
            assertEquals("{\"token\":\"synthetic-token\"}", body.text)
            respond("{\"verified\":true}", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        KtorBeautyApi(client).verifyEmail("synthetic-token")
        client.close()
    }

    @Test
    fun `deserializes client directory response`() = runTest {
        val engine = MockEngine {
            respond(
                "[{\"id\":\"c1\",\"name\":\"Ada\",\"phone\":\"+100\",\"createdAt\":\"now\",\"updatedAt\":\"now\"}]",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val clients = KtorBeautyApi(client).getClients("org-a")

        assertEquals("c1", clients.single().id)
        assertEquals("Ada", clients.single().name)
    }

    /**
     * The header is what scopes the request. Without it the backend answers
     * `MISSING_ORGANIZATION`, and with the wrong one it answers another salon's
     * records — so this asserts on the wire format rather than trusting that
     * the parameter is used somewhere.
     */
    @Test
    fun `scoped calls send the organization header`() = runTest {
        var seenHeader: String? = null
        val engine = MockEngine { request ->
            seenHeader = request.headers[ORG_HEADER]
            respond(
                "[]",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        KtorBeautyApi(client).getClients("org-b")

        assertEquals("org-b", seenHeader)
    }

    @Test
    fun `reads every paginated history page scoped to the requested client and organization`() = runTest {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val firstPage = List(100) { index ->
            """{"id":"v-$index","clientId":"client-a","visitDateTime":"2026-09-01T10:00:00","durationMinutes":30,"procedureNotes":"notes","status":"COMPLETED"}"""
        }.joinToString(prefix = "[", postfix = "]")
        val finalPage = """[{"id":"v-100","clientId":"client-a","visitDateTime":"2026-09-02T11:00:00","durationMinutes":45,"procedureNotes":"full decode","status":"SCHEDULED","attachments":[{"id":"a-1","visitId":"v-100","fileUrl":"/api/attachments/a-1","fileType":"image/jpeg","fileSize":1234,"caption":"Before","tag":"BEFORE","uploadedAt":"2026-09-02T11:05:00"}]}]"""
        val engine = MockEngine { request ->
            requests += request
            respond(
                if (requests.size == 1) firstPage else finalPage,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val visits = KtorBeautyApi(client).getVisitsForClient("org-history", "client-a")

        assertEquals(101, visits.size)
        assertEquals("full decode", visits.last().procedureNotes)
        assertEquals("a-1", visits.last().attachments.single().id)
        assertEquals("BEFORE", visits.last().attachments.single().tag)
        assertEquals(2, requests.size)
        requests.forEachIndexed { index, request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/visits", request.url.encodedPath)
            assertEquals("org-history", request.headers[ORG_HEADER])
            assertEquals("client-a", request.url.parameters["clientId"])
            assertEquals("100", request.url.parameters["limit"])
            assertEquals((index * 100).toString(), request.url.parameters["offset"])
        }
    }

    @Test
    fun `history request propagates a later pagination failure`() = runTest {
        var requestCount = 0
        val fullPage = List(100) {
            """{"id":"v-$it","clientId":"client-a","visitDateTime":"2026-09-01T10:00:00","durationMinutes":30,"procedureNotes":"notes","status":"COMPLETED"}"""
        }.joinToString(prefix = "[", postfix = "]")
        val engine = MockEngine {
            requestCount++
            respond(
                if (requestCount == 1) fullPage else "server unavailable",
                if (requestCount == 1) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val failure = runCatching { KtorBeautyApi(client).getVisitsForClient("org-history", "client-a") }

        assertTrue(failure.isFailure)
        assertEquals(2, requestCount)
    }

    /**
     * The sync worker's decision to stop retrying rests entirely on this
     * classification. Getting it wrong in one direction retries a refusal
     * forever; in the other, it abandons the offline queue over a transient
     * server error.
     */
    @Test
    fun `an unverified-email refusal is told apart from other 403s`() = runTest {
        assertEquals(true, forbiddenWith("""{"error":"Confirm your email","code":"EMAIL_NOT_VERIFIED"}"""))
        assertEquals(false, forbiddenWith("""{"error":"Not a member","code":"NOT_A_MEMBER"}"""))
        assertEquals(false, forbiddenWith("""{"error":"Admin required","code":"ADMIN_REQUIRED"}"""))
    }

    /** Runs a request that fails with 403 and [body], and classifies the error. */
    private suspend fun forbiddenWith(body: String): Boolean {
        val engine = MockEngine {
            respond(
                body,
                HttpStatusCode.Forbidden,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        return try {
            KtorBeautyApi(client).createVisit(
                "org-a",
                CreateVisitRequest("c1", "2026-08-13T10:00:00", 60, "notes", "COMPLETED")
            )
            false
        } catch (error: Exception) {
            error.isEmailNotVerified()
        }
    }

    /**
     * The backend refuses creation without a redeemable token, so a request
     * body that drops it fails for every user — which is how the Android
     * create flow used to behave.
     */
    @Test
    fun `organization creation sends the creation token and validation reads the verdict`() = runTest {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            val body = if (request.method == HttpMethod.Get) {
                """{"valid":true}"""
            } else {
                """{"id":"o1","name":"Aura","slug":"aura","role":"ORG_ADMIN","status":"ACTIVE"}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val api = KtorBeautyApi(client)

        assertTrue(api.validateCreationToken("tok-1"))
        api.createOrganization(CreateOrganizationRequest("Aura", "aura", "tok-1"))

        assertEquals("/api/organizations/creation-tokens/validate", requests[0].url.encodedPath)
        assertEquals("tok-1", requests[0].url.parameters["token"])
        val sent = (requests[1].body as io.ktor.http.content.TextContent).text
        assertTrue(sent, sent.contains("\"creationToken\":\"tok-1\""))
    }
}
