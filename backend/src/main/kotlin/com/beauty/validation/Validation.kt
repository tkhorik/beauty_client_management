package com.beauty.validation

/**
 * Input validation for the public auth endpoints.
 *
 * These are the only routes an unauthenticated stranger on the internet can
 * reach, so they are the only place where request bodies are fully untrusted.
 * Validation lives here rather than inline in the route handlers so that the
 * web client, the Android client and the tests can all be checked against one
 * definition of "valid" instead of three drifting copies.
 *
 * Every validator returns a map of `field name -> human-readable message`, so
 * a failure can be rendered inline next to the offending input rather than as
 * one opaque banner.
 */
data class ValidationIssue(val code: String, val message: String, val args: Map<String, Int> = emptyMap())

object Validation {

    fun validateEmail(email: String): String? = emailIssue(email)?.message

    fun validatePassword(password: String): String? = passwordIssue(password)?.message

    fun validateFullName(fullName: String): String? = fullNameIssue(fullName)?.message

    fun validateOrganizationName(name: String): String? = nameIssue(name)?.message

    fun validateOrganizationSlug(slug: String): String? = slugIssue(slug)?.message

    /** BCrypt silently ignores everything past 72 bytes. See [validatePassword]. */
    const val PASSWORD_MAX_BYTES = 72

    /**
     * 12, not 8. Length is the only password property that reliably resists
     * offline cracking, and current NIST guidance (SP 800-63B) explicitly
     * recommends a long minimum over character-class rules.
     */
    const val PASSWORD_MIN_LENGTH = 12

    /** The maximum length of an email address per RFC 5321. */
    const val EMAIL_MAX_LENGTH = 254

    const val FULL_NAME_MAX_LENGTH = 255

    /**
     * Deliberately permissive. Strict email regexes are famous for rejecting
     * addresses that are perfectly valid (plus-addressing, new TLDs, unusual
     * but legal local parts), and they cannot tell a real mailbox from a
     * typo anyway. The only check that actually proves an address works is
     * sending mail to it, which is what the verification flow does. This
     * regex exists to catch obvious garbage, nothing more.
     */
    private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$")

    /**
     * Addresses are compared case-insensitively and stored lowercase.
     *
     * Without this, `Owner@salon.com` and `owner@salon.com` are two distinct
     * rows as far as the unique index is concerned: the same person can
     * register twice, and someone who signs up with capitals then fails to log
     * in with lowercase.
     */
    fun normaliseEmail(raw: String): String = raw.trim().lowercase()

    fun emailIssue(email: String): ValidationIssue? = when {
        email.isBlank() -> ValidationIssue("EMAIL_REQUIRED", "Email is required.")
        email.length > EMAIL_MAX_LENGTH -> ValidationIssue("EMAIL_TOO_LONG", "Email must be at most $EMAIL_MAX_LENGTH characters.", mapOf("max" to EMAIL_MAX_LENGTH))
        !EMAIL_PATTERN.matches(email) -> ValidationIssue("EMAIL_INVALID", "Enter a valid email address.")
        else -> null
    }

    /**
     * No composition rules (no "must contain a digit and a symbol"). Those
     * push people toward predictable substitutions like `Password1!` while
     * blocking genuinely strong passphrases, and the resulting passwords are
     * measurably weaker in practice.
     *
     * The upper bound is not arbitrary. BCrypt operates on the first 72 bytes
     * of input and discards the rest without complaint, so a 200-character
     * passphrase is exactly as strong as its first 72 bytes. Rejecting the
     * input is honest; accepting it and silently truncating is not. Note the
     * check is on **bytes**, not characters — non-ASCII names and passphrases
     * encode to more than one byte per character in UTF-8.
     */
    fun passwordIssue(password: String): ValidationIssue? {
        val byteLength = password.toByteArray(Charsets.UTF_8).size
        return when {
            password.isEmpty() -> ValidationIssue("PASSWORD_REQUIRED", "Password is required.")
            password.length < PASSWORD_MIN_LENGTH ->
                ValidationIssue("PASSWORD_TOO_SHORT", "Password must be at least $PASSWORD_MIN_LENGTH characters.", mapOf("min" to PASSWORD_MIN_LENGTH))
            byteLength > PASSWORD_MAX_BYTES ->
                ValidationIssue("PASSWORD_TOO_LONG", "Password must be at most $PASSWORD_MAX_BYTES bytes long.", mapOf("max" to PASSWORD_MAX_BYTES))
            else -> null
        }
    }

    fun fullNameIssue(fullName: String): ValidationIssue? = when {
        fullName.isBlank() -> ValidationIssue("NAME_REQUIRED", "Name is required.")
        fullName.length > FULL_NAME_MAX_LENGTH ->
            ValidationIssue("NAME_TOO_LONG", "Name must be at most $FULL_NAME_MAX_LENGTH characters.", mapOf("max" to FULL_NAME_MAX_LENGTH))
        else -> null
    }

    const val ORG_NAME_MAX_LENGTH = 255
    const val ORG_SLUG_MAX_LENGTH = 100
    const val ORG_SLUG_MIN_LENGTH = 3

    /**
     * Lowercase letters, digits and single interior hyphens.
     *
     * Narrow on purpose: the slug is spoken aloud and typed from memory when
     * one person tells another which organization to join, so anything that is
     * ambiguous out loud (case, underscores, spaces) is worse than useless.
     */
    private val ORG_SLUG_PATTERN = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

    fun nameIssue(name: String): ValidationIssue? = when {
        name.isBlank() -> ValidationIssue("ORGANIZATION_NAME_REQUIRED", "Organization name is required.")
        name.length > ORG_NAME_MAX_LENGTH ->
            ValidationIssue("ORGANIZATION_NAME_TOO_LONG", "Organization name must be at most $ORG_NAME_MAX_LENGTH characters.", mapOf("max" to ORG_NAME_MAX_LENGTH))
        else -> null
    }

    fun slugIssue(slug: String): ValidationIssue? = when {
        slug.isBlank() -> ValidationIssue("SLUG_REQUIRED", "Organization handle is required.")
        slug.length < ORG_SLUG_MIN_LENGTH ->
            ValidationIssue("SLUG_TOO_SHORT", "Organization handle must be at least $ORG_SLUG_MIN_LENGTH characters.", mapOf("min" to ORG_SLUG_MIN_LENGTH))
        slug.length > ORG_SLUG_MAX_LENGTH ->
            ValidationIssue("SLUG_TOO_LONG", "Organization handle must be at most $ORG_SLUG_MAX_LENGTH characters.", mapOf("max" to ORG_SLUG_MAX_LENGTH))
        !ORG_SLUG_PATTERN.matches(slug) ->
            ValidationIssue("SLUG_INVALID", "Organization handle may contain only lowercase letters, numbers and hyphens.")
        else -> null
    }

    /**
     * Turns a display name into a candidate slug.
     *
     * Best-effort: the result still goes through [validateOrganizationSlug], so
     * a name made entirely of characters this strips (say, one written in a
     * non-Latin script) is rejected with a message asking for an explicit
     * handle rather than silently becoming an empty string.
     */
    fun slugify(raw: String): String = raw.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    /**
     * Validates a registration payload against already-normalised values.
     * Returns an empty map when everything is acceptable.
     */
    fun registrationIssues(email: String, password: String, fullName: String): Map<String, ValidationIssue> = buildMap {
        emailIssue(email)?.let { put("email", it) }
        passwordIssue(password)?.let { put("password", it) }
        fullNameIssue(fullName)?.let { put("fullName", it) }
    }

    fun validateRegistration(
        email: String,
        password: String,
        fullName: String
    ): Map<String, String> = buildMap {
        validateEmail(email)?.let { put("email", it) }
        validatePassword(password)?.let { put("password", it) }
        validateFullName(fullName)?.let { put("fullName", it) }
    }
}
