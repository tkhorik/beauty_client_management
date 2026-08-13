package com.beauty.routes

import com.beauty.auth.AccountState
import com.beauty.auth.GlobalRole
import com.beauty.auth.OneTimeTokenService
import com.beauty.auth.RefreshTokenService
import com.beauty.auth.TokenPurpose
import com.beauty.auth.VerificationPolicy
import com.beauty.config.AppSettings
import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.UsersTable
import com.beauty.mail.AccountMailer
import com.beauty.mail.MailSender
import com.beauty.models.*
import com.beauty.plugins.RATE_LIMIT_AUTH
import com.beauty.plugins.RATE_LIMIT_EMAIL
import com.beauty.plugins.generateJwtToken
import com.beauty.plugins.userId
import com.beauty.validation.Validation
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.mindrot.jbcrypt.BCrypt
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
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

internal fun ApplicationCall.usesCookieTransport(): Boolean =
    request.headers[TRANSPORT_HEADER]?.equals(TRANSPORT_COOKIE, ignoreCase = true) == true

/**
 * Sends the refresh token by whichever route the client asked for, and
 * returns the value to embed in the JSON body (null when it went into a
 * cookie — putting it in both places would defeat the point of httpOnly).
 *
 * Top-level (not nested in [authRoutes]) so every endpoint that mints or
 * rotates a session — login, register, refresh, and password change in
 * `UserRoutes.kt` — shares one definition of the Secure/SameSite/path rules.
 * Duplicating this per-route is exactly how one of them ends up wrong.
 */
internal fun ApplicationCall.deliverRefreshToken(rawToken: String, settings: AppSettings): String? {
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

internal fun ApplicationCall.clearRefreshCookie() {
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

/**
 * The single mapping from a `users` row to the DTO clients see.
 *
 * Every route that returns a user goes through this. When `UserDto` gained
 * `emailVerified` there were four separate hand-written constructions, and a
 * default of `false` on the field means a missed one is a *silent* wrong
 * answer, not a compile error. One definition removes that failure mode.
 */
internal fun userDto(
    row: org.jetbrains.exposed.sql.ResultRow,
    policy: VerificationPolicy? = null
): UserDto {
    val account = AccountState(
        globalRole = GlobalRole.parse(row[UsersTable.globalRole]),
        emailVerifiedAt = row[UsersTable.emailVerifiedAt],
        createdAt = row[UsersTable.createdAt]
    )
    return UserDto(
        id = row[UsersTable.id],
        email = row[UsersTable.email],
        fullName = row[UsersTable.fullName],
        createdAt = row[UsersTable.createdAt].toString(),
        emailVerified = account.emailVerified,
        // Null when no policy is supplied. That is the honest answer rather
        // than a guess: a caller with no policy in hand cannot know whether
        // enforcement is on, and inventing a deadline would have clients
        // counting down to a restriction that may not exist.
        verificationDeadline = policy?.deadlineFor(account)?.toString()
    )
}

/**
 * Mints a fresh access + refresh token pair and responds with it.
 *
 * Shared by login, register, and password-change (in `UserRoutes.kt`) — every
 * place a brand-new session family should start. `/refresh` does not use this
 * because it rotates an existing family instead of starting a new one.
 */
internal suspend fun ApplicationCall.respondWithNewSession(
    status: HttpStatusCode,
    user: UserDto,
    settings: AppSettings,
    secret: String,
    issuer: String,
    audience: String,
    accessTokenMinutes: Long,
    refreshTokens: RefreshTokenService
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
            refreshToken = deliverRefreshToken(refreshToken, settings),
            expiresInSeconds = accessTokenMinutes * 60,
            user = user
        )
    )
}

fun Route.authRoutes() {
    val settings = AppSettings(application.environment.config)
    val secret = settings.jwtSecret
    val issuer = settings.jwtIssuer
    val audience = settings.jwtAudience
    val accessTokenMinutes = settings.accessTokenMinutes
    val refreshTokens = RefreshTokenService(settings.refreshTokenDays)
    val oneTimeTokens = OneTimeTokenService()
    // `application` is the scope the SMTP sends run in — see AccountMailer.
    val accountMailer = AccountMailer(settings, oneTimeTokens, MailSender.from(settings), application)
    val verification = VerificationPolicy(settings)

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
    ) = respondWithNewSession(status, user, settings, secret, issuer, audience, accessTokenMinutes, refreshTokens)

    route("/api/auth") {
        // Credential endpoints share the looser bucket. `/refresh` and
        // `/logout` are deliberately left unlimited: both are called
        // automatically by every signed-in client, several times an hour, and
        // throttling them would log real users out rather than stop an
        // attacker — who gains nothing by replaying a refresh token, since
        // rotation revokes the whole family on reuse.
        rateLimit(RateLimitName(RATE_LIMIT_AUTH)) {
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

            // Mail is sent after the row is committed, and its outcome is not
            // checked. The account is created and signed in either way: a
            // bounced or slow SMTP server must not fail a registration that
            // has already succeeded, and the new user has a grace window plus
            // /resend-verification to recover with. `MailSender.send` swallows its own
            // errors for the same reason, and AccountMailer hands the SMTP call
            // off to the application scope so a slow server does not hold the
            // new user on a spinner.
            accountMailer.sendVerification(id, email, fullName)

            // One timestamp, used for both the stored row and the response.
            // Computing it twice means the client is told a creation time that
            // is not the one in the database.
            call.respondWithSession(
                HttpStatusCode.Created,
                // emailVerified is explicitly false rather than left to the
                // default: the account was created one line ago and the
                // confirmation mail is still in flight.
                UserDto(
                    id,
                    email,
                    fullName,
                    createdAt.toString(),
                    emailVerified = false,
                    // Told to them up front, in the same response that signs
                    // them in. A user who learns about the deadline on day one
                    // can act on it; one who first meets it as a refused save
                    // on day eight has been ambushed.
                    verificationDeadline = verification.deadlineFor(
                        AccountState(GlobalRole.USER, emailVerifiedAt = null, createdAt = createdAt)
                    )?.toString()
                )
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
                userDto(row, verification)
            )
        }
        } // end rateLimit(RATE_LIMIT_AUTH)

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
                            refreshToken = call.deliverRefreshToken(result.token, settings),
                            expiresInSeconds = accessTokenMinutes * 60,
                            // Recomputed on every refresh — every 15 minutes,
                            // in practice. That is what makes the restriction
                            // lift promptly: a user who clicks the link in
                            // another tab sees the banner clear on the next
                            // token rotation without signing out and back in.
                            user = userDto(row, verification)
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

        // -------------------------------------------------------------------
        // Email verification
        // -------------------------------------------------------------------

        /**
         * Redeems a verification link.
         *
         * A GET, because this is the target of a link clicked in a mail client,
         * and it answers with a redirect back into the web app rather than
         * JSON — the person who clicked is looking at a browser, not a
         * response body. The outcome is carried in a query parameter so the
         * SPA can render the right message.
         *
         * A GET that changes state is normally a CSRF hazard. It is acceptable
         * here precisely because the token in the URL *is* the credential: an
         * attacker who can supply a valid one already has the mail, and
         * "forcing" a victim to verify their own address grants nothing.
         */
        get("/verify-email") {
            val token = call.request.queryParameters["token"].orEmpty()

            val result = oneTimeTokens.redeem(token, TokenPurpose.EMAIL_VERIFICATION)
            if (result !is OneTimeTokenService.Redemption.Redeemed) {
                call.respondRedirect("${settings.publicUrl}/verify-email?status=invalid")
                return@get
            }

            dbQuery {
                UsersTable.update({ UsersTable.id eq result.userId }) {
                    it[emailVerifiedAt] = LocalDateTime.now()
                }
            }
            call.respondRedirect("${settings.publicUrl}/verify-email?status=success")
        }

        // -------------------------------------------------------------------
        // Password reset
        // -------------------------------------------------------------------

        /**
         * Starts a reset.
         *
         * **Always answers 200 with the same body**, whether the address has an
         * account, has none, or is malformed. This endpoint needs no
         * credentials, so any variation in the response — status, body, or a
         * conspicuous difference in timing — is a free oracle for testing which
         * addresses are registered. `/login` already goes out of its way to
         * avoid being one (see [DUMMY_PASSWORD_HASH]); it would be pointless to
         * close that hole and open this one.
         *
         * That is also why validation failures are swallowed rather than
         * returned as a 400: "this is not a valid email" and "no account here"
         * must be indistinguishable from the outside.
         *
         * The two things that used to break that promise are both handled
         * now: the lookup runs for a malformed address as well as a valid one
         * so the work done is the same either way, and `AccountMailer` hands
         * the SMTP call to the application scope rather than awaiting it, so a
         * registered address does not answer a network round trip later than
         * an unregistered one. What is left is the token write, two local
         * indexed statements — orders of magnitude below the variance of the
         * request itself.
         */
        rateLimit(RateLimitName(RATE_LIMIT_EMAIL)) {
        post("/forgot-password") {
            val req = call.receive<ForgotPasswordRequest>()
            val email = Validation.normaliseEmail(req.email)

            // Queried unconditionally. Skipping the lookup for a malformed
            // address would make it answer measurably sooner than a
            // well-formed one — a weaker oracle than the SMTP delay, but the
            // same kind, and a normalised non-address simply matches no row.
            val found = dbQuery { UsersTable.select { UsersTable.email eq email }.singleOrNull() }
            val row = found?.takeIf { Validation.validateEmail(email) == null }

            if (row != null) {
                accountMailer.sendPasswordReset(
                    userId = row[UsersTable.id],
                    email = row[UsersTable.email],
                    fullName = row[UsersTable.fullName]
                )
            } else {
                // Logged server-side only. The requester is told nothing.
                call.application.log.info("Password reset requested for an address with no account.")
            }

            call.respond(
                HttpStatusCode.OK,
                MessageResponse("If an account exists for that address, a reset link is on its way.")
            )
        }
        } // end rateLimit(RATE_LIMIT_EMAIL)

        /**
         * Completes a reset.
         *
         * The order of operations matters and is deliberate:
         *  1. Validate the new password *before* spending the token, so a
         *     too-short password does not burn the user's only link.
         *  2. Redeem the token, which is the atomic single-use gate.
         *  3. Write the new hash.
         *  4. Invalidate any other outstanding reset tokens.
         *  5. Revoke every refresh-token family for the user.
         *  6. Notify the account address.
         *
         * Step 5 is the one most often left out. A reset that leaves existing
         * sessions alive has not locked anyone out: an attacker holding a
         * stolen refresh token keeps their access indefinitely, and the owner
         * believes the problem is solved.
         */
        // Limited too: without it, a valid-looking token could be brute-forced.
        // 256 bits makes that hopeless anyway, but the bound costs nothing.
        rateLimit(RateLimitName(RATE_LIMIT_AUTH)) {
        post("/reset-password") {
            val req = call.receive<ResetPasswordRequest>()

            Validation.validatePassword(req.newPassword)?.let { message ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ValidationErrorResponse(errors = mapOf("newPassword" to message))
                )
                return@post
            }

            val result = oneTimeTokens.redeem(req.token, TokenPurpose.PASSWORD_RESET)
            if (result !is OneTimeTokenService.Redemption.Redeemed) {
                // One message for unknown, expired and already-used. The client
                // can do nothing different in each case, and saying which would
                // help someone probing with guessed tokens.
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "This reset link is invalid or has expired. Please request a new one.")
                )
                return@post
            }

            val newHash = BCrypt.hashpw(req.newPassword, BCrypt.gensalt())
            val row = dbQuery {
                UsersTable.update({ UsersTable.id eq result.userId }) {
                    it[passwordHash] = newHash
                }
                UsersTable.select { UsersTable.id eq result.userId }.singleOrNull()
            }

            oneTimeTokens.invalidateOutstanding(result.userId, TokenPurpose.PASSWORD_RESET)
            refreshTokens.revokeAllForUser(result.userId)
            call.clearRefreshCookie()

            if (row != null) {
                // The password is already changed, so a slow or failing SMTP
                // server must not delay or fail the response. AccountMailer
                // dispatches the send and logs a warning if it cannot be
                // delivered, since this is the notice that makes a takeover
                // visible to the account's owner.
                accountMailer.sendPasswordChangedNotice(
                    email = row[UsersTable.email],
                    fullName = row[UsersTable.fullName]
                )
            }

            // No new session is issued. Making the user sign in with the
            // password they just chose confirms it works and reaches them
            // through the normal login path, rather than handing tokens to
            // whoever happened to hold the link.
            call.respond(
                HttpStatusCode.OK,
                MessageResponse("Your password has been changed. You have been signed out on all devices.")
            )
        }
        } // end rateLimit(RATE_LIMIT_AUTH)
    }
}

/**
 * Auth routes that require a valid access token. Mounted inside the
 * `authenticate("auth-jwt")` block in `Routing.kt`.
 */
fun Route.authenticatedAuthRoutes() {
    val settings = AppSettings(application.environment.config)
    val refreshTokens = RefreshTokenService(settings.refreshTokenDays)
    val oneTimeTokens = OneTimeTokenService()
    // `application` is the scope the SMTP sends run in — see AccountMailer.
    val accountMailer = AccountMailer(settings, oneTimeTokens, MailSender.from(settings), application)

    route("/api/auth") {
        /**
         * Sends a fresh verification link to the signed-in user's own address.
         *
         * Authenticated, and takes no parameters: the address is read from the
         * token, never from the request. An unauthenticated "resend to this
         * address" endpoint would be both an enumeration oracle and a way to
         * make this server send mail to arbitrary strangers.
         *
         * Answers 204 unconditionally, including when the address is already
         * verified — there is nothing useful to distinguish, and issuing a
         * token retires the previous one, so repeated calls are harmless.
         */
        rateLimit(RateLimitName(RATE_LIMIT_EMAIL)) {
        post("/resend-verification") {
            val userId = call.userId()
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                return@post
            }

            val row = dbQuery { UsersTable.select { UsersTable.id eq userId }.singleOrNull() }
            if (row != null && row[UsersTable.emailVerifiedAt] == null) {
                accountMailer.sendVerification(
                    userId = userId,
                    email = row[UsersTable.email],
                    fullName = row[UsersTable.fullName]
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }
        } // end rateLimit(RATE_LIMIT_EMAIL)

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
