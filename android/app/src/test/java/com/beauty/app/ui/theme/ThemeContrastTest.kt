package com.beauty.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private fun assertReadable(name: String, foreground: Color, background: Color) {
        val first = foreground.luminance()
        val second = background.luminance()
        val ratio = (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
        assertTrue("$name text contrast was $ratio, expected at least 4.5:1", ratio >= 4.5f)
    }

    @Test fun `light and dark semantic text pairs remain readable`() {
        listOf(AuraColors to LightSemantic, AuraDarkColors to DarkSemantic).forEach { (scheme, semantic) ->
            assertReadable("background", scheme.onBackground, scheme.background)
            assertReadable("surface", scheme.onSurface, scheme.surface)
            assertReadable("muted", semantic.muted, scheme.surface)
            assertReadable("primary action", scheme.onPrimary, scheme.primary)
            assertReadable("secondary action", scheme.onSecondary, scheme.secondary)
            assertReadable("tertiary action", scheme.onTertiary, scheme.tertiary)
            assertReadable("error", scheme.error, scheme.surface)
            assertReadable("error badge", scheme.onErrorContainer, scheme.errorContainer)
            assertReadable("success", semantic.success, scheme.surface)
            assertReadable("success action", semantic.onSuccess, semantic.success)
            assertReadable("sage badge", scheme.onSurface, semantic.sage)
            assertReadable("peach badge", scheme.onSurface, semantic.peach)
        }
    }
}
