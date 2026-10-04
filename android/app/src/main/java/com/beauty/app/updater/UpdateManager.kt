package com.beauty.app.updater

import android.content.Context
import android.content.SharedPreferences
import com.beauty.app.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: AppRelease, val dismissed: Boolean = false) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateState
    data class ReadyToInstall(val release: AppRelease, val apkFile: File) : UpdateState
    data class Error(val message: String, val release: AppRelease? = null) : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
}

class UpdateManager(
    private val context: Context,
    private val service: GithubUpdateService = GithubUpdateService(),
    private val prefs: SharedPreferences = context.getSharedPreferences("app_update_prefs", Context.MODE_PRIVATE)
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    val currentVersionName: String = BuildConfig.VERSION_NAME

    val releasesPageUrl: String get() = service.releasesPageUrl

    /**
     * Resolves the active distribution mode.
     */
    fun getDistributionMode(): UpdateDistributionMode {
        val saved = prefs.getString(KEY_DISTRIBUTION_MODE, UpdateDistributionMode.AUTO.name)
        val mode = runCatching { UpdateDistributionMode.valueOf(saved ?: UpdateDistributionMode.AUTO.name) }
            .getOrDefault(UpdateDistributionMode.AUTO)

        return if (mode == UpdateDistributionMode.AUTO) {
            if (UpdateInstaller.isInstalledFromGooglePlay(context)) {
                UpdateDistributionMode.PLAY_STORE
            } else {
                UpdateDistributionMode.GITHUB_DIRECT
            }
        } else {
            mode
        }
    }

    fun setDistributionMode(mode: UpdateDistributionMode) {
        prefs.edit().putString(KEY_DISTRIBUTION_MODE, mode.name).apply()
    }

    /**
     * Checks GitHub for updates.
     *
     * @param force If false, respects throttling (checks at most once every 30 minutes).
     */
    suspend fun checkForUpdates(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_TIMESTAMP, 0L)
        if (!force && now - lastCheck < THROTTLE_INTERVAL_MS && _state.value !is UpdateState.Idle) {
            return
        }

        _state.value = UpdateState.Checking

        val result = service.fetchLatestRelease()
        prefs.edit().putLong(KEY_LAST_CHECK_TIMESTAMP, now).apply()

        result.fold(
            onSuccess = { release ->
                if (release == null) {
                    _state.value = UpdateState.UpToDate(now)
                    return@fold
                }

                val hasNewer = SemanticVersion.isNewer(currentVersionName, release.versionName)
                if (hasNewer) {
                    val lastDismissedTag = prefs.getString(KEY_DISMISSED_TAG, null)
                    val isDismissed = !force && (lastDismissedTag == release.tagName)
                    _state.value = UpdateState.Available(release, dismissed = isDismissed)
                } else {
                    _state.value = UpdateState.UpToDate(now)
                }
            },
            onFailure = { error ->
                _state.value = UpdateState.Error("UPDATE_CHECK")
            }
        )
    }

    /**
     * Dismisses the startup modal dialog for the current release without disabling the update banner.
     */
    fun dismissCurrentUpdate() {
        val current = _state.value
        if (current is UpdateState.Available) {
            prefs.edit().putString(KEY_DISMISSED_TAG, current.release.tagName).apply()
            _state.value = current.copy(dismissed = true)
        }
    }

    /**
     * Restores the dialog visibility when the user clicks the update banner.
     */
    fun reopenUpdateDialog() {
        val current = _state.value
        if (current is UpdateState.Available) {
            _state.value = current.copy(dismissed = false)
        }
    }

    /**
     * Begins streaming the APK file to the app's cache directory.
     */
    fun startDownload(scope: CoroutineScope) {
        val current = _state.value
        val release = when (current) {
            is UpdateState.Available -> current.release
            is UpdateState.Error -> current.release
            else -> null
        } ?: return

        downloadJob?.cancel()
        _state.value = UpdateState.Downloading(release, progress = 0f, downloadedBytes = 0L, totalBytes = release.apkSize)

        val destinationDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val targetApk = File(destinationDir, "aura-beauty-update.apk")

        downloadJob = scope.launch {
            val result = service.downloadApk(
                downloadUrl = release.apkUrl,
                targetFile = targetApk
            ) { downloaded, total ->
                val progress = if (total > 0) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                _state.value = UpdateState.Downloading(release, progress, downloaded, total)
            }

            result.fold(
                onSuccess = { file ->
                    _state.value = UpdateState.ReadyToInstall(release, file)
                },
                onFailure = { error ->
                    if (error !is CancellationException) {
                        _state.value = UpdateState.Error("UPDATE_DOWNLOAD", release)
                    }
                }
            )
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val current = _state.value
        if (current is UpdateState.Downloading) {
            _state.value = UpdateState.Available(current.release, dismissed = false)
        }
    }

    /**
     * Performs update action based on distribution mode:
     * - In GITHUB_DIRECT: launches package installer via FileProvider.
     * - In PLAY_STORE: opens Google Play store listing.
     */
    fun installOrOpenStore(context: Context): Result<Unit> {
        val mode = getDistributionMode()
        return if (mode == UpdateDistributionMode.PLAY_STORE) {
            UpdateInstaller.openGooglePlayStore(context)
            Result.success(Unit)
        } else {
            val current = _state.value
            if (current is UpdateState.ReadyToInstall) {
                UpdateInstaller.launchApkInstallation(context, current.apkFile)
            } else {
                Result.failure(IllegalStateException("No APK downloaded and ready to install"))
            }
        }
    }

    fun clearCache() {
        runCatching {
            File(context.cacheDir, "updates").deleteRecursively()
        }
    }

    companion object {
        private const val KEY_LAST_CHECK_TIMESTAMP = "last_check_timestamp"
        private const val KEY_DISMISSED_TAG = "dismissed_tag"
        private const val KEY_DISTRIBUTION_MODE = "distribution_mode"
        private const val THROTTLE_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes
    }
}
