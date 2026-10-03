package com.beauty.i18n

/** Preferences are synchronized; a device's resolved language is not. */
object Languages {
    val preferences = setOf("system", "en", "ru")

    fun resolve(preference: String, acceptLanguage: String?): String {
        if (preference == "en" || preference == "ru") return preference
        return acceptLanguage.orEmpty().split(',').mapIndexedNotNull { index, entry ->
            val parts = entry.trim().split(';')
            val language = parts.first().trim().substringBefore('-').lowercase()
            val quality = parts.drop(1).firstOrNull { it.trim().startsWith("q=") }
                ?.trim()?.substringAfter('=')?.toDoubleOrNull() ?: 1.0
            if (language !in setOf("en", "ru") || quality <= 0 || quality > 1) null
            else Triple(language, quality, index)
        }.sortedWith(compareByDescending<Triple<String, Double, Int>> { it.second }.thenBy { it.third })
            .firstOrNull()?.first ?: "en"
    }
}
