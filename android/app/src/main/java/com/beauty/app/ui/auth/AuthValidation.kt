package com.beauty.app.ui.auth

/**
 * Client-side mirror of the backend's `com.beauty.validation.Validation`.
 *
 * The server remains the authority — this exists so the user is told about a
 * too-short password immediately instead of after a network round trip, which
 * matters far more on mobile than on desktop. **If the backend rules change,
 * change these too**; a client that is stricter than the server merely annoys,
 * but a client that is laxer produces confusing 400s the UI cannot explain.
 */
object AuthValidation {

    const val PASSWORD_MIN_LENGTH = 12

    /** BCrypt ignores everything past 72 bytes, so the server rejects longer input. */
    const val PASSWORD_MAX_BYTES = 72

    private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$")

    /** Addresses are stored lowercase server-side; normalise before sending. */
    fun normaliseEmail(raw: String): String = raw.trim().lowercase()

    fun emailError(email: String): String? = when {
        email.isBlank() -> "EMAIL_REQUIRED"
        !EMAIL_PATTERN.matches(email) -> "EMAIL_INVALID"
        else -> null
    }

    fun passwordError(password: String): String? = when {
        password.isEmpty() -> "PASSWORD_REQUIRED"
        password.length < PASSWORD_MIN_LENGTH ->
            "PASSWORD_TOO_SHORT:$PASSWORD_MIN_LENGTH"
        // Byte length, not character count: non-ASCII characters take more
        // than one byte in UTF-8 and can cross the limit sooner than expected.
        password.toByteArray(Charsets.UTF_8).size > PASSWORD_MAX_BYTES ->
            "PASSWORD_TOO_LONG:$PASSWORD_MAX_BYTES"
        else -> null
    }

    /**
     * Mirrors the backend's `Validation.phoneIssue` / `formatPhone` (and the
     * web's `phone.ts`): 11 digits with a +1 or +7 country code. A leading 8
     * is read as +7; any other unknown first digit means +1 was left off.
     */
    private val PHONE_GROUPS = mapOf('1' to listOf(3, 3, 4), '7' to listOf(3, 3, 2, 2))

    const val PHONE_DIGITS = 11

    private fun phoneDigits(raw: String): String {
        val digits = raw.filter { it in '0'..'9' }
        return when {
            digits.isEmpty() -> ""
            digits[0] == '8' -> "7" + digits.drop(1)
            digits[0] !in PHONE_GROUPS -> "1$digits"
            else -> digits
        }
    }

    fun phoneError(phone: String): String? {
        val digits = phoneDigits(phone)
        return when {
            digits.isEmpty() -> "PHONE_REQUIRED"
            digits.length != PHONE_DIGITS -> "PHONE_INVALID"
            else -> null
        }
    }

    /** `+1 (555) 000-0000` / `+7 (999) 123-45-67`. Call only after [phoneError] passed. */
    fun formatPhone(phone: String): String {
        val digits = phoneDigits(phone)
        var rest = digits.drop(1)
        val parts = PHONE_GROUPS.getValue(digits[0]).map { size -> rest.take(size).also { rest = rest.drop(size) } }
        return "+${digits[0]} (${parts[0]}) ${parts[1]}-" + parts.drop(2).joinToString("-")
    }

    fun fullNameError(fullName: String): String? =
        if (fullName.isBlank()) "NAME_REQUIRED" else null

    /**
     * Checked only on the client: the confirmation field is never sent. A typo
     * here would otherwise lock the user out of an account they just created,
     * recoverable then only by going through the reset flow — which is a poor
     * substitute for catching the mistake in front of them.
     */
    fun confirmPasswordError(password: String, confirmPassword: String): String? =
        if (password != confirmPassword) "PASSWORD_MISMATCH" else null
}
