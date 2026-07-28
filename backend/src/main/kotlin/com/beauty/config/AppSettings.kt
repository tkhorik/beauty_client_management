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

    val jwtSecret: String = str("jwt.secret", INSECURE_DEV_SECRET)
    val jwtIssuer: String = str("jwt.issuer", "aura-beauty-log")
    val jwtAudience: String = str("jwt.audience", "aura-beauty-log-users")
    val jwtRealm: String = str("jwt.realm", "Beauty Client Management")

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
