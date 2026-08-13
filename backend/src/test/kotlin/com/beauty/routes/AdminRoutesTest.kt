package com.beauty.routes

import com.beauty.auth.GlobalRole
import com.beauty.db.UsersTable
import com.beauty.module
import com.beauty.plugins.ORG_HEADER
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests for the admin panel's backend surface: `AdminRoutes.kt`
 * and the suspension enforcement wired into `OrgAccess.kt`.
 *
 * Promoting a test user to `SUPER_ADMIN` reaches directly into the database
 * rather than going through an API, because there is no such API — see the
 * class doc on `AdminRoutes.kt` for why that is deliberate. This mirrors
 * exactly how the real bootstrap works (`SUPER_ADMIN_EMAILS` at startup, or a
 * manual `UPDATE`).
 */
class AdminRoutesTest {

    private fun ApplicationTestBuilder.startApp() {
        environment {
            config = MapApplicationConfig(
                "app.environment" to "development",
                "app.uploadDir" to "build/test-uploads",
                "db.driver" to "org.h2.Driver",
                "db.url" to "jdbc:h2:mem:admintest-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "db.user" to "sa",
                "db.password" to ""
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

    private suspend fun ApplicationTestBuilder.userId(token: String): String {
        val me = client.get("/api/users/me") { bearerAuth(token) }
        return Json.parseToJsonElement(me.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** Registers an account and promotes it to SUPER_ADMIN directly in the database. */
    private suspend fun ApplicationTestBuilder.registerSuperAdmin(email: String): Pair<String, String> {
        val token = register(email)
        val id = userId(token)
        transaction {
            UsersTable.update({ UsersTable.id eq id }) {
                it[globalRole] = GlobalRole.SUPER_ADMIN.name
            }
        }
        return token to id
    }

    private suspend fun ApplicationTestBuilder.mintCreationToken(adminToken: String, maxUses: Int = 5): String {
        val response = client.post("/api/admin/organization-creation-tokens") {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody("""{"maxUses":$maxUses,"expiresInHours":24}""")
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    // -----------------------------------------------------------------------
    // Authorization boundary
    // -----------------------------------------------------------------------

    @Test
    fun `a plain user cannot reach any admin endpoint`() = testApplication {
        startApp()
        val alice = register("alice@example.com")

        val users = client.get("/api/admin/users") { bearerAuth(alice) }
        assertEquals(HttpStatusCode.Forbidden, users.status)
        assertEquals(
            "SUPER_ADMIN_REQUIRED",
            Json.parseToJsonElement(users.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
        )

        val orgs = client.get("/api/admin/organizations") { bearerAuth(alice) }
        assertEquals(HttpStatusCode.Forbidden, orgs.status)

        val links = client.get("/api/admin/organization-creation-tokens") { bearerAuth(alice) }
        assertEquals(HttpStatusCode.Forbidden, links.status)
    }

    @Test
    fun `an org_admin of their own organization is still not a super admin`() = testApplication {
        startApp()
        // A plain ORG_ADMIN must not be conflated with SUPER_ADMIN — the two
        // are unrelated axes (see auth/Roles.kt).
        val alice = register("alice@example.com")
        val (adminToken, _) = registerSuperAdmin("bootstrap@example.com")
        val token = mintCreationToken(adminToken)

        val createOrg = client.post("/api/organizations") {
            bearerAuth(alice)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"org-a","slug":"org-a","creationToken":"$token"}""")
        }
        assertEquals(HttpStatusCode.Created, createOrg.status)

        val response = client.get("/api/admin/users") { bearerAuth(alice) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // -----------------------------------------------------------------------
    // User listing and suspension
    // -----------------------------------------------------------------------

    @Test
    fun `a super admin can list every account in the system`() = testApplication {
        startApp()
        val (adminToken, _) = registerSuperAdmin("admin@example.com")
        register("alice@example.com")
        register("bob@example.com")

        val response = client.get("/api/admin/users") { bearerAuth(adminToken) }
        assertEquals(HttpStatusCode.OK, response.status)

        val emails = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            .map { it.jsonObject["email"]!!.jsonPrimitive.content }
        assertTrue(emails.containsAll(listOf("admin@example.com", "alice@example.com", "bob@example.com")))
    }

    @Test
    fun `suspending a user blocks org-scoped access on the very next request`() = testApplication {
        startApp()
        val (adminToken, _) = registerSuperAdmin("admin@example.com")
        val alice = register("alice@example.com")
        val aliceId = userId(alice)
        val token = mintCreationToken(adminToken)

        val org = client.post("/api/organizations") {
            bearerAuth(alice)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"org-a","slug":"org-a","creationToken":"$token"}""")
        }
        val orgId = Json.parseToJsonElement(org.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        // Alice has working access before suspension.
        val before = client.get("/api/clients") { bearerAuth(alice); header(ORG_HEADER, orgId) }
        assertEquals(HttpStatusCode.OK, before.status)

        val suspend = client.patch("/api/admin/users/$aliceId") {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody("""{"suspended":true}""")
        }
        assertEquals(HttpStatusCode.OK, suspend.status)

        // Alice's access token is still unexpired. It no longer works anyway,
        // because suspension is read from the database on every request —
        // the same immediate-revocation mechanism organization removal uses.
        val after = client.get("/api/clients") { bearerAuth(alice); header(ORG_HEADER, orgId) }
        assertEquals(HttpStatusCode.Forbidden, after.status)
        assertEquals(
            "ACCOUNT_SUSPENDED",
            Json.parseToJsonElement(after.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `suspension also blocks the no-org routes, not just org-scoped data`() = testApplication {
        startApp()
        // These three endpoints resolve the caller with requireActiveAccount(),
        // not requireOrgAccess() — they exist so a brand-new account can list,
        // join, or create its first organization without one already selected.
        // A suspended account must not be able to use any of them either:
        // listing is a read of the account's own standing, but join-requests
        // and (especially) creating a brand-new organization are exactly the
        // kind of side effect suspension exists to stop.
        val (adminToken, _) = registerSuperAdmin("admin@example.com")
        val alice = register("alice@example.com")
        val aliceId = userId(alice)
        val token = mintCreationToken(adminToken)

        client.patch("/api/admin/users/$aliceId") {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody("""{"suspended":true}""")
        }

        val listing = client.get("/api/organizations") { bearerAuth(alice) }
        assertEquals(HttpStatusCode.Forbidden, listing.status)
        assertEquals(
            "ACCOUNT_SUSPENDED",
            Json.parseToJsonElement(listing.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
        )

        val create = client.post("/api/organizations") {
            bearerAuth(alice)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"org-a","slug":"org-a","creationToken":"$token"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, create.status)
        assertEquals(
            "ACCOUNT_SUSPENDED",
            Json.parseToJsonElement(create.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
        )

        val join = client.post("/api/organizations/join-requests") {
            bearerAuth(alice)
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"whatever"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, join.status)
        assertEquals(
            "ACCOUNT_SUSPENDED",
            Json.parseToJsonElement(join.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `suspension revokes refresh tokens so a new access token cannot be minted`() = testApplication {
        startApp()
        val (adminToken, _) = registerSuperAdmin("admin@example.com")

        val loginResponse = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"bob@example.com","password":"a-long-enough-password","fullName":"Bob"}""")
        }
        val body = Json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject
        val bobId = body["user"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val bobRefreshToken = body["refreshToken"]!!.jsonPrimitive.content

        client.patch("/api/admin/users/$bobId") {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody("""{"suspended":true}""")
        }

        val refreshAttempt = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$bobRefreshToken"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, refreshAttempt.status)
    }

    @Test
    fun `unsuspending restores access for a fresh login`() = testApplication {
        startApp()
        val (adminToken, _) = registerSuperAdmin("admin@example.com")
        val alice = register("alice@example.com")
        val aliceId = userId(alice)

        client.patch("/api/admin/users/$aliceId") {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody("""{"suspended":true}""")
        }
        client.patch("/api/admin/users/$aliceId") {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody("""{"suspended":false}""")
        }

        // Sessions are not restored — Alice signs in again, same as any logout.
        val freshLogin = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alice@example.com","password":"a-long-enough-password"}""")
        }
        assertEquals(HttpStatusCode.OK, freshLogin.status)
        val freshToken = Json.parseToJsonElement(freshLogin.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content

        val me = client.get("/api/users/me") { bearerAuth(freshToken) }
        assertEquals(HttpStatusCode.OK, me.status)
    }

    @Test
    fun `a super admin cannot suspend their own account`() = testApplication {
        startApp()
        val (adminToken, adminId) = registerSuperAdmin("admin@example.com")

        val response = client.patch("/api/admin/users/$adminId") {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody("""{"suspended":true}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    // -----------------------------------------------------------------------
    // Organization-creation link management
    // -----------------------------------------------------------------------

    @Test
    fun `a newly issued link is immediately usable to create an organization`() = testApplication {
        startApp()
        val (adminToken, _) = registerSuperAdmin("admin@example.com")
        val alice = register("alice@example.com")
        val token = mintCreationToken(adminToken, maxUses = 1)

        val response = client.post("/api/organizations") {
            bearerAuth(alice)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"org-a","slug":"org-a","creationToken":"$token"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    @Test
    fun `the listing never includes the raw token`() = testApplication {
        startApp()
        val (adminToken, _) = registerSuperAdmin("admin@example.com")
        val rawToken = mintCreationToken(adminToken)

        val listing = client.get("/api/admin/organization-creation-tokens") { bearerAuth(adminToken) }
        assertEquals(HttpStatusCode.OK, listing.status)
        assertTrue(
            !listing.bodyAsText().contains(rawToken),
            "the raw token must never appear once issued — only its hash is stored"
        )

        val entry = Json.parseToJsonElement(listing.bodyAsText()).jsonArray.single().jsonObject
        assertEquals(5, entry["maxUses"]!!.jsonPrimitive.int)
        assertEquals(0, entry["usesCount"]!!.jsonPrimitive.int)
        assertEquals("null", entry["revokedAt"].toString(), "a fresh link must not be revoked")
    }

    @Test
    fun `revoking a link stops it from working even with uses remaining`() = testApplication {
        startApp()
        val (adminToken, _) = registerSuperAdmin("admin@example.com")
        val alice = register("alice@example.com")

        val issueResponse = client.post("/api/admin/organization-creation-tokens") {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody("""{"maxUses":5,"expiresInHours":24}""")
        }
        val issued = Json.parseToJsonElement(issueResponse.bodyAsText()).jsonObject
        val rawToken = issued["token"]!!.jsonPrimitive.content
        val id = issued["info"]!!.jsonObject["id"]!!.jsonPrimitive.content

        val revoke = client.delete("/api/admin/organization-creation-tokens/$id") { bearerAuth(adminToken) }
        assertEquals(HttpStatusCode.OK, revoke.status)

        val attempt = client.post("/api/organizations") {
            bearerAuth(alice)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"org-a","slug":"org-a","creationToken":"$rawToken"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, attempt.status)
    }

    @Test
    fun `rejects a link request with no upper bound on uses or an unbounded expiry`() = testApplication {
        startApp()
        val (adminToken, _) = registerSuperAdmin("admin@example.com")

        val zeroUses = client.post("/api/admin/organization-creation-tokens") {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody("""{"maxUses":0,"expiresInHours":24}""")
        }
        assertEquals(HttpStatusCode.BadRequest, zeroUses.status)

        val hugeExpiry = client.post("/api/admin/organization-creation-tokens") {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody("""{"maxUses":1,"expiresInHours":999999}""")
        }
        assertEquals(HttpStatusCode.BadRequest, hugeExpiry.status)
    }

    @Test
    fun `every organization in the system is visible to a super admin regardless of membership`() = testApplication {
        startApp()
        val (adminToken, _) = registerSuperAdmin("admin@example.com")
        val alice = register("alice@example.com")
        val token = mintCreationToken(adminToken)

        client.post("/api/organizations") {
            bearerAuth(alice)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"org-a","slug":"org-a","creationToken":"$token"}""")
        }

        val response = client.get("/api/admin/organizations") { bearerAuth(adminToken) }
        assertEquals(HttpStatusCode.OK, response.status)

        val slugs = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            .map { it.jsonObject["slug"]!!.jsonPrimitive.content }
        assertTrue(slugs.contains("org-a"), "the admin panel is not itself a member of org-a and must still see it")
    }
}
