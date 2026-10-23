package com.beauty.app

import android.app.Application
import com.beauty.app.ui.theme.ThemePreferenceStore
import com.beauty.app.ui.theme.applyThemePreference

class BeautyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before AppCompat attaches any activity, including a cold start or incoming link.
        // The display store is independent of credentials and survives sign-out/account changes.
        applyThemePreference(this, ThemePreferenceStore(this).get())
    }
}
