package com.beauty.config

import io.ktor.server.config.*
import java.io.File

/**
 * Typed view over `application.conf` + environment variables.
 *
 * Everything the app needs to know about its environment is read here, once,
 * so there is exactly one place to look for "where does this value come from".
 */
class AppSettings(private val config: ApplicationConfig) {


    private fun str(path: String, default: String): String =
        config.propertyOrNull(path)?.getString()?.trim() ?: default

    val environment: String = str("app.environment", "development").lowercase()
    val isProduction: Boolean = environment == "production"

    /** Extra browser origins allowed to call the API. Empty in a same-origin deployment. */
    val allowedOrigins: List<String> = str("app.allowedOrigins", "")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val uploadDir: File = File(str("app.uploadDir", "uploads"))

    /**
     * Addresses promoted to `SUPER_ADMIN` at startup, comma-separated.
     *
     * The only way into that role short of a manual `UPDATE`, and deliberately
     * so: any API that can grant global access is an API worth attacking, and
     * the role is needed rarely enough that configuration is a fair price.
     *
     * Promotion is idempotent and one-directional — startup never *demotes* an
     * account that has dropped off the list, because silently removing the last
     * super admin during a routine deploy is a worse failure than an extra one
     * lingering until someone revokes it explicitly.
     *
     * Normalised to lowercase to match the storage form of `users.email`.
     */
    val superAdminEmails: List<String> = str("app.superAdminEmails", "")
        .split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }

    /**
     * Canonical public origin, no trailing slash — e.g. `https://beautyclient.duckdns.org`.
     *
     * Used to build the links inside verification and password-reset emails.
     * The request's own Host header is deliberately *not* used for this: an
     * attacker who can set `Host: evil.test` on a `/forgot-password` call would
     * otherwise cause the real user to be mailed a reset link pointing at the
     * attacker's server, which is a classic host-header account takeover. The
     * origin must come from configuration the attacker cannot influence.
     *
     * Reuses the `SITE_URL` name already used by the deploy workflow, rather
     * than introducing a second source of truth for the same value.
     */
    val publicUrl: String = str("app.publicUrl", "http://127.0.0.1:5174").trimEnd('/')

    /**
     * SMTP transport. A blank [mailHost] selects the console-logging sender,
     * which is what makes the flow runnable locally without a mail server.
     */
    val mailHost: String = str("mail.host", "")
    val mailPort: Int = str("mail.port", "587").toIntOrNull() ?: 587
    val mailUser: String = str("mail.user", "")
    val mailPassword: String = str("mail.password", "")
    val mailFrom: String = str("mail.from", "no-reply@localhost")
    val mailFromName: String = str("mail.fromName", "Aura Beauty Log")

    /** STARTTLS on the standard submission port (587). */
    val mailStartTls: Boolean = str("mail.startTls", "true").toBoolean()

    /** Implicit TLS from the first byte (port 465). Mutually exclusive with [mailStartTls]. */
    val mailSslOnConnect: Boolean = str("mail.sslOnConnect", "false").toBoolean()

    /** How long an email-verification link stays usable. */
    val verificationTokenHours: Long = str("mail.verificationTokenHours", "24").toLongOrNull() ?: 24L

    /**
     * How long a password-reset link stays usable.
     *
     * Much shorter than verification, because the two links are not equally
     * dangerous: a leaked verification link only confirms an address, while a
     * leaked reset link is a complete account takeover. The window is the only
     * thing bounding exposure once the mail is sitting in an inbox.
     */
    val resetTokenMinutes: Long = str("mail.resetTokenMinutes", "60").toLongOrNull() ?: 60L

    /**
     * When enforcement of email verification begins, or null for "never".
     *
     * Null is the off switch, and it is off by default: an unverified account
     * behaves exactly as it did before this feature existed. That matters
     * operationally — if enforcement causes trouble in production, clearing
     * `EMAIL_VERIFICATION_ENFORCED_FROM` and restarting restores the previous
     * behaviour without a redeploy, a rollback, or a migration.
     *
     * An unparseable value is treated as null rather than throwing. This is a
     * *restriction* on users, and the safe direction for a typo is to not
     * apply it: a malformed timestamp that locked the entire user base out of
     * writing would be a far worse outcome than one that quietly does nothing
     * and shows up in the startup log.
     */
    val verificationEnforcedFrom: java.time.LocalDateTime? =
        str("mail.verificationEnforcedFrom", "").takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { java.time.LocalDateTime.parse(raw) }.getOrNull()
        }

    /**
     * Days of unrestricted access before an unverified account becomes
     * read-only, counted from [verificationEnforcedFrom] or the account's
     * creation, whichever is later.
     *
     * Coerced to at least zero: a negative window is meaningless, and reading
     * it literally would push the deadline into the past — enforcing
     * *retroactively* on an account that was told it had a week.
     */
    val verificationGraceDays: Long =
        (str("mail.verificationGraceDays", "7").toLongOrNull() ?: 7L).coerceAtLeast(0L)

    val jwtSecret: String = str("jwt.secret", INSECURE_DEV_SECRET)
    val jwtIssuer: String = str("jwt.issuer", "aura-beauty-log")
    val jwtAudience: String = str("jwt.audience", "aura-beauty-log-users")
    val jwtRealm: String = str("jwt.realm", "Beauty Client Management")

    /**
     * Access-token lifetime. Short on purpose: an access token cannot be
     * revoked, so its expiry is the only thing limiting the damage of a leaked
     * one. The refresh token carries the long-lived session and *is* revocable.
     */
    val accessTokenMinutes: Long = str("jwt.accessTokenMinutes", "15").toLongOrNull() ?: 15L

    /** Refresh-token lifetime, i.e. how long a user stays signed in without re-entering a password. */
    val refreshTokenDays: Long = str("jwt.refreshTokenDays", "30").toLongOrNull() ?: 30L

    val dbDriver: String = str("db.driver", "org.postgresql.Driver")
    val dbUrl: String = str("db.url", "jdbc:postgresql://localhost:5432/beautydb")
    val dbUser: String = str("db.user", "postgres")
    val dbPassword: String = str("db.password", "")
    val dbMaxPoolSize: Int = str("db.maxPoolSize", "10").toIntOrNull() ?: 10

    /**
     * H2 fallback is a development convenience. It is force-disabled in
     * production: an API that silently swaps a real database for an in-memory
     * one keeps answering 200 OK while every write disappears on restart.
     */
    val allowH2Fallback: Boolean =
        !isProduction && str("db.allowH2Fallback", "true").toBoolean()

    /**
     * Fail fast at startup rather than serving traffic in an insecure state.
     * A container that refuses to start is loud and obvious; a container
     * running with a publicly known JWT signing key is silent and exploitable.
     */
    fun validateOrFail() {
        if (!isProduction) return

        val problems = buildList {
            if (jwtSecret == INSECURE_DEV_SECRET) {
                add("JWT_SECRET is still the built-in development value. Generate one with: openssl rand -base64 48")
            }
            if (jwtSecret.length < 32) {
                add("JWT_SECRET is shorter than 32 characters, which is too weak for HMAC256.")
            }
            if (dbPassword.isBlank()) {
                add("DB_PASSWORD is empty.")
            }
            if (dbPassword == "postgrespassword") {
                add("DB_PASSWORD is still the built-in development value.")
            }
            if (!dbUrl.startsWith("jdbc:postgresql://")) {
                add("DB_URL is not a PostgreSQL URL (got: $dbUrl). Production must not run on H2.")
            }

            // A verification or reset link is only as trustworthy as the origin
            // baked into it. Pointing at localhost makes the feature silently
            // useless; pointing at http:// puts a single-use account-takeover
            // token in cleartext across the network.
            if (!publicUrl.startsWith("https://")) {
                add("SITE_URL must be an https:// origin in production (got: '$publicUrl').")
            }

            // Blank MAIL_HOST would select LogMailSender, which writes reset
            // links — single-use account-takeover tokens — straight into the
            // container logs and never delivers them.
            if (mailHost.isBlank()) {
                add("MAIL_HOST is not set. Production would fall back to the dev logging mail sender, which prints reset links to the logs instead of sending them.")
            }
            if (mailStartTls && mailSslOnConnect) {
                add("MAIL_STARTTLS and MAIL_SSL_ON_CONNECT are both true. Choose one: STARTTLS for port 587, implicit TLS for port 465.")
            }
            if (!mailStartTls && !mailSslOnConnect) {
                add("Both MAIL_STARTTLS and MAIL_SSL_ON_CONNECT are false: mail, including password-reset links, would be sent unencrypted.")
            }
        }

        if (problems.isNotEmpty()) {
            throw IllegalStateException(
                problems.joinToString(
                    prefix = "Refusing to start in production due to unsafe configuration:\n  - ",
                    separator = "\n  - "
                )
            )
        }
    }

    companion object {
        const val INSECURE_DEV_SECRET = "dev-only-insecure-secret-change-me"
    }
}
