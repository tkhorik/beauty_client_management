package com.beauty.app.ui.theme

import android.app.UiModeManager
import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferenceTest {
    @Test fun `stored choices map to their AppCompat modes`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemePreference.SYSTEM.nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemePreference.LIGHT.nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemePreference.DARK.nightMode)
    }

    @Test fun `application API uses its own supported constants`() {
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, ThemePreference.SYSTEM.applicationNightMode)
        assertEquals(UiModeManager.MODE_NIGHT_NO, ThemePreference.LIGHT.applicationNightMode)
        assertEquals(UiModeManager.MODE_NIGHT_YES, ThemePreference.DARK.applicationNightMode)
    }

    @Test fun `missing or invalid choice defaults to system`() {
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStored(null))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStored("sepia"))
        assertEquals(ThemePreference.DARK, ThemePreference.fromStored("dark"))
    }
}
