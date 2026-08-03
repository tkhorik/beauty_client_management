package com.beauty.routes

import com.beauty.auth.OrgCreationTokenService
import com.beauty.module
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end tests for the restriction added to `POST /api/organizations`:
 * organization creation is no longer self-service and now requires a valid
 * admin-issued creation token. [OrganizationIsolationTest] already exercises
 * the multi-tenant behavior *after* an organization exists — this file is
 * about the gate itself, at the HTTP boundary.
 */
class OrgCreationTokenGatingTest {

    private fun ApplicationTestBuilder.startApp() {
        environment {
            config = MapApplicationConfig(
                "app.environment" to "development",
                "app.uploadDir" to "build/test-uploads",
                "db.driver" to "org.h2.Driver",
                "db.url" to "jdbc:h2:mem:creationgating-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "db.user" to "sa",
                "db.password" to ""
            )
        }
        application { module() }
    }

    private suspend fun ApplicationTestBuilder.register(email: String): String {
        val response = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"email":"$email","password":"a-long-enough-password","fullName":"Test User"}"""
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, "registration failed: ${response.bodyAsText()}")
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.userId(token: String): String {
        val me = client.get("/api/users/me") { bearerAuth(token) }
        return Json.parseToJsonElement(me.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.mintToken(
        issuerToken: String,
        maxUses: Int = 1,
        expiresAt: LocalDateTime = LocalDateTime.now().plusDays(1)
    ): String {
        val (_, raw) = OrgCreationTokenService().issue(
            createdBy = userId(issuerToken),
            label = null,
            maxUses = maxUses,
            expiresAt = expiresAt
        )
        return raw
    }

    private suspend fun ApplicationTestBuilder.attemptCreate(userToken: String, slug: String, creationToken: String?) =
        client.post("/api/organizations") {
            bearerAuth(userToken)
            contentType(ContentType.Application.Json)
            val tokenField = if (creationToken != null) ""","creationToken":"$creationToken"""" else ""
            setBody("""{"name":"$slug","slug":"$slug"$tokenField}""")
        }

    @Test
    fun `creating an organization with no token is refused`() = testApplication {
        startApp()
        val alice = register("alice@example.com")

        val response = attemptCreate(alice, "org-a", creationToken = null)

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(
            "CREATION_TOKEN_INVALID",
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `creating an organization with a garbage token is refused`() = testApplication {
        startApp()
        val alice = register("alice@example.com")

        val response = attemptCreate(alice, "org-a", creationToken = "not-a-real-token")

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `a valid token allows creation and becomes its first admin`() = testApplication {
        startApp()
        val alice = register("alice@example.com")
        val bootstrapper = register("bootstrapper@example.com")
        val token = mintToken(bootstrapper)

        val response = attemptCreate(alice, "org-a", token)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        assertEquals("ORG_ADMIN", Json.parseToJsonElement(response.bodyAsText()).jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a single-use token cannot create a second organization`() = testApplication {
        startApp()
        val alice = register("alice@example.com")
        val bob = register("bob@example.com")
        val bootstrapper = register("bootstrapper@example.com")
        val token = mintToken(bootstrapper, maxUses = 1)

        val first = attemptCreate(alice, "org-a", token)
        assertEquals(HttpStatusCode.Created, first.status)

        val second = attemptCreate(bob, "org-b", token)
        assertEquals(HttpStatusCode.Forbidden, second.status)
    }

    @Test
    fun `a multi-use token allows exactly its configured number of organizations`() = testApplication {
        startApp()
        val alice = register("alice@example.com")
        val bob = register("bob@example.com")
        val carol = register("carol@example.com")
        val bootstrapper = register("bootstrapper@example.com")
        val token = mintToken(bootstrapper, maxUses = 2)

        assertEquals(HttpStatusCode.Created, attemptCreate(alice, "org-a", token).status)
        assertEquals(HttpStatusCode.Created, attemptCreate(bob, "org-b", token).status)
        // Third caller finds the link already at its use limit.
        assertEquals(HttpStatusCode.Forbidden, attemptCreate(carol, "org-c", token).status)
    }

    @Test
    fun `an expired token is refused`() = testApplication {
        startApp()
        val alice = register("alice@example.com")
        val bootstrapper = register("bootstrapper@example.com")
        val expiredToken = mintToken(bootstrapper, expiresAt = LocalDateTime.now().minusMinutes(1))

        val response = attemptCreate(alice, "org-a", expiredToken)

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `a revoked token is refused even with uses remaining`() = testApplication {
        startApp()
        val alice = register("alice@example.com")
        val bootstrapper = register("bootstrapper@example.com")
        val service = OrgCreationTokenService()
        val (id, token) = service.issue(userId(bootstrapper), null, maxUses = 5, expiresAt = LocalDateTime.now().plusDays(1))
        service.revoke(id)

        val response = attemptCreate(alice, "org-a", token)

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `an invalid name still fails validation before the token is spent`() = testApplication {
        startApp()
        val alice = register("alice@example.com")
        val bootstrapper = register("bootstrapper@example.com")
        val token = mintToken(bootstrapper, maxUses = 1)

        // Blank name is rejected by field validation, which runs before the
        // token is redeemed.
        val badAttempt = attemptCreate(alice, "", token)
        assertEquals(HttpStatusCode.BadRequest, badAttempt.status)

        // The token must still be good for the real attempt right after.
        val goodAttempt = attemptCreate(alice, "org-a", token)
        assertEquals(HttpStatusCode.Created, goodAttempt.status, goodAttempt.bodyAsText())
    }

    @Test
    fun `validate endpoint reports validity without spending a use`() = testApplication {
        startApp()
        val alice = register("alice@example.com")
        val bootstrapper = register("bootstrapper@example.com")
        val token = mintToken(bootstrapper, maxUses = 1)

        val before = client.get("/api/organizations/creation-tokens/validate?token=$token") { bearerAuth(alice) }
        assertTrue(
            Json.parseToJsonElement(before.bodyAsText()).jsonObject["valid"]!!.jsonPrimitive.boolean,
            "a fresh token must validate"
        )

        // Checking validity must not itself have consumed the link.
        val response = attemptCreate(alice, "org-a", token)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())

        val after = client.get("/api/organizations/creation-tokens/validate?token=$token") { bearerAuth(alice) }
        assertFalse(
            Json.parseToJsonElement(after.bodyAsText()).jsonObject["valid"]!!.jsonPrimitive.boolean,
            "an exhausted token must no longer validate"
        )
    }

    @Test
    fun `an unauthenticated caller cannot create an organization even with a valid token`() = testApplication {
        startApp()
        val bootstrapper = register("bootstrapper@example.com")
        val token = mintToken(bootstrapper)

        val response = client.post("/api/organizations") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"org-a","slug":"org-a","creationToken":"$token"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
