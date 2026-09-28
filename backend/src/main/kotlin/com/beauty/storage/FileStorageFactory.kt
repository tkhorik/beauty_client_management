package com.beauty.storage

import com.beauty.config.AppSettings
import com.beauty.config.S3DeliveryMode
import com.beauty.config.StorageProvider
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

object FileStorageFactory {
    private val log = LoggerFactory.getLogger(FileStorageFactory::class.java)

    fun createStorageService(settings: AppSettings): FileStorageService {
        return when (settings.storageProvider) {
            StorageProvider.LOCAL -> {
                log.info("Using local filesystem storage at: {}", settings.uploadDir.absolutePath)
                LocalFileStorageService(settings.uploadDir)
            }
            StorageProvider.S3 -> {
                val s3 = settings.s3Settings
                log.info("Using S3 storage bucket: '{}' in region: '{}' (endpoint: {})", s3.bucket, s3.region, s3.endpoint ?: "default")

                val credentialsProvider = if (!s3.accessKey.isNullOrBlank() && !s3.secretKey.isNullOrBlank()) {
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.accessKey, s3.secretKey))
                } else {
                    DefaultCredentialsProvider.create()
                }

                val region = Region.of(s3.region)
                val s3Config = S3Configuration.builder()
                    .pathStyleAccessEnabled(s3.pathStyleAccess)
                    .build()

                val clientBuilder = S3Client.builder()
                    .region(region)
                    .credentialsProvider(credentialsProvider)
                    .serviceConfiguration(s3Config)

                s3.endpoint?.let {
                    clientBuilder.endpointOverride(URI.create(it))
                }

                val s3Client = clientBuilder.build()

                val presigner = if (s3.deliveryMode == S3DeliveryMode.REDIRECT) {
                    val presignerBuilder = S3Presigner.builder()
                        .region(region)
                        .credentialsProvider(credentialsProvider)
                        .serviceConfiguration(s3Config)

                    s3.endpoint?.let {
                        presignerBuilder.endpointOverride(URI.create(it))
                    }
                    presignerBuilder.build()
                } else {
                    null
                }

                S3StorageService(s3Client, presigner, s3)
            }
        }
    }
}
