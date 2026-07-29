package com.beauty.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValidationTest {

    // ── Email normalisation ────────────────────────────────────────────────
    // The case that motivates this: without normalisation, the unique index
    // treats these as different people, and a user who signs up with capitals
    // can never log in with lowercase.

    @Test
    fun `email is lowercased and trimmed`() {
        assertEquals("owner@salon.com", Validation.normaliseEmail("  Owner@Salon.COM  "))
    }

    @Test
    fun `differently cased addresses normalise to the same value`() {
        assertEquals(
            Validation.normaliseEmail("OWNER@salon.com"),
            Validation.normaliseEmail("owner@SALON.com")
        )
    }

    // ── Email format ───────────────────────────────────────────────────────

    @Test
    fun `obvious non-addresses are rejected`() {
        listOf("", "   ", "notanemail", "no@domain", "@salon.com", "owner@", "a b@salon.com")
            .forEach { assertNotNull(Validation.validateEmail(it), "expected '$it' to be rejected") }
    }

    @Test
    fun `legitimate but unusual addresses are accepted`() {
        // Over-strict email regexes routinely reject these. They are all valid.
        listOf(
            "owner+booking@salon.co.uk",
            "first.last@sub.domain.example",
            "o'brien@salon.ie",
            "owner_1@salon.travel"
        ).forEach { assertNull(Validation.validateEmail(it), "expected '$it' to be accepted") }
    }

    @Test
    fun `over-long addresses are rejected`() {
        val tooLong = "a".repeat(250) + "@salon.com"
        assertNotNull(Validation.validateEmail(tooLong))
    }

    // ── Password policy ────────────────────────────────────────────────────

    @Test
    fun `short passwords are rejected`() {
        assertNotNull(Validation.validatePassword("a"))
        assertNotNull(Validation.validatePassword("short"))
        // One character below the boundary.
        assertNotNull(Validation.validatePassword("a".repeat(Validation.PASSWORD_MIN_LENGTH - 1)))
    }

    @Test
    fun `password at the minimum length is accepted`() {
        assertNull(Validation.validatePassword("a".repeat(Validation.PASSWORD_MIN_LENGTH)))
    }

    @Test
    fun `a long passphrase without symbols is accepted`() {
        // No composition rules by design: length beats character-class mandates.
        assertNull(Validation.validatePassword("correct horse battery staple"))
    }

    @Test
    fun `passwords beyond BCrypt's 72-byte limit are rejected rather than silently truncated`() {
        assertNotNull(Validation.validatePassword("a".repeat(Validation.PASSWORD_MAX_BYTES + 1)))
        assertNull(Validation.validatePassword("a".repeat(Validation.PASSWORD_MAX_BYTES)))
    }

    @Test
    fun `the byte limit counts bytes, not characters`() {
        // 30 emoji = 30 characters but 120 UTF-8 bytes, so this must fail even
        // though its character count is well under the limit.
        val emoji = "🌸".repeat(30)
        assertTrue(emoji.length < Validation.PASSWORD_MAX_BYTES)
        assertNotNull(Validation.validatePassword(emoji))
    }

    // ── Full name ──────────────────────────────────────────────────────────

    @Test
    fun `blank names are rejected`() {
        assertNotNull(Validation.validateFullName(""))
        assertNotNull(Validation.validateFullName("   "))
    }

    @Test
    fun `over-long names are rejected before they hit the varchar limit`() {
        assertNotNull(Validation.validateFullName("a".repeat(Validation.FULL_NAME_MAX_LENGTH + 1)))
    }

    // ── Combined registration payload ──────────────────────────────────────

    @Test
    fun `a valid payload produces no errors`() {
        val errors = Validation.validateRegistration(
            email = "owner@salon.com",
            password = "correct horse battery staple",
            fullName = "Salon Owner"
        )
        assertTrue(errors.isEmpty(), "unexpected errors: $errors")
    }

    @Test
    fun `every failing field is reported at once`() {
        // The user should not have to fix one field, resubmit, and discover the
        // next one.
        val errors = Validation.validateRegistration(
            email = "nope",
            password = "short",
            fullName = ""
        )
        assertEquals(setOf("email", "password", "fullName"), errors.keys)
    }
}
