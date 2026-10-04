package com.beauty.app.i18n

/** Language contract shared by UI, Android Settings reconciliation, and API DTOs. */
object LanguagePreference {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val RUSSIAN = "ru"
    val all = setOf(SYSTEM, ENGLISH, RUSSIAN)

    fun fromLocaleTags(tags: String): String = when (tags.substringBefore(',').substringBefore('-').lowercase()) {
        RUSSIAN -> RUSSIAN
        ENGLISH -> ENGLISH
        else -> SYSTEM
    }
}
