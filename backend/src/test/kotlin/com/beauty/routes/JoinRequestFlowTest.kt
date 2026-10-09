package com.beauty.routes

import com.beauty.auth.OrgCreationTokenService
import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.UserOrganizationsTable
import com.beauty.db.UsersTable
import com.beauty.module
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
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
 * The incoming-account half of organization onboarding, at the HTTP boundary:
 * asking to join while signing up, an admin approving or declining, the
 * re-request cooldown after a decline, the audit trail, and the admin's
 * pending-request count.
 */
class JoinRequestFlowTest {

    private fun ApplicationTestBuilder.startApp() {
        environment {
            config = MapApplicationConfig(
                "app.environment" to "development",
                "app.uploadDir" to "build/test-uploads",
                "db.driver" to "org.h2.Driver",
                "db.url" to "jdbc:h2:mem:joinflow-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "db.user" to "sa",
                "db.password" to ""
            )
        }
        application { module() }
    }

    private fun json(response: String) = Json.parseToJsonElement(response)

    private suspend fun ApplicationTestBuilder.registerRaw(email: String, organizationSlug: String? = null): HttpResponse =
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            val slugField = if (organizationSlug != null) ""","organizationSlug":"$organizationSlug"""" else ""
            setBody("""{"email":"$email","password":"a-long-enough-password","fullName":"Test User"$slugField}""")
        }

    private suspend fun ApplicationTestBuilder.register(email: String, organizationSlug: String? = null): String {
        val response = registerRaw(email, organizationSlug)
        assertEquals(HttpStatusCode.Created, response.status, "registration failed: ${response.bodyAsText()}")
        return json(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.userId(token: String): String =
        json(client.get("/api/users/me") { bearerAuth(token) }.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

    /** Creates an organization owned by [adminToken] and returns its id. */
    private suspend fun ApplicationTestBuilder.createOrg(adminToken: String, slug: String): String {
        val (_, raw) = OrgCreationTokenService().issue(
            createdBy = userId(adminToken),
            label = null,
            maxUses = 1,
            expiresAt = LocalDateTime.now().plusDays(1)
        )
        val response = client.post("/api/organizations") {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$slug","slug":"$slug","creationToken":"$raw"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.myOrganizations(token: String): List<JsonObject> =
        json(client.get("/api/organizations") { bearerAuth(token) }.bodyAsText()).jsonArray.map { it.jsonObject }

    private suspend fun ApplicationTestBuilder.join(token: String, slug: String) =
        client.post("/api/organizations/join-requests") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"$slug"}""")
        }

    private suspend fun ApplicationTestBuilder.adminPost(adminToken: String, orgId: String, path: String) =
        client.post("/api/organizations/$orgId/members/$path") {
            bearerAuth(adminToken)
            header("X-Org-Id", orgId)
        }

    private suspend fun ApplicationTestBuilder.auditActions(adminToken: String, orgId: String): List<String> {
        val response = client.get("/api/organizations/$orgId/audit") {
            bearerAuth(adminToken)
            header("X-Org-Id", orgId)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return (json(response.bodyAsText()) as JsonArray).map { it.jsonObject["action"]!!.jsonPrimitive.content }
    }

    private suspend fun membershipStatus(userId: String, orgId: String): String? = dbQuery {
        UserOrganizationsTable.select {
            (UserOrganizationsTable.userId eq userId) and (UserOrganizationsTable.organizationId eq orgId)
        }.singleOrNull()?.get(UserOrganizationsTable.status)
    }

    @Test
    fun `signing up with a handle files a pending request that grants nothing`() = testApplication {
        startApp()
        val admin = register("admin@example.com")
        val orgId = createOrg(admin, "salon-a")

        val newcomer = register("new@example.com", organizationSlug = "Salon-A ")

        val mine = myOrganizations(newcomer)
        assertEquals(1, mine.size)
        assertEquals("PENDING", mine.single()["status"]!!.jsonPrimitive.content)

        val clients = client.get("/api/clients") {
            bearerAuth(newcomer)
            header("X-Org-Id", orgId)
        }
        assertEquals(HttpStatusCode.Forbidden, clients.status)
        assertTrue("JOIN_REQUESTED" in auditActions(admin, orgId))
    }

    @Test
    fun `signing up with an unknown handle is a field error and creates no account`() = testApplication {
        startApp()

        val response = registerRaw("ghost@example.com", organizationSlug = "no-such-salon")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = json(response.bodyAsText()).jsonObject
        assertEquals(
            "ORGANIZATION_NOT_FOUND",
            body["fieldErrors"]!!.jsonObject["organizationSlug"]!!.jsonObject["code"]!!.jsonPrimitive.content
        )
        val exists = dbQuery { UsersTable.select { UsersTable.email eq "ghost@example.com" }.any() }
        assertTrue(!exists, "a rejected registration must not leave an account behind")
    }

    @Test
    fun `signing up with a blank handle behaves like no handle`() = testApplication {
        startApp()
        val token = register("plain@example.com", organizationSlug = "  ")
        assertTrue(myOrganizations(token).isEmpty())
    }

    @Test
    fun `approval grants access and is audited`() = testApplication {
        startApp()
        val admin = register("admin@example.com")
        val orgId = createOrg(admin, "salon-a")
        val newcomer = register("new@example.com", organizationSlug = "salon-a")
        val newcomerId = userId(newcomer)

        assertEquals(HttpStatusCode.OK, adminPost(admin, orgId, "$newcomerId/approval").status)

        val clients = client.get("/api/clients") {
            bearerAuth(newcomer)
            header("X-Org-Id", orgId)
        }
        assertEquals(HttpStatusCode.OK, clients.status)
        assertEquals(listOf("APPROVED", "JOIN_REQUESTED", "ORG_CREATED"), auditActions(admin, orgId))
    }

    @Test
    fun `a declined request is visible to the requester and leaves the roster`() = testApplication {
        startApp()
        val admin = register("admin@example.com")
        val orgId = createOrg(admin, "salon-a")
        val newcomer = register("new@example.com", organizationSlug = "salon-a")
        val newcomerId = userId(newcomer)

        assertEquals(HttpStatusCode.OK, adminPost(admin, orgId, "$newcomerId/decline").status)

        val mine = myOrganizations(newcomer).single()
        assertEquals("DECLINED", mine["status"]!!.jsonPrimitive.content)
        assertNotNull(mine["retryAfter"])

        val roster = client.get("/api/organizations/$orgId/members") {
            bearerAuth(admin)
            header("X-Org-Id", orgId)
        }
        assertTrue(json(roster.bodyAsText()).jsonArray.none {
            it.jsonObject["userId"]!!.jsonPrimitive.content == newcomerId
        })

        val clients = client.get("/api/clients") {
            bearerAuth(newcomer)
            header("X-Org-Id", orgId)
        }
        assertEquals(HttpStatusCode.Forbidden, clients.status)
        assertTrue("DECLINED" in auditActions(admin, orgId))
    }

    @Test
    fun `a declined requester must wait out the cooldown before asking again`() = testApplication {
        startApp()
        val admin = register("admin@example.com")
        val orgId = createOrg(admin, "salon-a")
        val newcomer = register("new@example.com", organizationSlug = "salon-a")
        val newcomerId = userId(newcomer)
        adminPost(admin, orgId, "$newcomerId/decline")

        val tooSoon = join(newcomer, "salon-a")
        assertEquals(HttpStatusCode.Conflict, tooSoon.status)
        val body = json(tooSoon.bodyAsText()).jsonObject
        assertEquals("REQUEST_DECLINED", body["code"]!!.jsonPrimitive.content)
        assertNotNull(body["retryAfter"])
        assertEquals("DECLINED", membershipStatus(newcomerId, orgId))

        dbQuery {
            UserOrganizationsTable.update({
                (UserOrganizationsTable.userId eq newcomerId) and (UserOrganizationsTable.organizationId eq orgId)
            }) { it[decidedAt] = LocalDateTime.now().minusDays(8) }
        }

        val later = join(newcomer, "salon-a")
        assertEquals(HttpStatusCode.OK, later.status, later.bodyAsText())
        assertEquals("PENDING", membershipStatus(newcomerId, orgId))
    }

    @Test
    fun `an admin can invite a declined user without waiting`() = testApplication {
        startApp()
        val admin = register("admin@example.com")
        val orgId = createOrg(admin, "salon-a")
        val newcomer = register("new@example.com", organizationSlug = "salon-a")
        val newcomerId = userId(newcomer)
        adminPost(admin, orgId, "$newcomerId/decline")

        val invite = client.post("/api/organizations/$orgId/members/invitations") {
            bearerAuth(admin)
            header("X-Org-Id", orgId)
            contentType(ContentType.Application.Json)
            setBody("""{"email":"new@example.com","role":"ORG_USER"}""")
        }
        assertEquals(HttpStatusCode.OK, invite.status, invite.bodyAsText())

        // Asking now is accepting the invitation.
        assertEquals("ACTIVE", json(join(newcomer, "salon-a").bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `only a pending request can be declined`() = testApplication {
        startApp()
        val admin = register("admin@example.com")
        val orgId = createOrg(admin, "salon-a")
        val newcomer = register("new@example.com", organizationSlug = "salon-a")
        val newcomerId = userId(newcomer)
        adminPost(admin, orgId, "$newcomerId/approval")

        assertEquals(HttpStatusCode.NotFound, adminPost(admin, orgId, "$newcomerId/decline").status)
        assertEquals("ACTIVE", membershipStatus(newcomerId, orgId))
    }

    @Test
    fun `a plain member cannot decline or read the audit log`() = testApplication {
        startApp()
        val admin = register("admin@example.com")
        val orgId = createOrg(admin, "salon-a")
        val member = register("member@example.com", organizationSlug = "salon-a")
        adminPost(admin, orgId, "${userId(member)}/approval")
        val newcomer = register("new@example.com", organizationSlug = "salon-a")
        val newcomerId = userId(newcomer)

        assertEquals(HttpStatusCode.Forbidden, adminPost(member, orgId, "$newcomerId/decline").status)
        assertEquals("PENDING", membershipStatus(newcomerId, orgId))

        val audit = client.get("/api/organizations/$orgId/audit") {
            bearerAuth(member)
            header("X-Org-Id", orgId)
        }
        assertEquals(HttpStatusCode.Forbidden, audit.status)
    }

    @Test
    fun `an admin of another organization cannot decline here`() = testApplication {
        startApp()
        val adminA = register("a@example.com")
        val orgA = createOrg(adminA, "salon-a")
        val adminB = register("b@example.com")
        val orgB = createOrg(adminB, "salon-b")
        val newcomer = register("new@example.com", organizationSlug = "salon-a")
        val newcomerId = userId(newcomer)

        // Header names the organization B's admin runs; path names A's.
        val mismatched = client.post("/api/organizations/$orgA/members/$newcomerId/decline") {
            bearerAuth(adminB)
            header("X-Org-Id", orgB)
        }
        assertEquals(HttpStatusCode.Forbidden, mismatched.status)
        assertEquals("PENDING", membershipStatus(newcomerId, orgA))
    }

    @Test
    fun `admins see a pending count and plain members do not`() = testApplication {
        startApp()
        val admin = register("admin@example.com")
        val orgId = createOrg(admin, "salon-a")
        val member = register("member@example.com", organizationSlug = "salon-a")
        adminPost(admin, orgId, "${userId(member)}/approval")
        register("one@example.com", organizationSlug = "salon-a")
        register("two@example.com", organizationSlug = "salon-a")

        assertEquals(2, myOrganizations(admin).single()["pendingRequestCount"]!!.jsonPrimitive.int)
        assertNull(myOrganizations(member).single()["pendingRequestCount"])
    }

    @Test
    fun `re-asking while pending is idempotent and files no second audit entry`() = testApplication {
        startApp()
        val admin = register("admin@example.com")
        val orgId = createOrg(admin, "salon-a")
        val newcomer = register("new@example.com", organizationSlug = "salon-a")

        val again = join(newcomer, "salon-a")
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals("PENDING", json(again.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(1, auditActions(admin, orgId).count { it == "JOIN_REQUESTED" })
    }
}
