package com.beauty.app.updater

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GithubUpdateServiceTest {

    @Test
    fun `fetchLatestRelease parses release DTO and finds APK asset`() = runTest {
        val sampleJson = """
            {
              "tag_name": "v1.4.4",
              "name": "Aura Beauty Log v1.4.4",
              "body": "- Fixed client sync\n- Added appointment filters",
              "published_at": "2026-10-01T22:10:55Z",
              "html_url": "https://github.com/tkhorik/beauty_client_management/releases/tag/v1.4.4",
              "assets": [
                {
                  "name": "source.zip",
                  "size": 1024,
                  "browser_download_url": "https://example.com/source.zip"
                },
                {
                  "name": "aura-beauty-log-v1.4.4.apk",
                  "size": 13612539,
                  "browser_download_url": "https://github.com/tkhorik/beauty_client_management/releases/download/v1.4.4/aura-beauty-log-v1.4.4.apk"
                }
              ]
            }
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            assertEquals("https://api.github.com/repos/tkhorik/beauty_client_management/releases/latest", request.url.toString())
            respond(
                content = sampleJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val service = GithubUpdateService(client = client)
        val result = service.fetchLatestRelease()

        assertTrue(result.isSuccess)
        val release = result.getOrNull()
        assertNotNull(release)
        assertEquals("v1.4.4", release?.tagName)
        assertEquals("1.4.4", release?.versionName)
        assertEquals("Aura Beauty Log v1.4.4", release?.title)
        assertEquals(13612539L, release?.apkSize)
        assertTrue(release?.apkUrl?.endsWith(".apk") == true)
    }

    @Test
    fun `fetchLatestRelease returns null if release has no APK asset`() = runTest {
        val sampleJson = """
            {
              "tag_name": "v1.4.4",
              "name": "Aura Beauty Log v1.4.4",
              "assets": []
            }
        """.trimIndent()

        val mockEngine = MockEngine {
            respond(content = sampleJson, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val service = GithubUpdateService(client = client)
        val result = service.fetchLatestRelease()

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun `downloadApk streams content and reports progress`() = runTest {
        val testBytes = ByteArray(1024) { (it % 256).toByte() }

        val mockEngine = MockEngine {
            respond(
                content = testBytes,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/vnd.android.package-archive"),
                    HttpHeaders.ContentLength to listOf(testBytes.size.toString())
                )
            )
        }

        val client = HttpClient(mockEngine)
        val service = GithubUpdateService(client = client)

        val tempFile = File.createTempFile("test-update", ".apk").apply { deleteOnExit() }
        var reportedProgress = 0L

        val result = service.downloadApk("https://example.com/update.apk", tempFile) { downloaded, _ ->
            reportedProgress = downloaded
        }

        assertTrue(result.isSuccess)
        assertEquals(testBytes.size.toLong(), tempFile.length())
        assertEquals(testBytes.size.toLong(), reportedProgress)
    }
}
