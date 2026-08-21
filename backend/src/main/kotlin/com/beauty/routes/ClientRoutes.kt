package com.beauty.routes

import com.beauty.auth.MembershipService
import com.beauty.config.AppSettings
import com.beauty.db.AttachmentsTable
import com.beauty.db.ClientsTable
import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.VisitsTable
import com.beauty.models.*
import com.beauty.plugins.OrgContext
import com.beauty.plugins.requireOrgAccess
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
 * Returns `Op.TRUE` only for a super admin operating with no organization
 * selected — the one case where unrestricted really is intended. Written as a
 * helper so that every query in this file scopes itself the same way, and so a
 * missing filter shows up as a missing call rather than as a subtly different
 * `select {}` body.
 */
private fun OrgContext.clientScope(): Op<Boolean> =
    scopedTo?.let { ClientsTable.organizationId eq it } ?: Op.TRUE

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
fun Route.clientRoutes() {
    val memberships = MembershipService()

    route("/api/clients") {
        get {
            // allowGlobal: a super admin auditing the whole system can omit the
            // header and get everything. No ordinary user can reach that branch.
            val ctx = requireOrgAccess(memberships, allowGlobal = true) ?: return@get

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
                    predicate = predicate and (ClientsTable.tags.lowerCase() like "%${tag.escapeLike()}%")
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

        get("/{id}") {
            val ctx = requireOrgAccess(memberships, allowGlobal = true) ?: return@get
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID"))

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
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Client not found"))
            }
        }

        post {
            // No allowGlobal: a write has to land in exactly one organization,
            // and "all of them" is not an answer. Even a super admin must say
            // which one.
            val ctx = requireOrgAccess(memberships) ?: return@post
            val organizationId = ctx.organizationId!!

            val req = call.receive<CreateClientRequest>()
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
                    it[ClientsTable.phone] = req.phone
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
                phone = req.phone,
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
            val ctx = requireOrgAccess(memberships, allowGlobal = true) ?: return@put
            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID"))
            val req = call.receive<UpdateClientRequest>()
            val now = LocalDateTime.now()

            val updated = dbQuery {
                val existing = ClientsTable
                    .select { (ClientsTable.id eq id) and ctx.clientScope() }
                    .singleOrNull() ?: return@dbQuery null

                val newName = req.name ?: existing[ClientsTable.name]
                val newPhone = req.phone ?: existing[ClientsTable.phone]
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

                ClientDto(
                    id = id,
                    name = newName,
                    phone = newPhone,
                    email = newEmail,
                    tags = tagsList,
                    customFields = newCustomFields,
                    totalVisits = totalVisits,
                    createdAt = existing[ClientsTable.createdAt].toString(),
                    updatedAt = now.toString()
                )
            }

            if (updated != null) {
                call.respond(updated)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Client not found"))
            }
        }

        delete("/{id}") {
            val ctx = requireOrgAccess(memberships, allowGlobal = true) ?: return@delete
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID"))

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
                val uploadDir = AppSettings(application.environment.config).uploadDir
                deletedFiles.forEach { storedPath ->
                    storedAttachmentFile(uploadDir, storedPath)?.let { file ->
                        if (file.exists() && !file.delete()) application.log.error("Could not delete attachment file {}", file)
                    }
                }
                call.respond(HttpStatusCode.OK, mapOf("message" to "Client deleted successfully"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Client not found"))
            }
        }
    }
}

/** Escapes SQL LIKE metacharacters before putting user input in a pattern. */
private fun String.escapeLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
