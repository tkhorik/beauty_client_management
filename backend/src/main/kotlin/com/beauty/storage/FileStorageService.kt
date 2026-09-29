package com.beauty.storage

import java.io.Closeable
import java.io.File
import java.io.InputStream

sealed interface StoragePayload {
    data class LocalFile(val file: File) : StoragePayload
    data class Redirect(val url: String) : StoragePayload
    data class Stream(
        val inputStream: InputStream,
        val contentType: String? = null,
        val contentLength: Long? = null
    ) : StoragePayload
}

/**
 * Common abstraction for attachment file storage (Local disk vs AWS S3 / MinIO / R2).
 */
interface FileStorageService : Closeable {
    /**
     * Stores a file on the underlying storage.
     * [storagePath] is the internal path (e.g. `/uploads/<uuid>_<filename>`).
     */
    suspend fun save(
        storagePath: String,
        file: File,
        contentType: String
    )

    /**
     * Stores a stream on the underlying storage.
     */
    suspend fun save(
        storagePath: String,
        inputStream: InputStream,
        contentLength: Long,
        contentType: String
    )

    /**
     * Resolves the stored file at [storagePath].
     * Returns null if the file does not exist or the path is invalid.
     */
    suspend fun resolve(storagePath: String): StoragePayload?

    /**
     * Deletes the stored file at [storagePath].
     */
    suspend fun delete(storagePath: String)

    /**
     * Deletes multiple stored files at [storagePaths].
     */
    suspend fun deleteMultiple(storagePaths: List<String>)

    override fun close() {}
}
