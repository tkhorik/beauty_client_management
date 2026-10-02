package com.beauty.app.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {

    @Test
    fun `isNewer correctly compares patch versions`() {
        assertTrue(SemanticVersion.isNewer("1.4.3", "1.4.4"))
        assertTrue(SemanticVersion.isNewer("1.4.3", "v1.4.4"))
        assertFalse(SemanticVersion.isNewer("1.4.4", "1.4.3"))
        assertFalse(SemanticVersion.isNewer("1.4.4", "v1.4.4"))
    }

    @Test
    fun `isNewer handles minor and major version increases`() {
        assertTrue(SemanticVersion.isNewer("1.4.3", "1.5.0"))
        assertTrue(SemanticVersion.isNewer("1.4.3", "2.0.0"))
        assertFalse(SemanticVersion.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun `isNewer ignores debug and local suffixes`() {
        assertTrue(SemanticVersion.isNewer("1.4.3-debug", "v1.4.4"))
        assertTrue(SemanticVersion.isNewer("1.0.0-local", "1.0.1"))
        assertFalse(SemanticVersion.isNewer("1.4.4-debug", "v1.4.4"))
    }

    @Test
    fun `handles different number of version parts`() {
        assertTrue(SemanticVersion.isNewer("1.4", "1.4.1"))
        assertFalse(SemanticVersion.isNewer("1.4.0", "1.4"))
        assertEquals(0, SemanticVersion.parse("1.4").compareTo(SemanticVersion.parse("1.4.0")))
    }
}
