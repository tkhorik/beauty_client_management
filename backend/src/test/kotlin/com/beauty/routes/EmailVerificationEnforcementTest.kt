package com.beauty.routes

import com.beauty.auth.OrgCreationTokenService
import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.UsersTable
import com.beauty.module
import com.beauty.plugins.ORG_HEADER
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for the read-only restriction on unverified accounts.
 *
 * Driven over HTTP against the real module for the same reason as
 * `OrganizationIsolationTest`: the property under test is "the *endpoint*
 * refuses". The gate is derived from the HTTP method inside `requireOrgAccess`,
 * so a unit test of the policy — which exists separately in
 * `auth/VerificationPolicyTest` — cannot tell you whether any given route
 * actually consults it.
 *
 * Most tests run with `graceDays = 0` so "unverified" and "restricted" coincide
 * without any waiting. The grace window itself is tested against a fixed clock
 * in the unit test; duplicating that here would mean either a sleep or a fake
 * clock threaded through the application, both worse than the split.
 */
class EmailVerificationEnforcementTest {

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    /**
     * @param enforcedFrom null leaves `mail.verificationEnforcedFrom` unset,
     *   which is how every *other* test suite in this repo runs — and the reason
     *   none of them needed changing when this feature landed. Enforcement is
     *   off unless a deployment explicitly turns it on.
     */
    private fun ApplicationTestBuilder.startApp(
        enforcedFrom: LocalDateTime? = LocalDateTime.now().minusDays(1),
        graceDays: Long = 0
    ) {
        environment {
            config = MapApplicationConfig(
                *buildList {
                    add("app.environment" to "development")
                    add("app.uploadDir" to "build/test-uploads")
                    add("db.driver" to "org.h2.Driver")
                    // MODE=PostgreSQL: `clients.custom_fields` is a jsonb column
                    // and only maps on H2 in PostgreSQL compatibility mode.
                    add("db.url" to "jdbc:h2:mem:verification-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
                    add("db.user" to "sa")
                    add("db.password" to "")
                    add("mail.verificationGraceDays" to graceDays.toString())
                    enforcedFrom?.let { add("mail.verificationEnforcedFrom" to it.toString()) }
                }.toTypedArray()
            )
        }
        application { module() }
    }

    private suspend fun ApplicationTestBuilder.register(email: String): String {
        val response = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"a-long-enough-password","fullName":"Test User"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status, "registration failed: ${response.bodyAsText()}")
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
    }

    /**
     * Marks an address verified by writing the column directly.
     *
     * The alternative — redeeming a real link — would mean scraping the token
     * out of the log sink, which couples the test to the mail formatting. What
     * is under test here is what the *gate* does with a verified account, and
     * `/verify-email`'s own redemption path is covered in
     * `auth/OneTimeTokenServiceTest`.
     */
    private suspend fun verify(email: String) {
        dbQuery {
            UsersTable.update({ UsersTable.email eq email }) {
                it[emailVerifiedAt] = LocalDateTime.now()
            }
        }
    }

    /**
     * Mints an organization-creation token, mirroring `OrganizationIsolationTest`.
     *
     * Organization creation stopped being self-service when the admin panel
     * landed, so every test that needs an organization has to issue one of
     * these first. Called through the service rather than the admin API
     * because the super-admin flow is not what these tests are about.
     */
    private suspend fun ApplicationTestBuilder.mintCreationToken(issuerToken: String): String {
        val me = client.get("/api/users/me") { bearerAuth(issuerToken) }
        val issuerId = Json.parseToJsonElement(me.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val (_, rawToken) = OrgCreationTokenService().issue(
            createdBy = issuerId,
            label = null,
            maxUses = 1,
            expiresAt = LocalDateTime.now().plusDays(1)
        )
        return rawToken
    }

    private suspend fun ApplicationTestBuilder.createOrg(token: String, slug: String): String {
        val creationToken = mintCreationToken(token)
        val response = client.post("/api/organizations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$slug","slug":"$slug","creationToken":"$creationToken"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status, "createOrg failed: ${response.bodyAsText()}")
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.postClient(token: String, orgId: String, name: String) =
        client.post("/api/clients") {
            bearerAuth(token)
            header(ORG_HEADER, orgId)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","phone":"+1 555 0100"}""")
        }

    /** The `code` field of an error body, or null if the body has none. */
    private suspend fun HttpResponse.code(): String? =
        runCatching {
            Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content
        }.getOrNull()

    // -----------------------------------------------------------------------
    // The core restriction
    // -----------------------------------------------------------------------

    @Test
    fun `an unverified user can read but cannot write`() = testApplication {
        startApp()

        // Alice verifies so she can set the organization up; Bob does not.
        val alice = register("alice@example.com")
        verify("alice@example.com")
        val orgId = createOrg(alice, "salon-a")

        // Alice, verified, writes normally.
        assertEquals(HttpStatusCode.Created, postClient(alice, orgId, "Real Client").status)

        // Bob joins and is approved, then finds himself read-only.
        val bob = register("bob@example.com")
        val join = client.post("/api/organizations/join-requests") {
            bearerAuth(bob)
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"salon-a"}""")
        }
        assertEquals(HttpStatusCode.OK, join.status, "joining must stay open to unverified accounts")

        val bobUserId = dbQuery {
            UsersTable.select { UsersTable.email eq "bob@example.com" }.single()[UsersTable.id]
        }
        assertEquals(
            HttpStatusCode.OK,
            client.post("/api/organizations/$orgId/members/$bobUserId/approval") {
                bearerAuth(alice)
                header(ORG_HEADER, orgId)
            }.status
        )

        // Reads: allowed. This is the whole point of read-only rather than a
        // hard block — the salon can still look up a client's history.
        val read = client.get("/api/clients") {
            bearerAuth(bob)
            header(ORG_HEADER, orgId)
        }
        assertEquals(HttpStatusCode.OK, read.status, "an unverified member must keep read access")

        // Writes: refused, with a code the clients can match on.
        val write = postClient(bob, orgId, "Bob Client")
        assertEquals(HttpStatusCode.Forbidden, write.status)
        val body = Json.parseToJsonElement(write.bodyAsText()).jsonObject
        assertEquals("EMAIL_NOT_VERIFIED", body["code"]!!.jsonPrimitive.content)
        assertNotNull(body["verificationDeadline"], "clients need the deadline to explain the refusal")
    }

    @Test
    fun `verifying lifts the restriction without signing in again`() = testApplication {
        startApp()

        val alice = register("alice@example.com")
        verify("alice@example.com")
        val orgId = createOrg(alice, "salon-a")

        val bob = register("bob@example.com")
        client.post("/api/organizations/join-requests") {
            bearerAuth(bob)
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"salon-a"}""")
        }
        val bobUserId = dbQuery {
            UsersTable.select { UsersTable.email eq "bob@example.com" }.single()[UsersTable.id]
        }
        client.post("/api/organizations/$orgId/members/$bobUserId/approval") {
            bearerAuth(alice)
            header(ORG_HEADER, orgId)
        }

        assertEquals(HttpStatusCode.Forbidden, postClient(bob, orgId, "Before").status)

        verify("bob@example.com")

        // Same access token as before. The gate reads the database on every
        // request rather than trusting a claim in the JWT, so clicking the link
        // in another tab takes effect immediately — no re-login, no waiting for
        // the 15-minute token to lapse.
        assertEquals(
            HttpStatusCode.Created,
            postClient(bob, orgId, "After").status,
            "verification must take effect on the very next request"
        )
    }

    @Test
    fun `enforcement is off unless configured`() = testApplication {
        // The kill switch, and the reason no existing test suite needed
        // changing: with the variable unset the app behaves exactly as it did
        // before this feature existed.
        startApp(enforcedFrom = null)

        val alice = register("alice@example.com")
        val orgId = createOrg(alice, "salon-a")

        assertEquals(
            HttpStatusCode.Created,
            postClient(alice, orgId, "Unverified But Allowed").status
        )
    }

    @Test
    fun `an unverified user inside the grace window can still write`() = testApplication {
        startApp(enforcedFrom = LocalDateTime.now().minusHours(1), graceDays = 7)

        val alice = register("alice@example.com")
        val orgId = createOrg(alice, "salon-a")

        assertEquals(
            HttpStatusCode.Created,
            postClient(alice, orgId, "Within Grace").status,
            "the grace window is what stops a rollout locking everyone out on day one"
        )
    }

    // -----------------------------------------------------------------------
    // What stays open
    // -----------------------------------------------------------------------

    @Test
    fun `a restricted user can still change their password`() = testApplication {
        startApp()

        val token = register("alice@example.com")

        // Not a data write, and the action someone takes when they believe
        // their account is compromised. Blocking it would be actively harmful.
        val response = client.post("/api/users/me/password") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"currentPassword":"a-long-enough-password","newPassword":"another-long-password"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, "password change must survive the restriction")
    }

    @Test
    fun `a restricted user cannot create a new organization, even with a valid link`() = testApplication {
        startApp()

        // A *valid* creation token, so the refusal can only be the verification
        // gate. Without this the test would pass on `CREATION_TOKEN_INVALID`
        // and prove nothing about email verification at all.
        val token = register("alice@example.com")
        val creationToken = mintCreationToken(token)

        val response = client.post("/api/organizations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Spam Salon","slug":"spam-salon","creationToken":"$creationToken"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("EMAIL_NOT_VERIFIED", response.code())
    }

    // -----------------------------------------------------------------------
    // What clients are told
    // -----------------------------------------------------------------------

    @Test
    fun `the session response carries the verification state and deadline`() = testApplication {
        startApp(enforcedFrom = LocalDateTime.now().minusDays(1), graceDays = 7)

        val response = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alice@example.com","password":"a-long-enough-password","fullName":"Alice"}""")
        }

        val user = Json.parseToJsonElement(response.bodyAsText()).jsonObject["user"]!!.jsonObject
        assertEquals(false, user["emailVerified"]!!.jsonPrimitive.content.toBoolean())
        assertNotNull(
            user["verificationDeadline"]?.jsonPrimitive?.contentOrNull,
            "a user must be told the deadline up front, not discover it as a refused save"
        )
    }

    @Test
    fun `a verified user is advertised no deadline`() = testApplication {
        startApp()

        register("alice@example.com")
        verify("alice@example.com")

        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alice@example.com","password":"a-long-enough-password"}""")
        }
        val user = Json.parseToJsonElement(login.bodyAsText()).jsonObject["user"]!!.jsonObject

        assertTrue(user["emailVerified"]!!.jsonPrimitive.content.toBoolean())
        assertNull(
            user["verificationDeadline"]?.jsonPrimitive?.contentOrNull,
            "a verified account has nothing to count down to"
        )
    }
}
