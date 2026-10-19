package com.beauty.routes

import com.beauty.auth.MembershipService
import com.beauty.db.AttachmentsTable
import com.beauty.db.ClientsTable
import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.VisitsTable
import com.beauty.models.*
import com.beauty.plugins.OrgContext
import com.beauty.plugins.requireOrgAccess
import com.beauty.storage.FileStorageService
import com.beauty.validation.Validation
import com.beauty.validation.ValidationIssue
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import java.time.LocalDateTime
import java.util.UUID

/**
 * Restricts a query to the caller's organization.
 *
 * Every caller, including a super admin, selects one active organization.
 */
private fun OrgContext.clientScope(): Op<Boolean> =
    ClientsTable.organizationId eq organizationId!!

/**
 * Client records, scoped to one organization.
 *
 * Every route here resolves an [OrgContext] first and filters on it. Two
 * consequences worth stating, because they are easy to undo by accident:
 *
 *  - A client belonging to another organization answers **404, not 403**. A 403
 *    confirms the id exists, which turns `GET /api/clients/{uuid}` into a probe
 *    for other tenants' record ids. As far as this caller is concerned, that
 *    record does not exist.
 *  - `organization_id` is never read from the request body. It comes from the
 *    verified membership check, so a client cannot write into an organization
 *    it does not belong to by naming it in the payload.
 */
fun Route.clientRoutes(storage: FileStorageService) {
    val memberships = MembershipService()

    route("/api/clients") {
        get {
            val ctx = requireOrgAccess(memberships) ?: return@get

            val q = call.request.queryParameters["q"]?.lowercase()?.trim()
            val tag = call.request.queryParameters["tag"]?.lowercase()?.trim()
            val limit = call.pageLimit()
            val offset = call.pageOffset()

            val clients = dbQuery {
                var predicate: Op<Boolean> = ctx.clientScope()
                if (!q.isNullOrEmpty()) {
                    val pattern = "%${q.escapeLike()}%"
                    predicate = predicate and (
                        (ClientsTable.name.lowerCase() like pattern) or
                            (ClientsTable.phone.lowerCase() like pattern) or
                            (ClientsTable.email.lowerCase() like pattern) or
                            (ClientsTable.tags.lowerCase() like pattern)
                    )
                }
                if (!tag.isNullOrEmpty()) {
                    predicate = predicate and (tagListExpression() like "%,${tag.withoutSpaces().escapeLike()},%")
                }

                val visitCount = VisitsTable.id.count()
                (ClientsTable leftJoin VisitsTable)
                    .slice(ClientsTable.columns + visitCount)
                    .select { predicate }
                    .groupBy(*ClientsTable.columns.toTypedArray())
                    .orderBy(ClientsTable.updatedAt to SortOrder.DESC)
                    .limit(limit, offset)
                    .map { row ->
                    val clientId = row[ClientsTable.id]
                    val tagsList = row[ClientsTable.tags].split(",").map { it.trim() }.filter { it.isNotEmpty() }

                    ClientDto(
                        id = clientId,
                        name = row[ClientsTable.name],
                        phone = row[ClientsTable.phone],
                        email = row[ClientsTable.email],
                        tags = tagsList,
                        customFields = row[ClientsTable.customFields],
                        totalVisits = row[visitCount].toInt(),
                        createdAt = row[ClientsTable.createdAt].toString(),
                        updatedAt = row[ClientsTable.updatedAt].toString()
                    )
                }
            }

            call.respond(clients)
        }

        // Tags actually in use, for the directory's filter chips. Spellings
        // differing only in case are one tag (the list filter ignores case
        // too); the most recently edited client's spelling is shown.
        get("/tags") {
            val ctx = requireOrgAccess(memberships) ?: return@get

            val tags = dbQuery {
                ClientsTable
                    .slice(ClientsTable.tags)
                    .select { ctx.clientScope() }
                    .orderBy(ClientsTable.updatedAt to SortOrder.DESC)
                    .flatMap { row ->
                        row[ClientsTable.tags].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            .distinctBy { it.lowercase() }
                    }
            }

            val counted = tags.groupBy { it.lowercase() }
                .map { (_, spellings) -> ClientTagDto(name = spellings.first(), count = spellings.size) }
                .sortedWith(compareByDescending<ClientTagDto> { it.count }.thenBy { it.name.lowercase() })

            call.respond(counted)
        }

        get("/{id}") {
            val ctx = requireOrgAccess(memberships) ?: return@get
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID", "code" to "MISSING_ID"))

            val client = dbQuery {
                // The organization predicate is part of the lookup, not a check
                // applied to the result afterwards. A separate check is one
                // early `return` away from being skipped; this way a foreign id
                // simply matches no row.
                val row = ClientsTable
                    .select { (ClientsTable.id eq id) and ctx.clientScope() }
                    .singleOrNull() ?: return@dbQuery null
                val totalVisits = VisitsTable.select { VisitsTable.clientId eq id }.count().toInt()
                val tagsList = row[ClientsTable.tags].split(",").map { it.trim() }.filter { it.isNotEmpty() }

                ClientDto(
                    id = row[ClientsTable.id],
                    name = row[ClientsTable.name],
                    phone = row[ClientsTable.phone],
                    email = row[ClientsTable.email],
                    tags = tagsList,
                    customFields = row[ClientsTable.customFields],
                    totalVisits = totalVisits,
                    createdAt = row[ClientsTable.createdAt].toString(),
                    updatedAt = row[ClientsTable.updatedAt].toString()
                )
            }

            if (client != null) {
                call.respond(client)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Client not found", "code" to "CLIENT_NOT_FOUND"))
            }
        }

        post {
            // A write has to land in exactly one active organization. Even a
            // super admin must select it explicitly.
            val ctx = requireOrgAccess(memberships) ?: return@post
            val organizationId = ctx.organizationId!!

            val req = call.receive<CreateClientRequest>()
            Validation.phoneIssue(req.phone)?.let {
                call.respond(HttpStatusCode.BadRequest, ValidationErrorResponse.from(mapOf("phone" to it)))
                return@post
            }
            val phone = Validation.formatPhone(req.phone)
            val id = UUID.randomUUID().toString()
            val now = LocalDateTime.now()

            val tagsStr = req.tags.joinToString(",")

            dbQuery {
                ClientsTable.insert {
                    it[ClientsTable.id] = id
                    // From the verified membership, never from the request body.
                    it[ClientsTable.organizationId] = organizationId
                    it[createdBy] = ctx.userId
                    it[ClientsTable.name] = req.name
                    it[ClientsTable.phone] = phone
                    it[ClientsTable.email] = req.email
                    it[ClientsTable.tags] = tagsStr
                    it[ClientsTable.customFields] = req.customFields
                    it[ClientsTable.createdAt] = now
                    it[ClientsTable.updatedAt] = now
                }
            }

            val created = ClientDto(
                id = id,
                name = req.name,
                phone = phone,
                email = req.email,
                tags = req.tags,
                customFields = req.customFields,
                totalVisits = 0,
                createdAt = now.toString(),
                updatedAt = now.toString()
            )
            call.respond(HttpStatusCode.Created, created)
        }

        put("/{id}") {
            val ctx = requireOrgAccess(memberships) ?: return@put
            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID", "code" to "MISSING_ID"))
            val req = call.receive<UpdateClientRequest>()
            val now = LocalDateTime.now()

            val updated = dbQuery {
                val existing = ClientsTable
                    .select { (ClientsTable.id eq id) and ctx.clientScope() }
                    .singleOrNull() ?: return@dbQuery UpdateOutcome.NotFound

                // Checked only when the number changes. Clients created before
                // phone validation keep whatever was typed then, and every
                // client re-sends the current number on unrelated edits (Android's
                // attribute editor does), so validating an unchanged value would
                // make those clients impossible to edit at all.
                val currentPhone = existing[ClientsTable.phone]
                val changedPhone = req.phone?.takeIf { it != currentPhone }
                changedPhone?.let(Validation::phoneIssue)?.let { return@dbQuery UpdateOutcome.Invalid(it) }

                val newName = req.name ?: existing[ClientsTable.name]
                val newPhone = changedPhone?.let(Validation::formatPhone) ?: currentPhone
                val newEmail = req.email ?: existing[ClientsTable.email]
                val newTagsStr = if (req.tags != null) req.tags.joinToString(",") else existing[ClientsTable.tags]
                val newCustomFields = req.customFields ?: existing[ClientsTable.customFields]

                // Scoped again on the UPDATE itself. The SELECT above already
                // proved access, but repeating the predicate means the write can
                // never touch a row the read would not have returned.
                ClientsTable.update({ (ClientsTable.id eq id) and ctx.clientScope() }) {
                    it[ClientsTable.name] = newName
                    it[ClientsTable.phone] = newPhone
                    it[ClientsTable.email] = newEmail
                    it[ClientsTable.tags] = newTagsStr
                    it[ClientsTable.customFields] = newCustomFields
                    it[ClientsTable.updatedAt] = now
                }

                val totalVisits = VisitsTable.select { VisitsTable.clientId eq id }.count().toInt()
                val tagsList = newTagsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                UpdateOutcome.Updated(ClientDto(
                    id = id,
                    name = newName,
                    phone = newPhone,
                    email = newEmail,
                    tags = tagsList,
                    customFields = newCustomFields,
                    totalVisits = totalVisits,
                    createdAt = existing[ClientsTable.createdAt].toString(),
                    updatedAt = now.toString()
                ))
            }

            when (updated) {
                is UpdateOutcome.Updated -> call.respond(updated.client)
                is UpdateOutcome.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, ValidationErrorResponse.from(mapOf("phone" to updated.issue)))
                UpdateOutcome.NotFound ->
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Client not found", "code" to "CLIENT_NOT_FOUND"))
            }
        }

        delete("/{id}") {
            val ctx = requireOrgAccess(memberships) ?: return@delete
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID", "code" to "MISSING_ID"))

            val deletedFiles = dbQuery {
                // Confirm the client is in scope *before* deleting its visits.
                // Deleting the children first and only then discovering the
                // parent belongs to another organization would already have
                // destroyed that organization's data.
                val visible = ClientsTable
                    .select { (ClientsTable.id eq id) and ctx.clientScope() }
                    .singleOrNull()
                if (visible == null) {
                    null
                } else {
                    val files = (AttachmentsTable innerJoin VisitsTable)
                        .select { VisitsTable.clientId eq id }
                        .map { it[AttachmentsTable.fileUrl] }
                    if (ClientsTable.deleteWhere { ClientsTable.id eq id } > 0) files else null
                }
            }

            if (deletedFiles != null) {
                storage.deleteMultiple(deletedFiles)
                call.respond(HttpStatusCode.OK, mapOf("message" to "Client deleted successfully"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Client not found", "code" to "CLIENT_NOT_FOUND"))
            }
        }
    }
}

/** Escapes SQL LIKE metacharacters before putting user input in a pattern. */
/**
 * `tags` as `,tag1,tag2,`, lowercased and with whitespace removed, so a tag
 * filter can match one whole tag: "VIP" must not select a client tagged
 * "Non-VIP". Whitespace goes because older rows were stored untrimmed
 * ("VIP, Diabetic"); the filter value gets the same treatment.
 */
private fun tagListExpression(): Expression<String> {
    val compact = CustomStringFunction("REPLACE", ClientsTable.tags.lowerCase(), stringLiteral(" "), stringLiteral(""))
    return Concat("", stringLiteral(","), compact, stringLiteral(","))
}

private fun String.withoutSpaces(): String = replace(" ", "")

private fun String.escapeLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private sealed interface UpdateOutcome {
    data class Updated(val client: ClientDto) : UpdateOutcome
    data class Invalid(val issue: ValidationIssue) : UpdateOutcome
    data object NotFound : UpdateOutcome
}
