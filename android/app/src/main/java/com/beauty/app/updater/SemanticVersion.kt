package com.beauty.app.updater

/**
 * Parses and compares semantic version strings.
 *
 * Supported formats:
 * - "1.4.3"
 * - "v1.4.4"
 * - "1.4.3-debug"
 * - "1.0.0-local"
 */
data class SemanticVersion(
    val parts: List<Int>,
    val raw: String
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        val maxLen = maxOf(parts.size, other.parts.size)
        for (i in 0 until maxLen) {
            val thisPart = parts.getOrElse(i) { 0 }
            val otherPart = other.parts.getOrElse(i) { 0 }
            if (thisPart != otherPart) {
                return thisPart.compareTo(otherPart)
            }
        }
        return 0
    }

    companion object {
        fun parse(version: String): SemanticVersion {
            val clean = version.trim()
                .removePrefix("v")
                .removePrefix("V")
                // Strip pre-release suffixes like "-debug", "-local", "-alpha.1"
                .substringBefore("-")
                .trim()

            val parts = clean.split(".")
                .mapNotNull { it.toIntOrNull() }

            return SemanticVersion(
                parts = parts.ifEmpty { listOf(0) },
                raw = version
            )
        }

        /**
         * Returns true if [targetVersion] is strictly newer than [currentVersion].
         */
        fun isNewer(currentVersion: String, targetVersion: String): Boolean {
            val current = parse(currentVersion)
            val target = parse(targetVersion)
            return target > current
        }
    }
}
