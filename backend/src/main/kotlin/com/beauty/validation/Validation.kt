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
object Validation {

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

    fun validateEmail(email: String): String? = when {
        email.isBlank() -> "Email is required."
        email.length > EMAIL_MAX_LENGTH -> "Email must be at most $EMAIL_MAX_LENGTH characters."
        !EMAIL_PATTERN.matches(email) -> "Enter a valid email address."
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
    fun validatePassword(password: String): String? {
        val byteLength = password.toByteArray(Charsets.UTF_8).size
        return when {
            password.isEmpty() -> "Password is required."
            password.length < PASSWORD_MIN_LENGTH ->
                "Password must be at least $PASSWORD_MIN_LENGTH characters."
            byteLength > PASSWORD_MAX_BYTES ->
                "Password must be at most $PASSWORD_MAX_BYTES bytes long."
            else -> null
        }
    }

    fun validateFullName(fullName: String): String? = when {
        fullName.isBlank() -> "Name is required."
        fullName.length > FULL_NAME_MAX_LENGTH ->
            "Name must be at most $FULL_NAME_MAX_LENGTH characters."
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

    fun validateOrganizationName(name: String): String? = when {
        name.isBlank() -> "Organization name is required."
        name.length > ORG_NAME_MAX_LENGTH ->
            "Organization name must be at most $ORG_NAME_MAX_LENGTH characters."
        else -> null
    }

    fun validateOrganizationSlug(slug: String): String? = when {
        slug.isBlank() -> "Organization handle is required."
        slug.length < ORG_SLUG_MIN_LENGTH ->
            "Organization handle must be at least $ORG_SLUG_MIN_LENGTH characters."
        slug.length > ORG_SLUG_MAX_LENGTH ->
            "Organization handle must be at most $ORG_SLUG_MAX_LENGTH characters."
        !ORG_SLUG_PATTERN.matches(slug) ->
            "Organization handle may contain only lowercase letters, numbers and hyphens."
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
