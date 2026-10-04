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
import kotlin.test.assertNull

/** Exercises the public JSON and browser routes against the same token store. */
class EmailVerificationRouteTest {
    private val publicOrigin = "https://verification.example:8443"

    private fun ApplicationTestBuilder.startApp() {
        environment {
            config = MapApplicationConfig(
                "app.environment" to "development",
                "app.publicUrl" to publicOrigin,
                "app.uploadDir" to "build/test-uploads",
                "db.driver" to "org.h2.Driver",
                "db.url" to "jdbc:h2:mem:verification-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "db.user" to "sa",
                "db.password" to ""
            )
        }
        application { module() }
    }

    private data class Registered(val userId: String, val accessToken: String)

    private suspend fun ApplicationTestBuilder.register(email: String = "owner@example.com"): Registered {
        val response = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"a-long-enough-password","fullName":"Test User"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return Registered(
            body["user"]!!.jsonObject["id"]!!.jsonPrimitive.content,
            body["token"]!!.jsonPrimitive.content
        )
    }

    private suspend fun ApplicationTestBuilder.assertVerified(user: Registered, expected: Boolean) {
        val response = client.get("/api/users/me") { bearerAuth(user.accessToken) }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(expected.toString(), body["emailVerified"]!!.jsonPrimitive.content)
    }

    private suspend fun issueToken(
        user: Registered,
        purpose: TokenPurpose = TokenPurpose.EMAIL_VERIFICATION,
        ttl: Duration = Duration.ofMinutes(60)
    ): String = OneTimeTokenService().issue(user.userId, purpose, ttl)

    // Deliberately no bearer token or cookie jar: possession of the email token
    // is sufficient, including when the recipient is signed out of the app.
    private suspend fun ApplicationTestBuilder.postVerification(token: String) =
        postBody("""{"token":"$token"}""")

    private suspend fun ApplicationTestBuilder.postBody(body: String) =
        client.post("/api/auth/verify-email") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.getVerification(token: String? = null): HttpResponse {
        val browser = createClient { followRedirects = false }
        return browser.get("/api/auth/verify-email") {
            token?.let { parameter("token", it) }
            // Redirects must use configured SITE_URL, never caller-controlled Host.
            header(HttpHeaders.Host, "untrusted.example")
        }
    }

    private suspend fun assertInvalid(response: HttpResponse) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            Json.parseToJsonElement("""{"error":"Invalid verification token","code":"INVALID_VERIFICATION_TOKEN"}"""),
            Json.parseToJsonElement(response.bodyAsText())
        )
    }

    private fun assertRedirect(response: HttpResponse, status: String) {
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("$publicOrigin/verify-email?status=$status", response.headers[HttpHeaders.Location])
    }

    @Test
    fun unauthenticatedPostVerifiesOnlyTokenOwnerAndPreventsPostAndGetReplay() = testApplication {
        startApp()
        val owner = register()
        val other = register("other@example.com")
        assertVerified(owner, false)
        val token = issueToken(owner)

        val response = postVerification(token)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            Json.parseToJsonElement("""{"verified":true}"""),
            Json.parseToJsonElement(response.bodyAsText())
        )
        assertNull(response.headers[HttpHeaders.SetCookie], "verification must not issue a session")
        assertNull(response.headers[HttpHeaders.Location], "the app endpoint must return JSON, not redirect")
        assertVerified(owner, true)
        assertVerified(other, false)
        assertInvalid(postVerification(token))
        assertRedirect(getVerification(token), "invalid")
    }

    @Test
    fun browserGetPreservesRedirectAndPreventsGetAndPostReplay() = testApplication {
        startApp()
        val user = register()
        val token = issueToken(user)

        assertRedirect(getVerification(token), "success")
        assertVerified(user, true)
        assertInvalid(postVerification(token))
        assertRedirect(getVerification(token), "invalid")
    }

    @Test
    fun blankMissingMalformedAndUnknownPostTokensFailWithoutVerifyingAccount() = testApplication {
        startApp()
        val user = register()
        val valid = issueToken(user)

        listOf(
            "{}",
            """{"token":""}""",
            """{"token":"   "}""",
            """{"token":null}""",
            """{"token":{}}""",
            """{"token":"not-a-real-token"}""",
            "{"
        ).forEach { body -> assertInvalid(postBody(body)) }
        assertVerified(user, false)
        assertEquals(HttpStatusCode.OK, postVerification(valid).status, "invalid requests must not spend a valid link")
        assertVerified(user, true)
    }

    @Test
    fun browserMissingBlankAndUnknownTokensKeepInvalidRedirect() = testApplication {
        startApp()
        val user = register()

        listOf(null, "", "   ", "not-a-real-token").forEach { token ->
            assertRedirect(getVerification(token), "invalid")
        }
        assertVerified(user, false)
    }

    @Test
    fun expiredTokensAreRejectedByBothRoutes() = testApplication {
        startApp()
        val user = register()
        val token = issueToken(user, ttl = Duration.ofMinutes(-1))

        assertInvalid(postVerification(token))
        assertRedirect(getVerification(token), "invalid")
        assertVerified(user, false)
    }

    @Test
    fun passwordResetTokenCannotVerifyAndRemainsUsableForItsOwnPurpose() = testApplication {
        startApp()
        val user = register()
        val token = issueToken(user, TokenPurpose.PASSWORD_RESET)

        assertInvalid(postVerification(token))
        assertRedirect(getVerification(token), "invalid")
        assertVerified(user, false)

        val reset = client.post("/api/auth/reset-password") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"$token","newPassword":"a-brand-new-password"}""")
        }
        assertEquals(HttpStatusCode.OK, reset.status, "wrong-purpose attempts must not consume the reset token")
        assertVerified(user, false)
    }
}
