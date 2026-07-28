package com.beauty.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.beauty.config.AppSettings
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

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

fun generateJwtToken(userId: String, email: String, secret: String, issuer: String, audience: String): String {
    return JWT.create()
        .withAudience(audience)
        .withIssuer(issuer)
        .withClaim("userId", userId)
        .withClaim("email", email)
        .sign(Algorithm.HMAC256(secret))
}
