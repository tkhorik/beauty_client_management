package com.beauty.storage

import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.*

class LegacyFallbackStorageServiceTest {

    /** Stands in for the bucket: an in-memory map of stored paths. */
    private class FakeBucket(var failSaves: Boolean = false) : FileStorageService {
        val objects = mutableMapOf<String, ByteArray>()
        override suspend fun save(storagePath: String, file: File, contentType: String) {
            objects[storagePath] = file.readBytes()
        }
        override suspend fun save(storagePath: String, inputStream: InputStream, contentLength: Long, contentType: String) {
            if (failSaves) error("bucket unavailable")
            objects[storagePath] = inputStream.readBytes()
        }
        override suspend fun resolve(storagePath: String): StoragePayload? =
            objects[storagePath]?.let { StoragePayload.Redirect("https://bucket.example$storagePath") }
        override suspend fun delete(storagePath: String) { objects.remove(storagePath) }
        override suspend fun deleteMultiple(storagePaths: List<String>) { storagePaths.forEach { objects.remove(it) } }
    }

    private lateinit var uploadDir: File
    private lateinit var bucket: FakeBucket
    private lateinit var service: LegacyFallbackStorageService

    @BeforeTest
    fun setup() {
        uploadDir = Files.createTempDirectory("legacy-uploads").toFile()
        bucket = FakeBucket()
        service = LegacyFallbackStorageService(bucket, LocalFileStorageService(uploadDir))
    }

    @AfterTest
    fun cleanup() {
        uploadDir.deleteRecursively()
    }

    @Test
    fun `object in the bucket is served from the bucket`() = runBlocking {
        bucket.objects["/uploads/new.jpg"] = byteArrayOf(1)
        assertTrue(service.resolve("/uploads/new.jpg") is StoragePayload.Redirect)
    }

    @Test
    fun `file only in the uploads volume is served and copied to the bucket`() = runBlocking {
        File(uploadDir, "old.jpg").writeBytes(byteArrayOf(4, 5, 6))

        assertTrue(service.resolve("/uploads/old.jpg") is StoragePayload.LocalFile)
        assertContentEquals(byteArrayOf(4, 5, 6), bucket.objects["/uploads/old.jpg"])
        assertTrue(File(uploadDir, "old.jpg").exists(), "the local copy is kept")

        assertTrue(service.resolve("/uploads/old.jpg") is StoragePayload.Redirect)
    }

    @Test
    fun `failed copy still serves the local file`() = runBlocking {
        bucket.failSaves = true
        File(uploadDir, "old.jpg").writeBytes(byteArrayOf(7))

        assertTrue(service.resolve("/uploads/old.jpg") is StoragePayload.LocalFile)
        assertTrue(bucket.objects.isEmpty())
    }

    @Test
    fun `missing everywhere resolves to null`() = runBlocking {
        assertNull(service.resolve("/uploads/gone.jpg"))
    }

    @Test
    fun `delete removes both copies`() = runBlocking {
        File(uploadDir, "old.jpg").writeBytes(byteArrayOf(1))
        bucket.objects["/uploads/old.jpg"] = byteArrayOf(1)

        service.delete("/uploads/old.jpg")

        assertFalse(File(uploadDir, "old.jpg").exists())
        assertTrue(bucket.objects.isEmpty())
    }
}
