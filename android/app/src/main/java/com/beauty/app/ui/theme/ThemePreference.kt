package com.beauty.app.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate

/** Device-only display choice. It intentionally never shares account or credential storage. */
enum class ThemePreference(val storedValue: String, val nightMode: Int, val applicationNightMode: Int) {
    // For the application API, AUTO clears the package override and follows system configuration.
    // AppCompat's FOLLOW_SYSTEM (-1) is not accepted by UiModeManager.
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, UiModeManager.MODE_NIGHT_AUTO),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO, UiModeManager.MODE_NIGHT_NO),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES, UiModeManager.MODE_NIGHT_YES);

    companion object {
        fun fromStored(value: String?): ThemePreference = entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

class ThemePreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun get(): ThemePreference = ThemePreference.fromStored(preferences.getString(KEY, null))

    fun set(preference: ThemePreference) {
        preferences.edit().putString(KEY, preference.storedValue).apply()
    }

    companion object {
        private const val FILE_NAME = "display_preferences"
        private const val KEY = "theme_preference"
    }
}

/** Apply at process startup and on selection; the platform persists the API 31+ launch theme. */
fun applyThemePreference(context: Context, preference: ThemePreference) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(UiModeManager::class.java)
        manager?.setApplicationNightMode(preference.applicationNightMode)
    } else {
        AppCompatDelegate.setDefaultNightMode(preference.nightMode)
    }
}
