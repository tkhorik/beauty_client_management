package com.beauty.routes

import com.beauty.auth.MembershipService
import com.beauty.config.AppSettings
import com.beauty.db.AttachmentsTable
import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.VisitsTable
import com.beauty.models.AttachmentDto
import com.beauty.plugins.OrgContext
import com.beauty.plugins.requireOrgAccess
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/** Restricts a visit lookup to the caller's organization. See `ClientRoutes.clientScope`. */
private fun OrgContext.visitScope(): Op<Boolean> =
    scopedTo?.let { VisitsTable.organizationId eq it } ?: Op.TRUE

/**
 * Attachment upload and deletion.
 *
 * Attachments carry no `organization_id` of their own: they are reachable only
 * through a visit, so the visit's organization is theirs. Every route here
 * therefore resolves the parent visit under the caller's scope first — a
 * missing check would be worse than on the other tables, because these rows
 * point at photographs of other people's clients.
 */
fun Route.attachmentRoutes() {
    val uploadDir = AppSettings(application.environment.config).uploadDir
    val memberships = MembershipService()

    route("/api/attachments") {
        post("/upload") {
            // Resolved before the multipart body is read, so an unauthorized
            // caller never gets as far as writing bytes to disk.
            val ctx = requireOrgAccess(memberships) ?: return@post
            val multipart = call.receiveMultipart()
            var visitId = ""
            var caption: String? = null
            var tag = "PROCEDURE"
            var fileBytes: ByteArray? = null
            var fileName = ""
            var contentType = "image/jpeg"

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        when (part.name) {
                            "visitId" -> visitId = part.value
                            "caption" -> caption = part.value
                            "tag" -> tag = part.value
                        }
                    }
                    is PartData.FileItem -> {
                        fileName = part.originalFileName ?: "photo.jpg"
                        contentType = part.contentType?.toString() ?: "image/jpeg"
                        fileBytes = part.streamProvider().readBytes()
                    }
                    else -> {}
                }
                part.dispose()
            }

            if (visitId.isEmpty() || fileBytes == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing visitId or file data"))
                return@post
            }

            // The visit must belong to the caller's organization. Checked before
            // the file is written, so a rejected upload leaves nothing behind —
            // and, more importantly, so a photograph can never be attached to
            // another tenant's visit record.
            val visitInScope = dbQuery {
                VisitsTable
                    .select { (VisitsTable.id eq visitId) and ctx.visitScope() }
                    .singleOrNull() != null
            }
            if (!visitInScope) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Visit not found"))
                return@post
            }

            val attachmentId = UUID.randomUUID().toString()
            // The client controls the original filename, so strip any directory
            // component and unsafe characters before it touches the filesystem.
            // Without this, a name like "../../app.jar" writes outside the
            // upload directory.
            val safeName = File(fileName).name
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .takeLast(100)
                .ifBlank { "upload" }
            val savedFileName = "${attachmentId}_$safeName"
            val destFile = File(uploadDir, savedFileName)
            destFile.writeBytes(fileBytes!!)

            val fileUrl = "/uploads/$savedFileName"
            val fileSize = fileBytes!!.size.toLong()
            val now = LocalDateTime.now()

            dbQuery {
                AttachmentsTable.insert {
                    it[AttachmentsTable.id] = attachmentId
                    it[AttachmentsTable.visitId] = visitId
                    it[AttachmentsTable.fileUrl] = fileUrl
                    it[AttachmentsTable.fileType] = contentType
                    it[AttachmentsTable.fileSize] = fileSize
                    it[AttachmentsTable.caption] = caption
                    it[AttachmentsTable.tag] = tag
                    it[AttachmentsTable.uploadedAt] = now
                }
            }

            val created = AttachmentDto(
                id = attachmentId,
                visitId = visitId,
                fileUrl = fileUrl,
                fileType = contentType,
                fileSize = fileSize,
                caption = caption,
                tag = tag,
                uploadedAt = now.toString()
            )
            call.respond(HttpStatusCode.Created, created)
        }

        delete("/{id}") {
            val ctx = requireOrgAccess(memberships, allowGlobal = true) ?: return@delete
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID"))

            val count = dbQuery {
                // Join to the visit to find the owning organization, since the
                // attachment row does not carry one itself. A bare
                // `deleteWhere { id eq id }` here would let anyone with a valid
                // token destroy any attachment in the system by id.
                val ownedByCaller = (AttachmentsTable innerJoin VisitsTable)
                    .select { (AttachmentsTable.id eq id) and ctx.visitScope() }
                    .singleOrNull() != null

                if (!ownedByCaller) 0 else AttachmentsTable.deleteWhere { AttachmentsTable.id eq id }
            }
            if (count > 0) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "Attachment deleted"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Attachment not found"))
            }
        }
    }
}
