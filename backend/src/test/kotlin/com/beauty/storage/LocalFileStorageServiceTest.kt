package com.beauty.storage

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class LocalFileStorageServiceTest {

    private lateinit var tempFolder: File
    private lateinit var uploadDir: File
    private lateinit var service: LocalFileStorageService

    @BeforeTest
    fun setup() {
        tempFolder = Files.createTempDirectory("test-uploads").toFile()
        uploadDir = File(tempFolder, "uploads")
        uploadDir.mkdirs()
        service = LocalFileStorageService(uploadDir)
    }

    @AfterTest
    fun cleanup() {
        tempFolder.deleteRecursively()
    }

    @Test
    fun `save and resolve with File`() = kotlinx.coroutines.runBlocking {
        val tempSource = File.createTempFile("test-source", ".tmp")
        tempSource.writeText("hello local storage")

        val storagePath = "/uploads/test-file.txt"
        service.save(storagePath, tempSource, "text/plain")

        val resolved = service.resolve(storagePath)
        assertNotNull(resolved)
        assertTrue(resolved is StoragePayload.LocalFile)
        assertEquals("hello local storage", resolved.file.readText())
    }

    @Test
    fun `save and resolve with InputStream`() = kotlinx.coroutines.runBlocking {
        val content = "stream content"
        val bytes = content.toByteArray()
        val storagePath = "/uploads/stream-file.txt"

        service.save(storagePath, ByteArrayInputStream(bytes), bytes.size.toLong(), "text/plain")

        val resolved = service.resolve(storagePath)
        assertNotNull(resolved)
        assertTrue(resolved is StoragePayload.LocalFile)
        assertEquals(content, resolved.file.readText())
    }

    @Test
    fun `resolve non-existent file returns null`() = kotlinx.coroutines.runBlocking {
        val resolved = service.resolve("/uploads/does-not-exist.txt")
        assertNull(resolved)
    }

    @Test
    fun `rejects path traversal attempts`() = kotlinx.coroutines.runBlocking {
        assertFailsWith<IllegalArgumentException> {
            service.save("/uploads/../escape.txt", ByteArrayInputStream("evil".toByteArray()), 4, "text/plain")
        }
        assertNull(service.resolve("/uploads/../escape.txt"))
        assertNull(service.resolve("/something/else.txt"))
    }

    @Test
    fun `delete removes file`() = kotlinx.coroutines.runBlocking {
        val storagePath = "/uploads/to-delete.txt"
        service.save(storagePath, ByteArrayInputStream("delete me".toByteArray()), 9, "text/plain")
        assertNotNull(service.resolve(storagePath))

        service.delete(storagePath)
        assertNull(service.resolve(storagePath))
    }

    @Test
    fun `deleteMultiple removes all specified files`() = kotlinx.coroutines.runBlocking {
        val path1 = "/uploads/file1.txt"
        val path2 = "/uploads/file2.txt"

        service.save(path1, ByteArrayInputStream("1".toByteArray()), 1, "text/plain")
        service.save(path2, ByteArrayInputStream("2".toByteArray()), 1, "text/plain")

        assertNotNull(service.resolve(path1))
        assertNotNull(service.resolve(path2))

        service.deleteMultiple(listOf(path1, path2))

        assertNull(service.resolve(path1))
        assertNull(service.resolve(path2))
    }
}
