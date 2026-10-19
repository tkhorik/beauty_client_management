package com.beauty.app.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Same cases as the backend's `ValidationTest` phone section; the rules must not drift. */
class AuthValidationPhoneTest {

    @Test
    fun `complete US and Russian numbers are accepted and formatted`() {
        mapOf(
            "+1 (555) 000-0000" to "+1 (555) 000-0000",
            "15550000000" to "+1 (555) 000-0000",
            "+7 999 123 45 67" to "+7 (999) 123-45-67",
            "8 999 123 45 67" to "+7 (999) 123-45-67",
            "555 000 0000" to "+1 (555) 000-0000",
        ).forEach { (raw, formatted) ->
            assertNull("expected '$raw' to be accepted", AuthValidation.phoneError(raw))
            assertEquals(formatted, AuthValidation.formatPhone(raw))
        }
    }

    @Test
    fun `empty and incomplete numbers are rejected with distinct codes`() {
        assertEquals("PHONE_REQUIRED", AuthValidation.phoneError("  "))
        listOf("+1 (555) 000-000", "+1 555 000 00000", "+7 999 123")
            .forEach { assertEquals("PHONE_INVALID", AuthValidation.phoneError(it)) }
    }
}
