package com.beauty.routes

import com.beauty.auth.OrgCreationTokenService
import com.beauty.db.ClientsTable
import com.beauty.module
import com.beauty.plugins.ORG_HEADER
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phone validation on the client routes, driven over HTTP like
 * [OrganizationIsolationTest] (whose harness this copies).
 */
class ClientPhoneValidationTest {

    private fun ApplicationTestBuilder.startApp() {
        environment {
            // Replaces application.conf entirely. Anything not listed falls
            // back to the defaults in AppSettings, which are the development
            // ones — that is what we want here.
            config = MapApplicationConfig(
                "app.environment" to "development",
                "app.uploadDir" to "build/test-uploads",
                "db.driver" to "org.h2.Driver",
                // MODE=PostgreSQL is not cosmetic: `clients.custom_fields` is an
                // Exposed `jsonb` column, which only has a mapping on H2 when the
                // dialect reports PostgreSQL compatibility. Without it the schema
                // fails to create and every test here dies at startup.
                "db.url" to "jdbc:h2:mem:client-phone-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "db.user" to "sa",
                "db.password" to ""
            )
        }
        application { module() }
    }

    /** Registers an account and returns its access token. */
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

    /**
     * Mints a one-use organization-creation token, bypassing HTTP.
     *
     * There is no admin-panel HTTP endpoint exercised in *this* file — that
     * gating is `OrgCreationTokenTest`'s concern — so tests here call the
     * service directly, the same way they'd call any other collaborator
     * that isn't the property under test. [issuerToken]'s user id is read
     * back via `/api/users/me` because the register() helper only returns
     * the access token.
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

    /** Creates an organization owned by [token]'s user and returns its id. */
    private suspend fun ApplicationTestBuilder.createOrg(token: String, slug: String): String {
        val creationToken = mintCreationToken(token)
        val response = client.post("/api/organizations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$slug","slug":"$slug","creationToken":"$creationToken"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status, "createOrg failed: ${response.bodyAsText()}")
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    /** Creates a client in [orgId] and returns its id. */
    private suspend fun ApplicationTestBuilder.createClient(token: String, orgId: String, name: String): String {
        val response = client.post("/api/clients") {
            bearerAuth(token)
            header(ORG_HEADER, orgId)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","phone":"+1 555 000 0100"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status, "createClient failed: ${response.bodyAsText()}")
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.signedInOrg(): Pair<String, String> {
        val token = register("owner@example.com")
        return token to createOrg(token, "phone-salon")
    }

    private suspend fun ApplicationTestBuilder.postClient(token: String, orgId: String, phone: String) =
        client.post("/api/clients") {
            bearerAuth(token)
            header(ORG_HEADER, orgId)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Client","phone":"$phone"}""")
        }

    private suspend fun ApplicationTestBuilder.putClient(token: String, orgId: String, id: String, body: String) =
        client.put("/api/clients/$id") {
            bearerAuth(token)
            header(ORG_HEADER, orgId)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun field(response: String, name: String) =
        Json.parseToJsonElement(response).jsonObject[name]!!

    @Test
    fun `an incomplete number is refused with a field error`() = testApplication {
        startApp()
        val (token, orgId) = signedInOrg()

        val response = postClient(token, orgId, "+1 (555) 000")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val phoneError = field(response.bodyAsText(), "fieldErrors").jsonObject["phone"]!!.jsonObject
        assertEquals("PHONE_INVALID", phoneError["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a valid number is stored in the masked format`() = testApplication {
        startApp()
        val (token, orgId) = signedInOrg()

        val response = postClient(token, orgId, "8 999 123 45 67")

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        assertEquals("+7 (999) 123-45-67", field(response.bodyAsText(), "phone").jsonPrimitive.content)
    }

    @Test
    fun `a legacy number survives edits that do not change it, but cannot be changed to another invalid one`() = testApplication {
        startApp()
        val (token, orgId) = signedInOrg()
        val id = field(postClient(token, orgId, "+1 555 000 0100").bodyAsText(), "id").jsonPrimitive.content
        // Rows written before validation existed hold free text.
        transaction { ClientsTable.update({ ClientsTable.id eq id }) { it[phone] = "ext. 12" } }

        val unchanged = putClient(token, orgId, id, """{"name":"Renamed","phone":"ext. 12"}""")
        assertEquals(HttpStatusCode.OK, unchanged.status, unchanged.bodyAsText())
        assertEquals("ext. 12", field(unchanged.bodyAsText(), "phone").jsonPrimitive.content)

        val changed = putClient(token, orgId, id, """{"phone":"ext. 13"}""")
        assertEquals(HttpStatusCode.BadRequest, changed.status)
    }
}
