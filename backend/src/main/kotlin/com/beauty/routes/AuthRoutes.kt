package com.beauty.routes

import com.beauty.auth.RefreshTokenService
import com.beauty.config.AppSettings
import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.UsersTable
import com.beauty.models.*
import com.beauty.plugins.generateJwtToken
import com.beauty.plugins.userId
import com.beauty.validation.Validation
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.mindrot.jbcrypt.BCrypt
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import java.time.LocalDateTime
import java.util.UUID

/**
 * A pre-computed BCrypt hash of a value no one will ever submit.
 *
 * Login checks this when the email does not exist, so that a missing account
 * costs the same ~100ms of hashing as a wrong password. Without it, "no such
 * user" returns almost instantly while "wrong password" takes measurably
 * longer, and that difference is a reliable oracle for enumerating which
 * addresses have accounts.
 *
 * Computed once at class-load; BCrypt hashing is intentionally slow and doing
 * it per failed request would itself be a cheap denial-of-service lever.
 */
private val DUMMY_PASSWORD_HASH: String =
    BCrypt.hashpw("timing-equalisation-placeholder", BCrypt.gensalt())

/** PostgreSQL SQLSTATE for a unique-constraint violation. */
private const val SQLSTATE_UNIQUE_VIOLATION = "23505"

/** Name of the httpOnly cookie carrying the refresh token for browser clients. */
private const val REFRESH_COOKIE = "beauty_refresh"

/**
 * Browsers opt into cookie transport with this header.
 *
 * Making the client declare its transport is better than sniffing the
 * User-Agent: it is explicit, it is testable with curl, and it cannot
 * misclassify a client into handing a browser a token that JavaScript can read.
 */
private const val TRANSPORT_HEADER = "X-Auth-Transport"
private const val TRANSPORT_COOKIE = "cookie"

private fun ApplicationCall.usesCookieTransport(): Boolean =
    request.headers[TRANSPORT_HEADER]?.equals(TRANSPORT_COOKIE, ignoreCase = true) == true

fun Route.authRoutes() {
    val settings = AppSettings(application.environment.config)
    val secret = settings.jwtSecret
    val issuer = settings.jwtIssuer
    val audience = settings.jwtAudience
    val accessTokenMinutes = settings.accessTokenMinutes
    val refreshTokens = RefreshTokenService(settings.refreshTokenDays)

    /**
     * Sends the refresh token by whichever route the client asked for, and
     * returns the value to embed in the JSON body (null when it went into a
     * cookie — putting it in both places would defeat the point of httpOnly).
     */
    fun ApplicationCall.deliverRefreshToken(rawToken: String): String? {
        if (!usesCookieTransport()) return rawToken

        // Derived from the actual connection rather than from `isProduction`.
        //
        // Ktor throws if a `Secure` cookie is set on a connection it considers
        // plaintext, so tying the flag to the environment turns any
        // proxy misconfiguration into a 500 on every login. Reading the real
        // scheme means the flag is correct by construction and this can never
        // crash — behind Nginx that scheme comes from X-Forwarded-Proto, via
        // the XForwardedHeaders plugin installed in Routing.kt.
        val overHttps = request.origin.scheme == "https"
        if (settings.isProduction && !overHttps) {
            // Loud, because the refresh cookie is now going out without the
            // Secure flag: the proxy is not forwarding X-Forwarded-Proto.
            application.log.warn(
                "Refresh cookie issued WITHOUT the Secure flag: request scheme is " +
                    "'${request.origin.scheme}', not https. Check that the proxy sets " +
                    "X-Forwarded-Proto and that XForwardedHeaders is installed."
            )
        }

        response.cookies.append(
            Cookie(
                name = REFRESH_COOKIE,
                value = rawToken,
                // Unreadable from JavaScript, so an XSS payload cannot exfiltrate
                // the long-lived half of the session.
                httpOnly = true,
                // Never sent over plain HTTP. False in local development, which
                // is not served over TLS.
                secure = overHttps,
                // The refresh endpoint changes server state, so it needs CSRF
                // protection. `Strict` means the browser will not attach this
                // cookie to any cross-site request, which removes the attack
                // outright — affordable here only because the web app and the
                // API share one origin.
                extensions = mapOf("SameSite" to "Strict"),
                path = "/api/auth",
                maxAge = (settings.refreshTokenDays * 24 * 60 * 60).toInt()
            )
        )
        return null
    }

    fun ApplicationCall.clearRefreshCookie() {
        response.cookies.append(
            Cookie(
                name = REFRESH_COOKIE,
                value = "",
                httpOnly = true,
                // Same reasoning as above: read the real scheme so expiring the
                // cookie can never throw. This runs on the rejection path, where
                // a 500 would mask the actual reason the session ended.
                secure = request.origin.scheme == "https",
                extensions = mapOf("SameSite" to "Strict"),
                path = "/api/auth",
                maxAge = 0
            )
        )
    }

    /** Reads the refresh token from the cookie, falling back to the request body. */
    suspend fun ApplicationCall.readRefreshToken(): String? {
        request.cookies[REFRESH_COOKIE]?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching { receive<RefreshRequest>().refreshToken }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun ApplicationCall.respondWithSession(
        status: HttpStatusCode,
        user: UserDto
    ) {
        val accessToken = generateJwtToken(
            userId = user.id,
            email = user.email,
            secret = secret,
            issuer = issuer,
            audience = audience,
            expiresInMinutes = accessTokenMinutes
        )
        val refreshToken = refreshTokens.issueNewFamily(user.id)

        respond(
            status,
            AuthResponse(
                token = accessToken,
                refreshToken = deliverRefreshToken(refreshToken),
                expiresInSeconds = accessTokenMinutes * 60,
                user = user
            )
        )
    }

    route("/api/auth") {
        post("/register") {
            val req = call.receive<RegisterRequest>()

            // Normalise before validating, so that trailing whitespace and
            // capitalisation are corrected rather than rejected.
            val email = Validation.normaliseEmail(req.email)
            val fullName = req.fullName.trim()

            val errors = Validation.validateRegistration(email, req.password, fullName)
            if (errors.isNotEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ValidationErrorResponse(errors = errors))
                return@post
            }

            val id = UUID.randomUUID().toString()
            val hashedPassword = BCrypt.hashpw(req.password, BCrypt.gensalt())
            val createdAt = LocalDateTime.now()

            // The pre-check is a fast path for the common case, but it is not
            // what guarantees uniqueness: two concurrent registrations can
            // both pass it. The unique index on `email` is the real guarantee,
            // so the insert must handle its violation rather than let it
            // bubble up to StatusPages as a generic 500.
            val alreadyExists = dbQuery {
                UsersTable.select { UsersTable.email eq email }.singleOrNull() != null
            }
            if (alreadyExists) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "User with email already exists"))
                return@post
            }

            try {
                dbQuery {
                    UsersTable.insert {
                        it[UsersTable.id] = id
                        it[UsersTable.email] = email
                        it[UsersTable.passwordHash] = hashedPassword
                        it[UsersTable.fullName] = fullName
                        it[UsersTable.createdAt] = createdAt
                    }
                }
            } catch (e: ExposedSQLException) {
                if ((e.cause as? java.sql.SQLException)?.sqlState == SQLSTATE_UNIQUE_VIOLATION ||
                    e.sqlState == SQLSTATE_UNIQUE_VIOLATION
                ) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "User with email already exists"))
                    return@post
                }
                throw e
            }

            // One timestamp, used for both the stored row and the response.
            // Computing it twice means the client is told a creation time that
            // is not the one in the database.
            call.respondWithSession(
                HttpStatusCode.Created,
                UserDto(id, email, fullName, createdAt.toString())
            )
        }

        post("/login") {
            val req = call.receive<AuthRequest>()

            // Must use the same normalisation as registration, or an account
            // created as `Owner@x.com` can never be logged into.
            val email = Validation.normaliseEmail(req.email)

            val row = dbQuery {
                UsersTable.select { UsersTable.email eq email }.singleOrNull()
            }

            // Always hash, even when the user is absent — see DUMMY_PASSWORD_HASH.
            val storedHash = row?.get(UsersTable.passwordHash) ?: DUMMY_PASSWORD_HASH
            val passwordMatches = BCrypt.checkpw(req.password, storedHash)

            if (row == null || !passwordMatches) {
                // One message for both cases. Saying "no such account" would
                // tell an attacker which addresses are worth guessing at.
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
                return@post
            }

            call.respondWithSession(
                HttpStatusCode.OK,
                UserDto(
                    id = row[UsersTable.id],
                    email = row[UsersTable.email],
                    fullName = row[UsersTable.fullName],
                    createdAt = row[UsersTable.createdAt].toString()
                )
            )
        }

        /**
         * Exchanges a refresh token for a fresh access token, rotating the
         * refresh token in the process.
         *
         * Public by design: the caller has no valid access token — that is the
         * whole reason they are here. The refresh token itself is the credential.
         */
        post("/refresh") {
            val presented = call.readRefreshToken()
            if (presented == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "No refresh token provided"))
                return@post
            }

            when (val result = refreshTokens.rotate(presented)) {
                is RefreshTokenService.RotationResult.Rejected -> {
                    // Clear the cookie so the browser stops resending a token
                    // that will never work again.
                    call.clearRefreshCookie()
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Session expired. Please sign in again."))
                }

                is RefreshTokenService.RotationResult.Rotated -> {
                    val row = dbQuery {
                        UsersTable.select { UsersTable.id eq result.userId }.singleOrNull()
                    }
                    if (row == null) {
                        // The token was valid but its user is gone (deleted
                        // account). Nothing to issue.
                        call.clearRefreshCookie()
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Session expired. Please sign in again."))
                        return@post
                    }

                    val accessToken = generateJwtToken(
                        userId = result.userId,
                        email = row[UsersTable.email],
                        secret = secret,
                        issuer = issuer,
                        audience = audience,
                        expiresInMinutes = accessTokenMinutes
                    )

                    call.respond(
                        HttpStatusCode.OK,
                        AuthResponse(
                            token = accessToken,
                            refreshToken = call.deliverRefreshToken(result.token),
                            expiresInSeconds = accessTokenMinutes * 60,
                            user = UserDto(
                                id = result.userId,
                                email = row[UsersTable.email],
                                fullName = row[UsersTable.fullName],
                                createdAt = row[UsersTable.createdAt].toString()
                            )
                        )
                    )
                }
            }
        }

        /**
         * Revokes the presented refresh token, ending this one session.
         *
         * Always answers 204, whether or not the token was recognised: logout
         * must not become a way to test whether a guessed token is real, and
         * the client's correct behaviour ("forget your credentials") is the
         * same either way.
         */
        post("/logout") {
            call.readRefreshToken()?.let { refreshTokens.revoke(it) }
            call.clearRefreshCookie()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Auth routes that require a valid access token. Mounted inside the
 * `authenticate("auth-jwt")` block in `Routing.kt`.
 */
fun Route.authenticatedAuthRoutes() {
    val settings = AppSettings(application.environment.config)
    val refreshTokens = RefreshTokenService(settings.refreshTokenDays)

    route("/api/auth") {
        /**
         * Signs the user out of every device.
         *
         * Unlike `/logout`, this needs to know *who* is asking, which is why it
         * requires an access token rather than accepting a refresh token: a
         * user should be able to evict a stolen session from a device that
         * still works, without holding the stolen token.
         */
        post("/logout-all") {
            val userId = call.userId()
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                return@post
            }
            refreshTokens.revokeAllForUser(userId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
