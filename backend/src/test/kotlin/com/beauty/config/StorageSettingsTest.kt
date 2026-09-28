package com.beauty.config

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.*

class StorageSettingsTest {

    @Test
    fun `defaults to local storage provider`() {
        val config = MapApplicationConfig()
        val settings = AppSettings(config)

        assertEquals(StorageProvider.LOCAL, settings.storageProvider)
        assertEquals("uploads", settings.uploadDir.name)
    }

    @Test
    fun `parses S3 settings from config`() {
        val config = MapApplicationConfig(
            "storage.provider" to "s3",
            "storage.s3.bucket" to "my-photos-bucket",
            "storage.s3.region" to "eu-central-1",
            "storage.s3.endpoint" to "http://minio:9000",
            "storage.s3.accessKey" to "minioadmin",
            "storage.s3.secretKey" to "miniopassword",
            "storage.s3.pathStyleAccess" to "true",
            "storage.s3.deliveryMode" to "stream",
            "storage.s3.presignedUrlMinutes" to "30"
        )
        val settings = AppSettings(config)

        assertEquals(StorageProvider.S3, settings.storageProvider)
        assertEquals("my-photos-bucket", settings.s3Settings.bucket)
        assertEquals("eu-central-1", settings.s3Settings.region)
        assertEquals("http://minio:9000", settings.s3Settings.endpoint)
        assertEquals("minioadmin", settings.s3Settings.accessKey)
        assertEquals("miniopassword", settings.s3Settings.secretKey)
        assertTrue(settings.s3Settings.pathStyleAccess)
        assertEquals(S3DeliveryMode.STREAM, settings.s3Settings.deliveryMode)
        assertEquals(30L, settings.s3Settings.presignedUrlMinutes)
    }

    @Test
    fun `production refuses to boot with blank S3 bucket when provider is s3`() {
        val config = MapApplicationConfig(
            "app.environment" to "production",
            "app.publicUrl" to "https://beauty.example.com",
            "jwt.secret" to "a-very-long-production-secret-with-sufficient-entropy-for-testing",
            "mail.host" to "smtp.example.com",
            "mail.startTls" to "true",
            "storage.provider" to "s3",
            "storage.s3.bucket" to ""
        )
        val settings = AppSettings(config)

        val ex = assertFailsWith<IllegalStateException> {
            settings.validateOrFail()
        }
        assertTrue(ex.message!!.contains("S3_BUCKET must not be blank when STORAGE_PROVIDER=s3"))
    }

    @Test
    fun `production boots successfully with valid S3 configuration`() {
        val config = MapApplicationConfig(
            "app.environment" to "production",
            "app.publicUrl" to "https://beauty.example.com",
            "jwt.secret" to "a-very-long-production-secret-with-sufficient-entropy-for-testing",
            "mail.host" to "smtp.example.com",
            "mail.startTls" to "true",
            "storage.provider" to "s3",
            "db.url" to "jdbc:postgresql://db:5432/beautydb",
            "db.password" to "a-real-production-password",
            "storage.s3.bucket" to "production-bucket"
        )
        val settings = AppSettings(config)
        settings.validateOrFail()
    }
}
