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
        email.isBlank() -> "Email is required."
        !EMAIL_PATTERN.matches(email) -> "Enter a valid email address."
        else -> null
    }

    fun passwordError(password: String): String? = when {
        password.isEmpty() -> "Password is required."
        password.length < PASSWORD_MIN_LENGTH ->
            "Password must be at least $PASSWORD_MIN_LENGTH characters."
        // Byte length, not character count: non-ASCII characters take more
        // than one byte in UTF-8 and can cross the limit sooner than expected.
        password.toByteArray(Charsets.UTF_8).size > PASSWORD_MAX_BYTES ->
            "Password must be at most $PASSWORD_MAX_BYTES bytes long."
        else -> null
    }

    fun fullNameError(fullName: String): String? =
        if (fullName.isBlank()) "Name is required." else null

    /**
     * Checked only on the client: the confirmation field is never sent. A typo
     * here would otherwise lock the user out of an account they just created
     * and have no way to reset.
     */
    fun confirmPasswordError(password: String, confirmPassword: String): String? =
        if (password != confirmPassword) "Passwords do not match." else null
}
