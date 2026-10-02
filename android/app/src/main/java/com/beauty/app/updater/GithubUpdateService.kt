package com.beauty.app.updater

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.isEmpty
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

class GithubUpdateService(
    private val owner: String = "tkhorik",
    private val repo: String = "beauty_client_management",
    private val client: HttpClient = createClient()
) {

    /** Public list of all releases, for "release notes" and manual-download fallbacks. */
    val releasesPageUrl: String = "https://github.com/$owner/$repo/releases"

    /**
     * Checks GitHub for the latest release.
     */
    suspend fun fetchLatestRelease(): Result<AppRelease?> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
            val response = client.get(url) {
                header(HttpHeaders.Accept, "application/vnd.github.v3+json")
                header(HttpHeaders.UserAgent, "AuraBeautyApp-Android")
            }

            if (response.status == HttpStatusCode.NotFound) {
                return@runCatching null
            }
            if (response.status == HttpStatusCode.Forbidden) {
                throw IllegalStateException("GitHub rate limit reached. Please try again later.")
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("GitHub API returned ${response.status.value}")
            }

            val dto: GithubReleaseDto = response.body()
            val apkAsset = dto.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: return@runCatching null

            val cleanVersion = dto.tagName.trim().removePrefix("v").removePrefix("V")
            AppRelease(
                tagName = dto.tagName,
                versionName = cleanVersion,
                title = dto.name ?: dto.tagName,
                notes = dto.body.orEmpty().trim(),
                apkUrl = apkAsset.browserDownloadUrl,
                apkSize = apkAsset.size,
                publishedAt = dto.publishedAt.orEmpty(),
                htmlUrl = dto.htmlUrl.orEmpty()
            )
        }
    }

    /**
     * Streams APK from [downloadUrl] into [targetFile], reporting progress.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        targetFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) {
                targetFile.delete()
            }

            client.prepareGet(downloadUrl) {
                header(HttpHeaders.UserAgent, "AuraBeautyApp-Android")
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("Download failed with HTTP ${response.status.value}")
                }

                val totalLength = response.contentLength() ?: -1L
                val channel: ByteReadChannel = response.bodyAsChannel()

                FileOutputStream(targetFile).use { output ->
                    var downloaded = 0L
                    val buffer = ByteArray(16 * 1024)

                    while (!channel.isClosedForRead) {
                        val packet = channel.readRemaining(buffer.size.toLong())
                        if (packet.isEmpty) break
                        val bytes = packet.readBytes()
                        output.write(bytes)
                        downloaded += bytes.size
                        onProgress(downloaded, if (totalLength > 0) totalLength else downloaded)
                    }
                    output.flush()
                }

                targetFile
            }
        }.onFailure {
            // Clean up partial file on error/cancellation
            if (targetFile.exists()) {
                targetFile.delete()
            }
        }
    }

    companion object {
        fun createClient(): HttpClient = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 300_000 // 5 minutes for APK download
                socketTimeoutMillis = 60_000
            }
            followRedirects = true
        }
    }
}
