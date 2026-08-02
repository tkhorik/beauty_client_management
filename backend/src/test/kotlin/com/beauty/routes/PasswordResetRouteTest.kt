package com.beauty.routes

import com.beauty.auth.OneTimeTokenService
import com.beauty.auth.TokenPurpose
import com.beauty.module
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests for the password-reset endpoints.
 *
 * `OneTimeTokenServiceTest` already covers the token primitive — single use,
 * expiry, purpose binding, nothing raw in the database. What it cannot cover is
 * whether the *routes* still depend on those properties, and that is where this
 * kind of feature historically breaks: the service stays correct while a route
 * stops checking the purpose, starts returning a different status for an
 * unknown address, or forgets to revoke sessions. Each test here pins one
 * property an attacker would otherwise get for free.
 *
 * Harness matches `OrganizationIsolationTest`: the real module against a
 * per-test in-memory H2, driven over HTTP, with raw JSON strings rather than a
 * typed client.
 *
 * Note on rate limits: `RATE_LIMIT_EMAIL` allows 3 requests per minute and
 * `RATE_LIMIT_AUTH` 10. Buckets are per-application, and each test builds its
 * own, but a test that adds requests carelessly will start seeing 429s — keep
 * the counts below deliberate.
 */
class PasswordResetRouteTest {

    private val password = "a-long-enough-password"

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    private fun ApplicationTestBuilder.startApp() {
        environment {
            config = MapApplicationConfig(
                "app.environment" to "development",
                "app.uploadDir" to "build/test-uploads",
                "db.driver" to "org.h2.Driver",
                "db.url" to "jdbc:h2:mem:reset-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "db.user" to "sa",
                "db.password" to ""
                // mail.host is left unset, so MailSender.from() selects
                // LogMailSender. Nothing here asserts on delivery: the reset
                // tokens these tests redeem are minted directly through the
                // service, because the raw value only ever exists inside the
                // email and is deliberately unrecoverable from the database.
            )
        }
        application { module() }
    }

    private data class Registered(val userId: String, val refreshToken: String)

    /** Registers an account and returns the ids the tests need. */
    private suspend fun ApplicationTestBuilder.register(email: String): Registered {
        val response = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"$password","fullName":"Test User"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status, "registration failed: ${response.bodyAsText()}")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return Registered(
            userId = body["user"]!!.jsonObject["id"]!!.jsonPrimitive.content,
            refreshToken = body["refreshToken"]!!.jsonPrimitive.content
        )
    }

    private suspend fun ApplicationTestBuilder.forgotPassword(email: String) =
        client.post("/api/auth/forgot-password") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email"}""")
        }

    private suspend fun ApplicationTestBuilder.resetPassword(token: String, newPassword: String) =
        client.post("/api/auth/reset-password") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"$token","newPassword":"$newPassword"}""")
        }

    private suspend fun ApplicationTestBuilder.login(email: String, withPassword: String) =
        client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"$withPassword"}""")
        }

    /**
     * Mints a token the way the mailer would.
     *
     * Safe to call against the application's database because
     * `OneTimeTokenService` holds no state of its own — everything it needs is
     * in `one_time_tokens`, so this instance and the route's instance are
     * interchangeable.
     */
    private suspend fun issueToken(
        userId: String,
        purpose: TokenPurpose,
        ttl: Duration = Duration.ofMinutes(60)
    ): String = OneTimeTokenService().issue(userId, purpose, ttl)

    // -----------------------------------------------------------------------
    // /forgot-password must not become an account-enumeration oracle
    // -----------------------------------------------------------------------

    /**
     * The whole point of the endpoint's design. If a registered address can be
     * told apart from an unregistered or malformed one by status or body, the
     * care taken over `/login`'s dummy hash is wasted — an attacker just asks
     * this endpoint instead.
     *
     * Timing is the third channel and is not asserted here: a wall-clock
     * assertion would be flaky on CI. It is addressed structurally instead —
     * the lookup runs unconditionally and the mail is dispatched off the
     * request path — and this test at least fails if the *shape* of the
     * response ever diverges.
     */
    @Test
    fun forgotPasswordAnswersIdenticallyForKnownUnknownAndMalformedAddresses() = testApplication {
        startApp()
        register("known@example.com")

        val known = forgotPassword("known@example.com")
        val unknown = forgotPassword("nobody@example.com")
        val malformed = forgotPassword("not-an-email")

        val bodies = listOf(known, unknown, malformed).map { it.bodyAsText() }
        listOf(known, unknown, malformed).forEach {
            assertEquals(HttpStatusCode.OK, it.status, "every outcome must answer 200")
        }
        assertEquals(bodies[0], bodies[1], "known and unknown addresses must be indistinguishable")
        assertEquals(bodies[0], bodies[2], "malformed input must not produce a validation error")
    }

    // -----------------------------------------------------------------------
    // Token handling
    // -----------------------------------------------------------------------

    /**
     * The account-takeover case, and the reason `redeem` matches the purpose
     * rather than merely reading it.
     *
     * A verification token is mailed on every signup, lives for 24 hours, and
     * is treated as low-value precisely because it grants nothing. If it were
     * accepted here it would grant everything.
     */
    @Test
    fun resetRejectsATokenIssuedForEmailVerification() = testApplication {
        startApp()
        val user = register("verify@example.com")

        val verificationToken = issueToken(user.userId, TokenPurpose.EMAIL_VERIFICATION)
        val response = resetPassword(verificationToken, "a-brand-new-password")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            HttpStatusCode.OK,
            login("verify@example.com", password).status,
            "the original password must still work — the reset must not have happened"
        )
    }

    /**
     * Unknown, expired and already-used must be one indistinguishable failure.
     * Telling them apart tells someone probing with guessed tokens whether they
     * are getting warm.
     */
    @Test
    fun expiredUsedAndUnknownTokensFailIdentically() = testApplication {
        startApp()
        val user = register("spent@example.com")

        val expired = issueToken(user.userId, TokenPurpose.PASSWORD_RESET, Duration.ofMinutes(-1))
        val expiredResponse = resetPassword(expired, "a-brand-new-password")

        // A fresh token, spent, then presented a second time.
        val reused = issueToken(user.userId, TokenPurpose.PASSWORD_RESET)
        assertEquals(HttpStatusCode.OK, resetPassword(reused, "a-brand-new-password").status)
        val reusedResponse = resetPassword(reused, "another-new-password")

        val unknownResponse = resetPassword("not-a-real-token", "a-brand-new-password")

        listOf(expiredResponse, reusedResponse, unknownResponse).forEach {
            assertEquals(HttpStatusCode.BadRequest, it.status)
        }
        assertEquals(expiredResponse.bodyAsText(), reusedResponse.bodyAsText())
        assertEquals(expiredResponse.bodyAsText(), unknownResponse.bodyAsText())
    }

    /**
     * Validation runs before redemption, so a password the server rejects does
     * not cost the user their only link. Getting this order wrong is a support
     * burden rather than a vulnerability, but it is invisible until someone
     * types a short password and finds their link dead.
     */
    @Test
    fun aRejectedPasswordDoesNotSpendTheToken() = testApplication {
        startApp()
        val user = register("retry@example.com")
        val token = issueToken(user.userId, TokenPurpose.PASSWORD_RESET)

        val tooShort = resetPassword(token, "short")
        assertEquals(HttpStatusCode.BadRequest, tooShort.status)
        assertTrue(
            tooShort.bodyAsText().contains("newPassword"),
            "the client needs a field-level error to render: ${tooShort.bodyAsText()}"
        )

        assertEquals(
            HttpStatusCode.OK,
            resetPassword(token, "a-brand-new-password").status,
            "the same token must still be spendable after a validation failure"
        )
    }

    // -----------------------------------------------------------------------
    // What a completed reset must actually accomplish
    // -----------------------------------------------------------------------

    /**
     * The step most often left out. A reset that leaves existing sessions alive
     * has not locked anyone out: an attacker holding a stolen refresh token
     * keeps their access indefinitely, while the owner believes the problem is
     * solved. This is the regression net for `revokeAllForUser`.
     */
    @Test
    fun successfulResetRevokesExistingSessionsAndChangesThePassword() = testApplication {
        startApp()
        val user = register("owner@example.com")
        val token = issueToken(user.userId, TokenPurpose.PASSWORD_RESET)

        assertEquals(HttpStatusCode.OK, resetPassword(token, "a-brand-new-password").status)

        val refresh = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"${user.refreshToken}"}""")
        }
        assertEquals(
            HttpStatusCode.Unauthorized,
            refresh.status,
            "a refresh token issued before the reset must no longer work"
        )

        assertEquals(HttpStatusCode.Unauthorized, login("owner@example.com", password).status)
        assertEquals(HttpStatusCode.OK, login("owner@example.com", "a-brand-new-password").status)
    }

    /**
     * No session is issued by the reset itself. Handing tokens to whoever held
     * the link would skip the one step that proves the new password reached the
     * person who is going to use it.
     */
    @Test
    fun resetDoesNotIssueASession() = testApplication {
        startApp()
        val user = register("nosession@example.com")
        val token = issueToken(user.userId, TokenPurpose.PASSWORD_RESET)

        val response = resetPassword(token, "a-brand-new-password")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(!body.contains("\"token\""), "response must carry no access token: $body")
        assertTrue(!body.contains("\"refreshToken\""), "response must carry no refresh token: $body")
    }

    /**
     * Issuing a second link retires the first. Two live reset tokens means two
     * chances for one to leak, for no benefit to a user who is only ever going
     * to click the newest mail.
     */
    @Test
    fun issuingASecondTokenInvalidatesTheFirst() = testApplication {
        startApp()
        val user = register("newest@example.com")

        val first = issueToken(user.userId, TokenPurpose.PASSWORD_RESET)
        val second = issueToken(user.userId, TokenPurpose.PASSWORD_RESET)
        assertNotEquals(first, second)

        assertEquals(HttpStatusCode.BadRequest, resetPassword(first, "a-brand-new-password").status)
        assertEquals(HttpStatusCode.OK, resetPassword(second, "a-brand-new-password").status)
    }
}
