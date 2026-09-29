package com.beauty.storage

import com.beauty.config.S3DeliveryMode
import com.beauty.config.S3Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.io.File
import java.io.InputStream
import java.time.Duration

/**
 * Stores attachment files in Amazon S3 or S3-compatible object storage (MinIO, Cloudflare R2, etc.).
 */
class S3StorageService(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner?,
    private val settings: S3Settings
) : FileStorageService {
    private val log = LoggerFactory.getLogger(S3StorageService::class.java)

    companion object {
        private const val PREFIX = "/uploads/"

        /**
         * Validates and extracts a canonical S3 object key from internal storage path (e.g. `/uploads/file.jpg`).
         * Rejects path traversal or invalid prefix to prevent writing or reading arbitrary keys.
         */
        fun toS3Key(storagePath: String): String? {
            val normalized = if (storagePath.startsWith("/")) storagePath else "/$storagePath"
            if (!normalized.startsWith(PREFIX)) return null
            val name = normalized.removePrefix(PREFIX)
            if (name.isBlank() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
            return "uploads/$name"
        }
    }

    override suspend fun save(storagePath: String, file: File, contentType: String): Unit = withContext(Dispatchers.IO) {
        val key = toS3Key(storagePath)
            ?: throw IllegalArgumentException("Invalid storage path: $storagePath")
        val request = PutObjectRequest.builder()
            .bucket(settings.bucket)
            .key(key)
            .contentType(contentType)
            .contentLength(file.length())
            .build()
        s3Client.putObject(request, RequestBody.fromFile(file))
    }

    override suspend fun save(
        storagePath: String,
        inputStream: InputStream,
        contentLength: Long,
        contentType: String
    ): Unit = withContext(Dispatchers.IO) {
        val key = toS3Key(storagePath)
            ?: throw IllegalArgumentException("Invalid storage path: $storagePath")
        val request = PutObjectRequest.builder()
            .bucket(settings.bucket)
            .key(key)
            .contentType(contentType)
            .contentLength(contentLength)
            .build()
        s3Client.putObject(request, RequestBody.fromInputStream(inputStream, contentLength))
    }

    override suspend fun resolve(storagePath: String): StoragePayload? = withContext(Dispatchers.IO) {
        val key = toS3Key(storagePath) ?: return@withContext null

        val head = try {
            s3Client.headObject(
                HeadObjectRequest.builder()
                    .bucket(settings.bucket)
                    .key(key)
                    .build()
            )
        } catch (e: NoSuchKeyException) {
            return@withContext null
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) return@withContext null
            log.error("Failed to check S3 object existence for key: {}", key, e)
            throw e
        }

        when (settings.deliveryMode) {
            S3DeliveryMode.REDIRECT -> {
                val presigner = s3Presigner
                    ?: throw IllegalStateException("S3Presigner is required when S3DeliveryMode is REDIRECT")
                val getRequest = GetObjectRequest.builder()
                    .bucket(settings.bucket)
                    .key(key)
                    .build()
                val presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(settings.presignedUrlMinutes))
                    .getObjectRequest(getRequest)
                    .build()
                val presignedUrl = presigner.presignGetObject(presignRequest).url().toString()
                StoragePayload.Redirect(presignedUrl)
            }
            S3DeliveryMode.STREAM -> {
                val getRequest = GetObjectRequest.builder()
                    .bucket(settings.bucket)
                    .key(key)
                    .build()
                val responseInputStream = s3Client.getObject(getRequest)
                StoragePayload.Stream(
                    inputStream = responseInputStream,
                    contentType = head.contentType(),
                    contentLength = head.contentLength()
                )
            }
        }
    }

    override suspend fun delete(storagePath: String): Unit = withContext(Dispatchers.IO) {
        val key = toS3Key(storagePath) ?: return@withContext
        try {
            s3Client.deleteObject(
                DeleteObjectRequest.builder()
                    .bucket(settings.bucket)
                    .key(key)
                    .build()
            )
        } catch (e: Exception) {
            log.error("Failed to delete S3 object: {}", key, e)
        }
    }

    override suspend fun deleteMultiple(storagePaths: List<String>): Unit = withContext(Dispatchers.IO) {
        val keys = storagePaths.mapNotNull { toS3Key(it) }
        if (keys.isEmpty()) return@withContext
        try {
            val toDelete = keys.map { ObjectIdentifier.builder().key(it).build() }
            s3Client.deleteObjects(
                DeleteObjectsRequest.builder()
                    .bucket(settings.bucket)
                    .delete(Delete.builder().objects(toDelete).build())
                    .build()
            )
        } catch (e: Exception) {
            log.error("Failed to batch delete {} S3 objects", keys.size, e)
        }
    }

    override fun close() {
        runCatching { s3Presigner?.close() }
        runCatching { s3Client.close() }
    }
}
