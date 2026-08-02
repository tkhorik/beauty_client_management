package com.beauty.app.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class KtorBeautyApiTest {
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
}
