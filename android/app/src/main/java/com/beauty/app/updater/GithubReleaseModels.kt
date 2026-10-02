package com.beauty.app.updater

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GithubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String? = null,
    @SerialName("body") val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<GithubAssetDto> = emptyList()
)

@Serializable
data class GithubAssetDto(
    val name: String,
    val size: Long = 0L,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    @SerialName("content_type") val contentType: String? = null
)

/**
 * Domain representation of an available app release.
 */
data class AppRelease(
    val tagName: String,
    val versionName: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val publishedAt: String,
    val htmlUrl: String
)

/**
 * Update distribution mode to ensure compatibility with Google Play Store policies.
 */
enum class UpdateDistributionMode {
    /** Direct APK download & in-place update from GitHub Releases (standard sideloading). */
    GITHUB_DIRECT,

    /** Opens Google Play Store (strictly required for apps installed via Google Play). */
    PLAY_STORE,

    /** Automatically determines based on installer package name (`com.android.vending`). */
    AUTO
}
