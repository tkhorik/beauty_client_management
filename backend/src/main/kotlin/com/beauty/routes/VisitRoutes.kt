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
import java.time.LocalDateTime
import java.util.UUID

/**
 * Restricts a visit query to the caller's organization.
 *
 * Reads `visits.organization_id` directly rather than joining through
 * `clients`. That column is denormalised precisely so this predicate cannot be
 * forgotten: a join is something you can omit and still get a working query,
 * whereas a missing `WHERE` here is visibly a missing `WHERE`.
 */
private fun OrgContext.visitScope(): Op<Boolean> =
    scopedTo?.let { VisitsTable.organizationId eq it } ?: Op.TRUE

/** Reads a visit's attachments. Callers must have already scoped the visit itself. */
private fun attachmentsFor(visitId: String): List<AttachmentDto> =
    attachmentsForVisits(listOf(visitId))[visitId].orEmpty()

/** Reads attachments for a page of visits in one query, avoiding an N+1 loop. */
private fun attachmentsForVisits(visitIds: List<String>): Map<String, List<AttachmentDto>> {
    if (visitIds.isEmpty()) return emptyMap()
    return AttachmentsTable.select { AttachmentsTable.visitId inList visitIds }
        .map { attRow ->
        AttachmentDto(
            id = attRow[AttachmentsTable.id],
            visitId = attRow[AttachmentsTable.visitId],
            fileUrl = attachmentDownloadUrl(attRow[AttachmentsTable.id]),
            fileType = attRow[AttachmentsTable.fileType],
            fileSize = attRow[AttachmentsTable.fileSize],
            caption = attRow[AttachmentsTable.caption],
            tag = attRow[AttachmentsTable.tag],
            uploadedAt = attRow[AttachmentsTable.uploadedAt].toString()
        )
    }.groupBy { it.visitId }
}

/**
 * Visit records, scoped to one organization.
 *
 * Same rules as `ClientRoutes`: cross-organization ids answer 404 rather than
 * 403, and the owning organization is derived server-side from the parent
 * client — never accepted from the request body.
 */
fun Route.visitRoutes() {
    val memberships = MembershipService()

    route("/api/visits") {
        get {
            val ctx = requireOrgAccess(memberships, allowGlobal = true) ?: return@get
            val clientId = call.request.queryParameters["clientId"]
            val limit = call.pageLimit()
            val offset = call.pageOffset()

            val visits = dbQuery {
                // `clientId` narrows the result but does not authorize it. The
                // organization predicate is always present, so passing another
                // tenant's client id returns an empty list rather than their
                // visit history.
                val predicate = if (!clientId.isNullOrEmpty()) {
                    (VisitsTable.clientId eq clientId) and ctx.visitScope()
                } else {
                    ctx.visitScope()
                }

                val page = VisitsTable.select { predicate }
                    .orderBy(VisitsTable.visitDateTime to SortOrder.DESC)
                    .limit(limit, offset)
                    .toList()
                val attachments = attachmentsForVisits(page.map { it[VisitsTable.id] })

                page.map { row ->
                    val visitId = row[VisitsTable.id]
                    VisitDto(
                        id = visitId,
                        clientId = row[VisitsTable.clientId],
                        visitDateTime = row[VisitsTable.visitDateTime].toString(),
                        durationMinutes = row[VisitsTable.durationMinutes],
                        procedureNotes = row[VisitsTable.procedureNotes],
                        status = row[VisitsTable.status],
                        attachments = attachments[visitId].orEmpty(),
                        createdAt = row[VisitsTable.createdAt].toString()
                    )
                    }
            }
            call.respond(visits)
        }

        get("/{id}") {
            val ctx = requireOrgAccess(memberships, allowGlobal = true) ?: return@get
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID"))

            val visit = dbQuery {
                val row = VisitsTable
                    .select { (VisitsTable.id eq id) and ctx.visitScope() }
                    .singleOrNull() ?: return@dbQuery null

                VisitDto(
                    id = row[VisitsTable.id],
                    clientId = row[VisitsTable.clientId],
                    visitDateTime = row[VisitsTable.visitDateTime].toString(),
                    durationMinutes = row[VisitsTable.durationMinutes],
                    procedureNotes = row[VisitsTable.procedureNotes],
                    status = row[VisitsTable.status],
                    attachments = attachmentsFor(id),
                    createdAt = row[VisitsTable.createdAt].toString()
                )
            }

            if (visit != null) {
                call.respond(visit)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Visit not found"))
            }
        }

        post {
            // A write lands in one organization; a super admin must name it too.
            val ctx = requireOrgAccess(memberships) ?: return@post
            val organizationId = ctx.organizationId!!

            val req = call.receive<CreateVisitRequest>()
            val id = UUID.randomUUID().toString()
            val now = LocalDateTime.now()

            val visitTime = try {
                LocalDateTime.parse(req.visitDateTime)
            } catch (e: Exception) {
                now
            }

            val created = dbQuery {
                // The parent client must be visible to this caller. Without this
                // check, posting a visit with another tenant's `clientId` would
                // attach a record to their client — and the denormalised
                // organization column would then disagree with the client's,
                // hiding the injected row from the people it actually affects.
                val parent = ClientsTable
                    .select { (ClientsTable.id eq req.clientId) and (ClientsTable.organizationId eq organizationId) }
                    .singleOrNull() ?: return@dbQuery null

                VisitsTable.insert {
                    it[VisitsTable.id] = id
                    it[VisitsTable.clientId] = req.clientId
                    // Copied from the parent client, which was just verified to
                    // belong to the caller's organization.
                    it[VisitsTable.organizationId] = parent[ClientsTable.organizationId]
                    it[createdBy] = ctx.userId
                    it[VisitsTable.visitDateTime] = visitTime
                    it[VisitsTable.durationMinutes] = req.durationMinutes
                    it[VisitsTable.procedureNotes] = req.procedureNotes
                    it[VisitsTable.status] = req.status
                    it[VisitsTable.createdAt] = now
                }

                VisitDto(
                    id = id,
                    clientId = req.clientId,
                    visitDateTime = visitTime.toString(),
                    durationMinutes = req.durationMinutes,
                    procedureNotes = req.procedureNotes,
                    status = req.status,
                    attachments = emptyList(),
                    createdAt = now.toString()
                )
            }

            if (created == null) {
                // Same wording the client would get for a genuinely unknown id.
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Client not found"))
            } else {
                call.respond(HttpStatusCode.Created, created)
            }
        }

        put("/{id}") {
            val ctx = requireOrgAccess(memberships, allowGlobal = true) ?: return@put
            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID"))
            val req = call.receive<UpdateVisitRequest>()

            val updated = dbQuery {
                val existing = VisitsTable
                    .select { (VisitsTable.id eq id) and ctx.visitScope() }
                    .singleOrNull() ?: return@dbQuery null

                val newTime = if (req.visitDateTime != null) {
                    try { LocalDateTime.parse(req.visitDateTime) } catch (e: Exception) { existing[VisitsTable.visitDateTime] }
                } else existing[VisitsTable.visitDateTime]

                val newDuration = req.durationMinutes ?: existing[VisitsTable.durationMinutes]
                val newNotes = req.procedureNotes ?: existing[VisitsTable.procedureNotes]
                val newStatus = req.status ?: existing[VisitsTable.status]

                // Scoped on the UPDATE as well, so the write can never reach a
                // row the read would not have returned.
                VisitsTable.update({ (VisitsTable.id eq id) and ctx.visitScope() }) {
                    it[VisitsTable.visitDateTime] = newTime
                    it[VisitsTable.durationMinutes] = newDuration
                    it[VisitsTable.procedureNotes] = newNotes
                    it[VisitsTable.status] = newStatus
                }

                VisitDto(
                    id = id,
                    clientId = existing[VisitsTable.clientId],
                    visitDateTime = newTime.toString(),
                    durationMinutes = newDuration,
                    procedureNotes = newNotes,
                    status = newStatus,
                    attachments = attachmentsFor(id),
                    createdAt = existing[VisitsTable.createdAt].toString()
                )
            }

            if (updated != null) {
                call.respond(updated)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Visit not found"))
            }
        }

        delete("/{id}") {
            val ctx = requireOrgAccess(memberships, allowGlobal = true) ?: return@delete
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID"))

            val deletedFiles = dbQuery {
                // Prove the visit is in scope before removing its attachments,
                // so a foreign id cannot destroy another organization's files
                // on the way to a 404.
                val visible = VisitsTable
                    .select { (VisitsTable.id eq id) and ctx.visitScope() }
                    .singleOrNull()
                if (visible == null) {
                    null
                } else {
                    val files = AttachmentsTable
                        .select { AttachmentsTable.visitId eq id }
                        .map { it[AttachmentsTable.fileUrl] }
                    if (VisitsTable.deleteWhere { VisitsTable.id eq id } > 0) files else null
                }
            }

            if (deletedFiles != null) {
                deletedFiles.forEach { storedPath ->
                    storedAttachmentFile(AppSettings(application.environment.config).uploadDir, storedPath)?.let { file ->
                        if (file.exists() && !file.delete()) application.log.error("Could not delete attachment file {}", file)
                    }
                }
                call.respond(HttpStatusCode.OK, mapOf("message" to "Visit deleted successfully"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Visit not found"))
            }
        }
    }
}
