package com.beauty.storage

import com.beauty.routes.storedAttachmentFile
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream

/**
 * Stores attachment files on the local filesystem / mounted volume under [uploadDir].
 */
class LocalFileStorageService(
    val uploadDir: File
) : FileStorageService {
    private val log = LoggerFactory.getLogger(LocalFileStorageService::class.java)

    init {
        if (!uploadDir.exists() && !uploadDir.mkdirs()) {
            log.warn("Could not create local upload directory: {}", uploadDir.absolutePath)
        }
    }

    override suspend fun save(storagePath: String, file: File, contentType: String) {
        val destFile = storedAttachmentFile(uploadDir, storagePath)
            ?: throw IllegalArgumentException("Invalid storage path: $storagePath")
        destFile.parentFile?.mkdirs()
        if (!file.renameTo(destFile)) {
            file.copyTo(destFile, overwrite = true)
            file.delete()
        }
    }

    override suspend fun save(
        storagePath: String,
        inputStream: InputStream,
        contentLength: Long,
        contentType: String
    ) {
        val destFile = storedAttachmentFile(uploadDir, storagePath)
            ?: throw IllegalArgumentException("Invalid storage path: $storagePath")
        destFile.parentFile?.mkdirs()
        destFile.outputStream().use { output ->
            inputStream.copyTo(output)
        }
    }

    override suspend fun resolve(storagePath: String): StoragePayload? {
        val file = storedAttachmentFile(uploadDir, storagePath)
        return if (file != null && file.isFile) {
            StoragePayload.LocalFile(file)
        } else {
            null
        }
    }

    override suspend fun delete(storagePath: String) {
        storedAttachmentFile(uploadDir, storagePath)?.let { file ->
            if (file.exists() && !file.delete()) {
                log.error("Could not delete attachment file: {}", file)
            }
        }
    }

    override suspend fun deleteMultiple(storagePaths: List<String>) {
        storagePaths.forEach { delete(it) }
    }
}
