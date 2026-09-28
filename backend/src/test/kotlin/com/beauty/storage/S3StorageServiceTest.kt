package com.beauty.storage

import com.beauty.config.S3DeliveryMode
import com.beauty.config.S3Settings
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.io.ByteArrayInputStream
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.*

class S3StorageServiceTest {

    @Test
    fun `toS3Key validates prefixes and prevents path traversal`() {
        assertEquals("uploads/photo_123.jpg", S3StorageService.toS3Key("/uploads/photo_123.jpg"))
        assertEquals("uploads/photo_123.jpg", S3StorageService.toS3Key("uploads/photo_123.jpg"))

        assertNull(S3StorageService.toS3Key("/uploads/../escape.jpg"))
        assertNull(S3StorageService.toS3Key("/uploads/nested/dir/photo.jpg"))
        assertNull(S3StorageService.toS3Key("/uploads/nested\\dir\\photo.jpg"))
        assertNull(S3StorageService.toS3Key("/other/path.jpg"))
        assertNull(S3StorageService.toS3Key("/uploads/"))
        assertNull(S3StorageService.toS3Key(""))
    }

    @Test
    fun `save sends putObject request to s3Client`() = kotlinx.coroutines.runBlocking {
        var putObjectCalled = false
        var capturedBucket: String? = null
        var capturedKey: String? = null
        var capturedContentType: String? = null

        val clientProxy = Proxy.newProxyInstance(
            S3Client::class.java.classLoader,
            arrayOf(S3Client::class.java)
        ) { _, method: Method, args: Array<Any?>? ->
            if (method.name == "putObject" && args != null && args.size == 2) {
                val req = args[0] as PutObjectRequest
                putObjectCalled = true
                capturedBucket = req.bucket()
                capturedKey = req.key()
                capturedContentType = req.contentType()
                PutObjectResponse.builder().build()
            } else if (method.name == "serviceName") {
                "s3"
            } else if (method.name == "close") {
                null
            } else {
                null
            }
        } as S3Client

        val settings = S3Settings(
            bucket = "test-bucket",
            region = "us-east-1",
            endpoint = null,
            accessKey = null,
            secretKey = null,
            pathStyleAccess = false,
            deliveryMode = S3DeliveryMode.REDIRECT,
            presignedUrlMinutes = 15
        )

        val service = S3StorageService(clientProxy, null, settings)
        val data = "test s3 payload".toByteArray()
        service.save("/uploads/sample.png", ByteArrayInputStream(data), data.size.toLong(), "image/png")

        assertTrue(putObjectCalled)
        assertEquals("test-bucket", capturedBucket)
        assertEquals("uploads/sample.png", capturedKey)
        assertEquals("image/png", capturedContentType)
    }

    @Test
    fun `resolve in REDIRECT mode returns presigned URL`() = kotlinx.coroutines.runBlocking {
        val clientProxy = Proxy.newProxyInstance(
            S3Client::class.java.classLoader,
            arrayOf(S3Client::class.java)
        ) { _, method: Method, _ ->
            if (method.name == "headObject") {
                HeadObjectResponse.builder()
                    .contentType("image/jpeg")
                    .contentLength(1024L)
                    .build()
            } else if (method.name == "serviceName") {
                "s3"
            } else {
                null
            }
        } as S3Client

        // Presigning is a local computation, so a real presigner with dummy
        // credentials exercises the actual URL the redirect would carry.
        val presigner = S3Presigner.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
            .build()

        val settings = S3Settings(
            bucket = "test-bucket",
            region = "us-east-1",
            endpoint = null,
            accessKey = null,
            secretKey = null,
            pathStyleAccess = false,
            deliveryMode = S3DeliveryMode.REDIRECT,
            presignedUrlMinutes = 10
        )

        val service = S3StorageService(clientProxy, presigner, settings)
        val result = service.resolve("/uploads/photo.jpg")

        assertNotNull(result)
        assertTrue(result is StoragePayload.Redirect)
        assertTrue(result.url.startsWith("https://test-bucket.s3.amazonaws.com/uploads/photo.jpg?"), result.url)
        assertTrue("X-Amz-Expires=600" in result.url, result.url)
        assertTrue("X-Amz-Signature=" in result.url, result.url)
    }

    @Test
    fun `resolve in STREAM mode returns input stream from getObject`() = kotlinx.coroutines.runBlocking {
        val streamContent = "streaming bytes"
        val clientProxy = Proxy.newProxyInstance(
            S3Client::class.java.classLoader,
            arrayOf(S3Client::class.java)
        ) { _, method: Method, _ ->
            when (method.name) {
                "headObject" -> HeadObjectResponse.builder()
                    .contentType("text/plain")
                    .contentLength(streamContent.length.toLong())
                    .build()
                "getObject" -> {
                    val resp = GetObjectResponse.builder()
                        .contentType("text/plain")
                        .contentLength(streamContent.length.toLong())
                        .build()
                    val abortable = AbortableInputStream.create(ByteArrayInputStream(streamContent.toByteArray()))
                    ResponseInputStream(resp, abortable)
                }
                "serviceName" -> "s3"
                else -> null
            }
        } as S3Client

        val settings = S3Settings(
            bucket = "test-bucket",
            region = "us-east-1",
            endpoint = null,
            accessKey = null,
            secretKey = null,
            pathStyleAccess = false,
            deliveryMode = S3DeliveryMode.STREAM,
            presignedUrlMinutes = 10
        )

        val service = S3StorageService(clientProxy, null, settings)
        val result = service.resolve("/uploads/stream.txt")

        assertNotNull(result)
        assertTrue(result is StoragePayload.Stream)
        assertEquals("text/plain", result.contentType)
        assertEquals(streamContent, result.inputStream.reader().readText())
    }

    @Test
    fun `resolve returns null when object does not exist in S3`() = kotlinx.coroutines.runBlocking {
        val clientProxy = Proxy.newProxyInstance(
            S3Client::class.java.classLoader,
            arrayOf(S3Client::class.java)
        ) { _, method: Method, _ ->
            if (method.name == "headObject") {
                throw NoSuchKeyException.builder().message("Object not found").build()
            } else if (method.name == "serviceName") {
                "s3"
            } else {
                null
            }
        } as S3Client

        val settings = S3Settings(
            bucket = "test-bucket",
            region = "us-east-1",
            endpoint = null,
            accessKey = null,
            secretKey = null,
            pathStyleAccess = false,
            deliveryMode = S3DeliveryMode.REDIRECT,
            presignedUrlMinutes = 10
        )

        val service = S3StorageService(clientProxy, null, settings)
        assertNull(service.resolve("/uploads/missing.jpg"))
    }

    @Test
    fun `delete invokes deleteObject`() = kotlinx.coroutines.runBlocking {
        var deletedKey: String? = null
        val clientProxy = Proxy.newProxyInstance(
            S3Client::class.java.classLoader,
            arrayOf(S3Client::class.java)
        ) { _, method: Method, args: Array<Any?>? ->
            if (method.name == "deleteObject" && args != null && args.isNotEmpty()) {
                val req = args[0] as DeleteObjectRequest
                deletedKey = req.key()
                DeleteObjectResponse.builder().build()
            } else if (method.name == "serviceName") {
                "s3"
            } else {
                null
            }
        } as S3Client

        val settings = S3Settings(
            bucket = "test-bucket",
            region = "us-east-1",
            endpoint = null,
            accessKey = null,
            secretKey = null,
            pathStyleAccess = false,
            deliveryMode = S3DeliveryMode.REDIRECT,
            presignedUrlMinutes = 10
        )

        val service = S3StorageService(clientProxy, null, settings)
        service.delete("/uploads/to-remove.jpg")
        assertEquals("uploads/to-remove.jpg", deletedKey)
    }

    @Test
    fun `deleteMultiple invokes deleteObjects with batch keys`() = kotlinx.coroutines.runBlocking {
        var deletedKeys: List<String>? = null
        val clientProxy = Proxy.newProxyInstance(
            S3Client::class.java.classLoader,
            arrayOf(S3Client::class.java)
        ) { _, method: Method, args: Array<Any?>? ->
            if (method.name == "deleteObjects" && args != null && args.isNotEmpty()) {
                val req = args[0] as DeleteObjectsRequest
                deletedKeys = req.delete().objects().map { it.key() }
                DeleteObjectsResponse.builder().build()
            } else if (method.name == "serviceName") {
                "s3"
            } else {
                null
            }
        } as S3Client

        val settings = S3Settings(
            bucket = "test-bucket",
            region = "us-east-1",
            endpoint = null,
            accessKey = null,
            secretKey = null,
            pathStyleAccess = false,
            deliveryMode = S3DeliveryMode.REDIRECT,
            presignedUrlMinutes = 10
        )

        val service = S3StorageService(clientProxy, null, settings)
        service.deleteMultiple(listOf("/uploads/1.jpg", "/uploads/2.jpg"))
        assertEquals(listOf("uploads/1.jpg", "uploads/2.jpg"), deletedKeys)
    }
}
