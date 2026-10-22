package com.beauty.storage

import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.net.URLConnection

/**
 * Serves files uploaded before the switch to object storage.
 *
 * Switching `STORAGE_PROVIDER` to `s3` does not move what is already in the
 * uploads volume, so every older photo answered 404. A path missing from
 * [primary] is looked up in [legacy] and, when found there, copied into
 * [primary] so the next read takes the normal path. The copy is best-effort
 * and the local file is kept: a failed upload must not lose the only copy.
 */
class LegacyFallbackStorageService(
    private val primary: FileStorageService,
    private val legacy: LocalFileStorageService
) : FileStorageService {
    private val log = LoggerFactory.getLogger(LegacyFallbackStorageService::class.java)

    override suspend fun save(storagePath: String, file: File, contentType: String) =
        primary.save(storagePath, file, contentType)

    override suspend fun save(storagePath: String, inputStream: InputStream, contentLength: Long, contentType: String) =
        primary.save(storagePath, inputStream, contentLength, contentType)

    override suspend fun resolve(storagePath: String): StoragePayload? {
        primary.resolve(storagePath)?.let { return it }
        val local = legacy.resolve(storagePath) as? StoragePayload.LocalFile ?: return null
        try {
            local.file.inputStream().use { input ->
                primary.save(storagePath, input, local.file.length(), guessContentType(local.file))
            }
            log.info("Copied legacy attachment {} into object storage", storagePath)
        } catch (e: Exception) {
            log.warn("Could not copy legacy attachment {} into object storage", storagePath, e)
        }
        return local
    }

    override suspend fun delete(storagePath: String) {
        primary.delete(storagePath)
        legacy.delete(storagePath)
    }

    override suspend fun deleteMultiple(storagePaths: List<String>) {
        primary.deleteMultiple(storagePaths)
        legacy.deleteMultiple(storagePaths)
    }

    override fun close() = primary.close()

    private fun guessContentType(file: File): String =
        URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
}
