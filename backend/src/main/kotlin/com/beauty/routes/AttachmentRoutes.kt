package com.beauty.routes

import com.beauty.config.AppSettings
import com.beauty.db.AttachmentsTable
import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.models.AttachmentDto
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

fun Route.attachmentRoutes() {
    val uploadDir = AppSettings(application.environment.config).uploadDir

    route("/api/attachments") {
        post("/upload") {
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
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID"))
            val count = dbQuery {
                AttachmentsTable.deleteWhere { AttachmentsTable.id eq id }
            }
            if (count > 0) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "Attachment deleted"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Attachment not found"))
            }
        }
    }
}
