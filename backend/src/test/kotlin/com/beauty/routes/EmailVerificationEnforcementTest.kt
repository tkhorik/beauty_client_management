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
 * End-to-end tests for the restriction on unverified accounts.
 *
 * Driven over HTTP against the real module for the same reason as
 * `OrganizationIsolationTest`: the property under test is "the *endpoint*
 * refuses". The gate lives inside `requireOrgAccess`/`requireActiveAccount`, so
 * a unit test of the policy — which exists separately in
 * `auth/VerificationPolicyTest` — cannot tell you whether any given route
 * actually consults it.
 *
 * The suite is organised around the two halves of the feature that can each
 * break silently:
 *  - **the wall** — every organization-scoped route refuses an unverified
 *    account, reads included. A regression here is a privacy hole and produces
 *    no error anywhere.
 *  - **the escape hatches** — the handful of routes a walled-off user must
 *    still reach to get out of the wall. A regression here strands the user
 *    with a screen whose buttons do nothing, which is worse than not shipping
 *    the feature.
 *
 * Most tests run with `graceDays = 0` so "unverified" and "restricted"
 * coincide without any waiting. The grace window itself is tested against a
 * fixed clock in the unit test; duplicating that here would mean either a
 * sleep or a fake clock threaded through the application, both worse than the
 * split.
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
     * Clears the verified flag, the way `004_email_verification_enforcement.sql`
     * does to the whole table on the day enforcement is switched on.
     *
     * Needed because an unverified account can no longer *join* an
     * organization — the wall covers that route too. Setting a membership up
     * therefore means verifying, joining, and then dropping back to
     * unverified, which is not a contrivance: it is exactly the state every
     * existing member is in the moment that migration runs.
     */
    private suspend fun unverify(email: String) {
        dbQuery {
            UsersTable.update({ UsersTable.email eq email }) {
                it[emailVerifiedAt] = null
            }
        }
    }

    /**
     * Backdates an account's creation, making it a *legacy* account in the
     * policy's eyes — one that predates enforcement and therefore gets a grace
     * window rather than an immediate wall.
     *
     * There is no other way to reach that state in a test: a row inserted by
     * `/register` during the test run is necessarily newer than any
     * `enforcedFrom` the app can already be running with.
     */
    private suspend fun backdateCreation(email: String, to: LocalDateTime) {
        dbQuery {
            UsersTable.update({ UsersTable.email eq email }) {
                it[createdAt] = to
            }
        }
    }

    private suspend fun userIdOf(email: String): String = dbQuery {
        UsersTable.select { UsersTable.email eq email }.single()[UsersTable.id]
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

    /**
     * Puts [email] into [orgId] as an approved member and leaves them
     * unverified — the state the wall is about.
     */
    private suspend fun ApplicationTestBuilder.joinAsUnverifiedMember(
        email: String,
        memberToken: String,
        adminToken: String,
        orgId: String,
        slug: String
    ) {
        verify(email)
        val join = client.post("/api/organizations/join-requests") {
            bearerAuth(memberToken)
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"$slug"}""")
        }
        assertEquals(HttpStatusCode.OK, join.status, "join request failed: ${join.bodyAsText()}")

        val approval = client.post("/api/organizations/$orgId/members/${userIdOf(email)}/approval") {
            bearerAuth(adminToken)
            header(ORG_HEADER, orgId)
        }
        assertEquals(HttpStatusCode.OK, approval.status, "approval failed: ${approval.bodyAsText()}")

        unverify(email)
    }

    private suspend fun ApplicationTestBuilder.postClient(token: String, orgId: String, name: String) =
        client.post("/api/clients") {
            bearerAuth(token)
            header(ORG_HEADER, orgId)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","phone":"+1 555 0100"}""")
        }

    private suspend fun ApplicationTestBuilder.getClients(token: String, orgId: String) =
        client.get("/api/clients") {
            bearerAuth(token)
            header(ORG_HEADER, orgId)
        }

    /** The `code` field of an error body, or null if the body has none. */
    private suspend fun HttpResponse.code(): String? =
        runCatching {
            Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content
        }.getOrNull()

    // -----------------------------------------------------------------------
    // The wall
    // -----------------------------------------------------------------------

    @Test
    fun `an unverified member is refused reads as well as writes`() = testApplication {
        startApp()

        // Alice verifies so she can set the organization up; Bob ends up in it
        // as an approved member whose address has never been confirmed.
        val alice = register("alice@example.com")
        verify("alice@example.com")
        val orgId = createOrg(alice, "salon-a")
        assertEquals(HttpStatusCode.Created, postClient(alice, orgId, "Real Client").status)

        val bob = register("bob@example.com")
        joinAsUnverifiedMember("bob@example.com", bob, alice, orgId, "salon-a")

        // The read is the case that regressed silently under the old
        // write-only gate: Bob is a genuine member of this salon, so nothing
        // else in the stack refuses him. Only the verification gate does.
        val read = getClients(bob, orgId)
        assertEquals(HttpStatusCode.Forbidden, read.status, "an unverified member must not read client records")
        assertEquals("EMAIL_NOT_VERIFIED", read.code())

        val write = postClient(bob, orgId, "Bob Client")
        assertEquals(HttpStatusCode.Forbidden, write.status)
        val body = Json.parseToJsonElement(write.bodyAsText()).jsonObject
        assertEquals("EMAIL_NOT_VERIFIED", body["code"]!!.jsonPrimitive.content)
        assertNotNull(body["verificationDeadline"], "clients need the deadline to explain the refusal")
    }

    @Test
    fun `a brand-new signup is restricted from its very first request`() = testApplication {
        // The requirement in one test: verification is mandatory, not
        // eventually mandatory. `graceDays = 7` is deliberately generous here —
        // if the grace window leaked into new registrations, this would pass
        // for a week and then start failing in production.
        startApp(enforcedFrom = LocalDateTime.now().minusDays(30), graceDays = 7)

        val admin = register("admin@example.com")
        verify("admin@example.com")
        val orgId = createOrg(admin, "salon-a")

        val newcomer = register("newcomer@example.com")
        val response = client.get("/api/organizations") { bearerAuth(newcomer) }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("EMAIL_NOT_VERIFIED", response.code())
        assertEquals(HttpStatusCode.Forbidden, getClients(newcomer, orgId).status)
    }

    @Test
    fun `an unverified user cannot list or join organizations`() = testApplication {
        // These two used to be open on purpose, so that an invited user landed
        // inside the organization read-only rather than stranded outside it.
        // Read-only is no longer a state this system has, so the exemption
        // would now only let a walled account accumulate memberships it cannot
        // use.
        startApp()

        val alice = register("alice@example.com")
        verify("alice@example.com")
        createOrg(alice, "salon-a")

        val bob = register("bob@example.com")

        val list = client.get("/api/organizations") { bearerAuth(bob) }
        assertEquals(HttpStatusCode.Forbidden, list.status)
        assertEquals("EMAIL_NOT_VERIFIED", list.code())

        val join = client.post("/api/organizations/join-requests") {
            bearerAuth(bob)
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"salon-a"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, join.status)
        assertEquals("EMAIL_NOT_VERIFIED", join.code())
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

    @Test
    fun `verifying lifts every restriction without signing in again`() = testApplication {
        startApp()

        val alice = register("alice@example.com")
        verify("alice@example.com")
        val orgId = createOrg(alice, "salon-a")

        val bob = register("bob@example.com")
        joinAsUnverifiedMember("bob@example.com", bob, alice, orgId, "salon-a")

        assertEquals(HttpStatusCode.Forbidden, getClients(bob, orgId).status)
        assertEquals(HttpStatusCode.Forbidden, postClient(bob, orgId, "Before").status)

        verify("bob@example.com")

        // Same access token as before. The gate reads the database on every
        // request rather than trusting a claim in the JWT, so clicking the link
        // in another tab takes effect immediately — no re-login, no waiting for
        // the 15-minute token to lapse. That is what makes the wall's
        // "I've verified" button work at all.
        assertEquals(
            HttpStatusCode.OK,
            getClients(bob, orgId).status,
            "verification must restore reads on the very next request"
        )
        assertEquals(
            HttpStatusCode.Created,
            postClient(bob, orgId, "After").status,
            "verification must restore writes on the very next request"
        )
    }

    // -----------------------------------------------------------------------
    // The rollout safety valves
    // -----------------------------------------------------------------------

    @Test
    fun `enforcement is off unless configured`() = testApplication {
        // The kill switch, and the reason no existing test suite needed
        // changing: with the variable unset the app behaves exactly as it did
        // before this feature existed.
        startApp(enforcedFrom = null)

        val alice = register("alice@example.com")
        val orgId = createOrg(alice, "salon-a")

        assertEquals(HttpStatusCode.OK, getClients(alice, orgId).status)
        assertEquals(
            HttpStatusCode.Created,
            postClient(alice, orgId, "Unverified But Allowed").status
        )
    }

    @Test
    fun `an account that predates enforcement keeps full access inside its grace window`() = testApplication {
        // The other half of the design, and the one that keeps a switch-on from
        // locking out every existing salon at once. Note the backdating: an
        // account created *after* enforcement began would be walled
        // immediately, which is the test above.
        val enforcedFrom = LocalDateTime.now().minusDays(1)
        startApp(enforcedFrom = enforcedFrom, graceDays = 7)

        val alice = register("alice@example.com")
        verify("alice@example.com")
        val orgId = createOrg(alice, "salon-a")

        val legacy = register("legacy@example.com")
        joinAsUnverifiedMember("legacy@example.com", legacy, alice, orgId, "salon-a")
        backdateCreation("legacy@example.com", enforcedFrom.minusMonths(6))

        assertEquals(
            HttpStatusCode.OK,
            getClients(legacy, orgId).status,
            "a pre-existing member must not be cut off the day enforcement is switched on"
        )
        assertEquals(
            HttpStatusCode.Created,
            postClient(legacy, orgId, "Within Grace").status,
            "the grace window is what stops a rollout locking everyone out on day one"
        )
    }

    // -----------------------------------------------------------------------
    // The escape hatches
    //
    // Everything a walled-off user needs in order to stop being walled off.
    // These routes resolve the caller with `call.userId()` and never touch
    // `requireOrgAccess`, so the exemption is structural rather than a flag —
    // but "structural" is exactly the kind of claim that stops being true
    // during a refactor, which is why each one is pinned here.
    // -----------------------------------------------------------------------

    @Test
    fun `a restricted user can still read their own profile`() = testApplication {
        startApp()

        val token = register("alice@example.com")
        val response = client.get("/api/users/me") { bearerAuth(token) }

        // The wall polls this to notice the link was clicked in another tab.
        // Refusing it would make the "I've verified" button permanently wrong.
        assertEquals(HttpStatusCode.OK, response.status, "the wall re-checks verification through this route")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(false, body["emailVerified"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("alice@example.com", body["email"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a restricted user can still request a new verification email`() = testApplication {
        startApp()

        val token = register("alice@example.com")
        val response = client.post("/api/auth/resend-verification") { bearerAuth(token) }

        // If this ever 403s, the wall becomes a dead end: the only account
        // that needs a resend is by definition the one being refused.
        assertEquals(HttpStatusCode.NoContent, response.status, "the resend button must work from behind the wall")
    }

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
    fun `a restricted user can still sign out everywhere`() = testApplication {
        startApp()

        val token = register("alice@example.com")
        val response = client.post("/api/auth/logout-all") { bearerAuth(token) }

        // The wall offers a sign-out. Someone who registered with a typo'd
        // address has no other way off the screen.
        assertEquals(HttpStatusCode.NoContent, response.status)
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

        // Registration still hands back a full session on purpose: the client
        // needs an access token to call `resend-verification` and to poll its
        // own profile from behind the wall. The session is what the wall is
        // rendered *inside*, not a way around it.
        val user = Json.parseToJsonElement(response.bodyAsText()).jsonObject["user"]!!.jsonObject
        assertEquals(false, user["emailVerified"]!!.jsonPrimitive.content.toBoolean())
        assertNotNull(
            user["verificationDeadline"]?.jsonPrimitive?.contentOrNull,
            "the client decides between the wall and the banner from this field"
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
