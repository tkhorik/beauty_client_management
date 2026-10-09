package com.beauty.routes

import com.beauty.auth.OrgCreationTokenService
import com.beauty.auth.OrgInviteLinkService
import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.UserOrganizationsTable
import com.beauty.module
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Single-use invite links: an admin issues one, the holder confirms, and they
 * are an active member with no further approval.
 */
class InviteLinkFlowTest {

    private fun ApplicationTestBuilder.startApp() {
        environment {
            config = MapApplicationConfig(
                "app.environment" to "development",
                "app.uploadDir" to "build/test-uploads",
                "db.driver" to "org.h2.Driver",
                "db.url" to "jdbc:h2:mem:invitelinks-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "db.user" to "sa",
                "db.password" to ""
            )
        }
        application { module() }
    }

    private fun json(response: String) = Json.parseToJsonElement(response)

    private suspend fun ApplicationTestBuilder.register(email: String): String {
        val response = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"a-long-enough-password","fullName":"Test User"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status, "registration failed: ${response.bodyAsText()}")
        return json(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.userId(token: String): String =
        json(client.get("/api/users/me") { bearerAuth(token) }.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

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

    private suspend fun ApplicationTestBuilder.issueLinkRaw(token: String, orgId: String) =
        client.post("/api/organizations/$orgId/invite-links") {
            bearerAuth(token)
            header("X-Org-Id", orgId)
        }

    /** Issues a link over HTTP and returns the raw token, after checking the URL carries it. */
    private suspend fun ApplicationTestBuilder.issueLink(adminToken: String, orgId: String): String {
        val response = issueLinkRaw(adminToken, orgId)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val body = json(response.bodyAsText()).jsonObject
        val raw = body["token"]!!.jsonPrimitive.content
        assertTrue(body["url"]!!.jsonPrimitive.content.endsWith("/?invite=$raw"))
        return raw
    }

    private suspend fun ApplicationTestBuilder.accept(token: String, link: String) =
        client.post("/api/organizations/invite-links/accept") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"token":"$link"}""")
        }

    private suspend fun ApplicationTestBuilder.listLinks(adminToken: String, orgId: String): JsonArray =
        json(client.get("/api/organizations/$orgId/invite-links") {
            bearerAuth(adminToken)
            header("X-Org-Id", orgId)
        }.bodyAsText()).jsonArray

    private suspend fun ApplicationTestBuilder.adminPost(adminToken: String, orgId: String, path: String) =
        client.post("/api/organizations/$orgId/$path") {
            bearerAuth(adminToken)
            header("X-Org-Id", orgId)
        }

    private suspend fun ApplicationTestBuilder.auditActions(adminToken: String, orgId: String): List<String> =
        json(client.get("/api/organizations/$orgId/audit") {
            bearerAuth(adminToken)
            header("X-Org-Id", orgId)
        }.bodyAsText()).jsonArray.map { it.jsonObject["action"]!!.jsonPrimitive.content }

    private suspend fun membershipStatus(userId: String, orgId: String): String? = dbQuery {
        UserOrganizationsTable.select {
            (UserOrganizationsTable.userId eq userId) and (UserOrganizationsTable.organizationId eq orgId)
        }.singleOrNull()?.get(UserOrganizationsTable.status)
    }

    @Test
    fun `accepting a link joins at once, as a plain member, with no approval`() = testApplication {
        startApp()
        val admin = register("admin@example.com")
        val orgId = createOrg(admin, "salon-a")
        val link = issueLink(admin, orgId)
        val newcomer = register("new@example.com")

        val preview = json(client.get("/api/organizations/invite-links/preview?token=$link") { bearerAuth(newcomer) }.bodyAsText()).jsonObject
        assertTrue(preview["valid"]!!.jsonPrimitive.boolean)
        assertEquals("salon-a", preview["organization"]!!.jsonObject["slug"]!!.jsonPrimitive.content)

        val accepted = accept(newcomer, link)
        assertEquals(HttpStatusCode.OK, accepted.status, accepted.bodyAsText())
        val org = json(accepted.bodyAsText()).jsonObject
        assertEquals("ACTIVE", org["status"]!!.jsonPrimitive.content)
        assertEquals("ORG_USER", org["role"]!!.jsonPrimitive.content)

        val clients = client.get("/api/clients") {
            bearerAuth(newcomer)
            header("X-Org-Id", orgId)
        }
        assertEquals(HttpStatusCode.OK, clients.status)
        assertTrue(listLinks(admin, orgId).isEmpty(), "a used link is no longer outstanding")
        assertEquals(listOf("INVITE_LINK_ACCEPTED", "INVITE_LINK_CREATED", "ORG_CREATED"), auditActions(admin, orgId))
    }

    @Test
    fun `a link admits exactly one person`() = testApplication {
        startApp()
        val admin = register("admin@example.com")
        val orgId = createOrg(admin, "salon-a")
        val link = issueLink(admin, orgId)
        val first = register("first@example.com")
        val second = register("second@example.com")

        assertEquals(HttpStatusCode.OK, accept(first, link).status)
        val again = accept(second, link)
        assertEquals(HttpStatusCode.NotFound, again.status)
        assertEquals("INVITE_LINK_INVALID", json(again.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(null, membershipStatus(userId(second), orgId))

        val preview = json(client.get("/api/organizations/invite-links/preview?token=$link") { bearerAuth(second) }.bodyAsText()).jsonObject
        assertFalse(preview["valid"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `revoked and expired links admit nobody`() = testApplication {
        startApp()
        val admin = register("admin@example.com")
        val orgId = createOrg(admin, "salon-a")
        val newcomer = register("new@example.com")

        issueLink(admin, orgId)
        val linkId = listLinks(admin, orgId).single().jsonObject["id"]!!.jsonPrimitive.content
        // The raw token is gone after issue; revoke a second one whose token we keep.
        val kept = issueLink(admin, orgId)
        val keptId = listLinks(admin, orgId).map { it.jsonObject["id"]!!.jsonPrimitive.content }.first { it != linkId }
        assertEquals(HttpStatusCode.OK, adminPost(admin, orgId, "invite-links/$keptId/revoke").status)
        assertEquals(HttpStatusCode.NotFound, accept(newcomer, kept).status)
        assertEquals(HttpStatusCode.NotFound, adminPost(admin, orgId, "invite-links/$keptId/revoke").status)

        val (_, expired) = OrgInviteLinkService().issue(orgId, userId(admin), now = LocalDateTime.now().minusDays(8))
        assertEquals(HttpStatusCode.NotFound, accept(newcomer, expired).status)
        assertEquals(null, membershipStatus(userId(newcomer), orgId))
    }

    @Test
    fun `a suspended member cannot use a link to get back in, and the link survives`() = testApplication {
        startApp()
        val admin = register("admin@example.com")
        val orgId = createOrg(admin, "salon-a")
        val member = register("member@example.com")
        val memberId = userId(member)
        assertEquals(HttpStatusCode.OK, accept(member, issueLink(admin, orgId)).status)
        assertEquals(HttpStatusCode.OK, adminPost(admin, orgId, "members/$memberId/revoke").status)

        val link = issueLink(admin, orgId)
        val refused = accept(member, link)
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("MEMBERSHIP_SUSPENDED", json(refused.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("SUSPENDED", membershipStatus(memberId, orgId))

        assertEquals(HttpStatusCode.OK, accept(register("other@example.com"), link).status)
    }

    @Test
    fun `an existing member is told so and does not spend the link`() = testApplication {
        startApp()
        val admin = register("admin@example.com")
        val orgId = createOrg(admin, "salon-a")
        val link = issueLink(admin, orgId)

        val refused = accept(admin, link)
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("ALREADY_A_MEMBER", json(refused.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(1, listLinks(admin, orgId).size)
    }

    @Test
    fun `a link overrides a pending or declined request`() = testApplication {
        startApp()
        val admin = register("admin@example.com")
        val orgId = createOrg(admin, "salon-a")
        val pending = register("pending@example.com")
        val declined = register("declined@example.com")
        for (token in listOf(pending, declined)) {
            client.post("/api/organizations/join-requests") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"slug":"salon-a"}""")
            }
        }
        assertEquals(HttpStatusCode.OK, adminPost(admin, orgId, "members/${userId(declined)}/decline").status)

        assertEquals(HttpStatusCode.OK, accept(pending, issueLink(admin, orgId)).status)
        assertEquals(HttpStatusCode.OK, accept(declined, issueLink(admin, orgId)).status)
        assertEquals("ACTIVE", membershipStatus(userId(pending), orgId))
        assertEquals("ACTIVE", membershipStatus(userId(declined), orgId))
    }

    @Test
    fun `only an admin can issue links`() = testApplication {
        startApp()
        val admin = register("admin@example.com")
        val orgId = createOrg(admin, "salon-a")
        val member = register("member@example.com")
        assertEquals(HttpStatusCode.OK, accept(member, issueLink(admin, orgId)).status)

        assertEquals(HttpStatusCode.Forbidden, issueLinkRaw(member, orgId).status)
    }
}
