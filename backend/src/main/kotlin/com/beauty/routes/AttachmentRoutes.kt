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
import java.io.InputStream
import java.io.OutputStream
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
            var temporaryFile: File? = null
            var fileSize = 0L
            var fileName = ""
            var contentType = "image/jpeg"
            var multipleFiles = false

            try {
                multipart.forEachPart { part ->
                    try {
                        when (part) {
                            is PartData.FormItem -> {
                                when (part.name) {
                                    "visitId" -> visitId = part.value
                                    "caption" -> caption = part.value
                                    "tag" -> tag = part.value
                                }
                            }
                            is PartData.FileItem -> {
                                if (temporaryFile != null) {
                                    multipleFiles = true
                                } else {
                                    fileName = part.originalFileName ?: "photo.jpg"
                                    contentType = part.contentType?.toString() ?: "image/jpeg"
                                    temporaryFile = File.createTempFile("upload-", ".tmp", uploadDir)
                                    fileSize = part.streamProvider().use { input ->
                                        temporaryFile!!.outputStream().use { output ->
                                            copyWithLimit(input, output, MAX_UPLOAD_BYTES)
                                        }
                                    }
                                }
                            }
                            else -> Unit
                        }
                    } finally {
                        part.dispose()
                    }
                }

                if (multipleFiles) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Only one file may be uploaded"))
                    return@post
                }
                if (visitId.isEmpty() || temporaryFile == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing visitId or file data"))
                    return@post
                }

                // The visit must belong to the caller's organization. The file
                // has only reached an unreferenced temporary path at this point.
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
                val safeName = File(fileName).name
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .takeLast(100)
                    .ifBlank { "upload" }
                val savedFileName = "${attachmentId}_$safeName"
                val destFile = File(uploadDir, savedFileName)
                if (!temporaryFile!!.renameTo(destFile)) {
                    throw IllegalStateException("Could not finalize attachment upload")
                }
                temporaryFile = null

                // This remains an internal storage key. DTOs expose the
                // authenticated endpoint instead, never this direct path.
                val storedFileUrl = "/uploads/$savedFileName"
                val now = LocalDateTime.now()

                try {
                    dbQuery {
                        AttachmentsTable.insert {
                            it[AttachmentsTable.id] = attachmentId
                            it[AttachmentsTable.visitId] = visitId
                            it[AttachmentsTable.fileUrl] = storedFileUrl
                            it[AttachmentsTable.fileType] = contentType
                            it[AttachmentsTable.fileSize] = fileSize
                            it[AttachmentsTable.caption] = caption
                            it[AttachmentsTable.tag] = tag
                            it[AttachmentsTable.uploadedAt] = now
                        }
                    }
                } catch (e: Exception) {
                    destFile.delete()
                    throw e
                }

                val created = AttachmentDto(
                    id = attachmentId,
                    visitId = visitId,
                    fileUrl = attachmentDownloadUrl(attachmentId),
                    fileType = contentType,
                    fileSize = fileSize,
                    caption = caption,
                    tag = tag,
                    uploadedAt = now.toString()
                )
                call.respond(HttpStatusCode.Created, created)
            } catch (e: UploadTooLargeException) {
                call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "Attachment exceeds the 20 MB upload limit"))
            } finally {
                temporaryFile?.delete()
            }
        }

        get("/{id}/file") {
            val ctx = requireOrgAccess(memberships, allowGlobal = true) ?: return@get
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID"))

            val attachment = dbQuery {
                (AttachmentsTable innerJoin VisitsTable)
                    .select { (AttachmentsTable.id eq id) and ctx.visitScope() }
                    .singleOrNull()
            }
            if (attachment == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Attachment not found"))
                return@get
            }

            val file = storedAttachmentFile(uploadDir, attachment[AttachmentsTable.fileUrl])
            if (file == null || !file.isFile) {
                application.log.warn("Attachment {} exists in the database but its file is unavailable", id)
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Attachment file not found"))
                return@get
            }
            call.respondFile(file)
        }

        delete("/{id}") {
            val ctx = requireOrgAccess(memberships, allowGlobal = true) ?: return@delete
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID"))

            val deletedFile = dbQuery {
                // Join to the visit to find the owning organization, since the
                // attachment row does not carry one itself. A bare
                // `deleteWhere { id eq id }` here would let anyone with a valid
                // token destroy any attachment in the system by id.
                val ownedByCaller = (AttachmentsTable innerJoin VisitsTable)
                    .select { (AttachmentsTable.id eq id) and ctx.visitScope() }
                    .singleOrNull() != null

                if (!ownedByCaller) null else {
                    val storedPath = AttachmentsTable
                        .select { AttachmentsTable.id eq id }
                        .single()[AttachmentsTable.fileUrl]
                    if (AttachmentsTable.deleteWhere { AttachmentsTable.id eq id } > 0) storedPath else null
                }
            }
            if (deletedFile != null) {
                storedAttachmentFile(uploadDir, deletedFile)?.let { file ->
                    if (file.exists() && !file.delete()) application.log.error("Could not delete attachment file {}", file)
                }
                call.respond(HttpStatusCode.OK, mapOf("message" to "Attachment deleted"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Attachment not found"))
            }
        }
    }
}

private const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024

private class UploadTooLargeException : RuntimeException()

/** Streams to disk in bounded buffers; no uploaded file is retained on the JVM heap. */
private fun copyWithLimit(input: InputStream, output: OutputStream, limit: Long): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return total
        total += read
        if (total > limit) throw UploadTooLargeException()
        output.write(buffer, 0, read)
    }
}
