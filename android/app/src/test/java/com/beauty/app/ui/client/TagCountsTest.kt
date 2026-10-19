package com.beauty.app.ui.client

import org.junit.Assert.assertEquals
import org.junit.Test

class TagCountsTest {
    @Test fun `counts each client once per tag, ignoring case and blanks, most common first`() {
        val counted = tagCounts(listOf(
            listOf("VIP", "Diabetic", "vip"),
            listOf(" vip ", ""),
            listOf("Manicure only", "diabetic"),
            listOf("Allergy")
        ))
        assertEquals(listOf("Diabetic" to 2, "VIP" to 2, "Allergy" to 1, "Manicure only" to 1), counted)
    }

    @Test fun `no clients means no chips`() {
        assertEquals(emptyList<Pair<String, Int>>(), tagCounts(emptyList()))
    }
}
