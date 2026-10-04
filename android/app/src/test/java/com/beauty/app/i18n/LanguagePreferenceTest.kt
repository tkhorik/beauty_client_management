package com.beauty.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguagePreferenceTest {
    @Test fun `known Android locale tags map to account preferences`() {
        assertEquals(LanguagePreference.RUSSIAN, LanguagePreference.fromLocaleTags("ru-RU"))
        assertEquals(LanguagePreference.ENGLISH, LanguagePreference.fromLocaleTags("en-US,ru-RU"))
    }

    @Test fun `unsupported and empty locale tags follow system`() {
        assertEquals(LanguagePreference.SYSTEM, LanguagePreference.fromLocaleTags("de-DE"))
        assertEquals(LanguagePreference.SYSTEM, LanguagePreference.fromLocaleTags(""))
    }
}
