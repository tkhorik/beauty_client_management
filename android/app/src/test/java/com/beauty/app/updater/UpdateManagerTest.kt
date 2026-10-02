package com.beauty.app.updater

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class UpdateManagerTest {

    private val context: Context = mock()
    private val prefs: SharedPreferences = mock()
    private val editor: SharedPreferences.Editor = mock()
    private val service: GithubUpdateService = mock()

    @Before
    fun setup() {
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.putString(any(), any())).thenReturn(editor)
        whenever(editor.putLong(any(), any())).thenReturn(editor)
    }

    @Test
    fun `checkForUpdates transitions to Available when newer release exists`() = runTest {
        val release = AppRelease(
            tagName = "v1.4.4",
            versionName = "1.4.4",
            title = "Aura Beauty Log v1.4.4",
            notes = "Bugfixes",
            apkUrl = "https://example.com/aura-beauty-1.4.4.apk",
            apkSize = 13612539L,
            publishedAt = "2026-10-01T22:10:55Z",
            htmlUrl = "https://github.com/tkhorik/beauty_client_management/releases/tag/v1.4.4"
        )
        whenever(service.fetchLatestRelease()).thenReturn(Result.success(release))
        whenever(prefs.getLong(any(), eq(0L))).thenReturn(0L)
        whenever(prefs.getString(eq("dismissed_tag"), isNull())).thenReturn(null)

        val manager = UpdateManager(context, service, prefs)
        manager.checkForUpdates(force = true)

        val state = manager.state.value
        assertTrue(state is UpdateState.Available)
        val available = state as UpdateState.Available
        assertEquals("1.4.4", available.release.versionName)
        assertFalse(available.dismissed)
    }

    @Test
    fun `dismissCurrentUpdate marks state as dismissed`() = runTest {
        val release = AppRelease(
            tagName = "v1.4.4",
            versionName = "1.4.4",
            title = "Aura Beauty Log v1.4.4",
            notes = "Bugfixes",
            apkUrl = "https://example.com/aura-beauty-1.4.4.apk",
            apkSize = 13612539L,
            publishedAt = "2026-10-01T22:10:55Z",
            htmlUrl = "https://github.com/tkhorik/beauty_client_management/releases/tag/v1.4.4"
        )
        whenever(service.fetchLatestRelease()).thenReturn(Result.success(release))

        val manager = UpdateManager(context, service, prefs)
        manager.checkForUpdates(force = true)
        manager.dismissCurrentUpdate()

        val state = manager.state.value
        assertTrue(state is UpdateState.Available)
        assertTrue((state as UpdateState.Available).dismissed)

        manager.reopenUpdateDialog()
        assertFalse((manager.state.value as UpdateState.Available).dismissed)
    }

    @Test
    fun `checkForUpdates transitions to UpToDate when no release or version is older`() = runTest {
        val olderRelease = AppRelease(
            tagName = "v0.9.0",
            versionName = "0.9.0",
            title = "Aura Beauty Log v0.9.0",
            notes = "Old",
            apkUrl = "https://example.com/old.apk",
            apkSize = 1000L,
            publishedAt = "",
            htmlUrl = ""
        )
        whenever(service.fetchLatestRelease()).thenReturn(Result.success(olderRelease))

        val manager = UpdateManager(context, service, prefs)
        manager.checkForUpdates(force = true)

        val state = manager.state.value
        assertTrue(state is UpdateState.UpToDate)
    }
}
