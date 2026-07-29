package com.beauty.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.beauty.config.AppSettings
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

/** Tolerance for clock drift between processes when checking `exp`. */
private const val CLOCK_SKEW_LEEWAY_SECONDS = 30L

fun Application.configureSecurity() {
    val settings = AppSettings(environment.config)
    val secret = settings.jwtSecret
    val issuer = settings.jwtIssuer
    val audience = settings.jwtAudience

    authentication {
        jwt("auth-jwt") {
            this.realm = settings.jwtRealm
            verifier(
                JWT.require(Algorithm.HMAC256(secret))
                    .withAudience(audience)
                    .withIssuer(issuer)
                    // The `exp` claim is checked automatically by the verifier;
                    // the leeway only tolerates minor clock skew between the
                    // token's issuer and this process.
                    .acceptLeeway(CLOCK_SKEW_LEEWAY_SECONDS)
                    .build()
            )
            validate { credential ->
                if (credential.payload.audience.contains(audience)) {
                    JWTPrincipal(credential.payload)
                } else null
            }
        }
    }
}

/**
 * The authenticated user's id, taken from the verified JWT.
 *
 * Null only if called outside an `authenticate` block, or on a token issued
 * before the `userId` claim existed. Callers must treat null as "not
 * authenticated" rather than assuming a default user — silently falling back
 * to some other identity is how ownership checks get bypassed.
 */
fun ApplicationCall.userId(): String? =
    principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()

/**
 * Mints a short-lived access token.
 *
 * [expiresInMinutes] is not optional by design. A JWT without an `exp` claim is
 * valid forever and cannot be revoked, so a single leaked token — from a shared
 * machine, a proxy log, a screenshot — is a permanent account compromise, and
 * "log out" only deletes the client's copy. Keeping the lifetime short is what
 * makes the stateless design safe; the refresh token carries the long-lived
 * part of the session, and that one *is* revocable.
 */
fun generateJwtToken(
    userId: String,
    email: String,
    secret: String,
    issuer: String,
    audience: String,
    expiresInMinutes: Long
): String {
    val now = Instant.now()
    return JWT.create()
        .withAudience(audience)
        .withIssuer(issuer)
        .withClaim("userId", userId)
        .withClaim("email", email)
        .withIssuedAt(Date.from(now))
        .withExpiresAt(Date.from(now.plus(expiresInMinutes, ChronoUnit.MINUTES)))
        .sign(Algorithm.HMAC256(secret))
}
