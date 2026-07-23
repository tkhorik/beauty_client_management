package com.beauty.routes

import com.beauty.db.AttachmentsTable
import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.VisitsTable
import com.beauty.models.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.LocalDateTime
import java.util.UUID

fun Route.visitRoutes() {
    route("/api/visits") {
        get {
            val clientId = call.request.queryParameters["clientId"]
            val visits = dbQuery {
                val query = if (!clientId.isNullOrEmpty()) {
                    VisitsTable.select { VisitsTable.clientId eq clientId }
                } else {
                    VisitsTable.selectAll()
                }

                query.orderBy(VisitsTable.visitDateTime to SortOrder.DESC).map { row ->
                    val visitId = row[VisitsTable.id]
                    val attachmentsList = AttachmentsTable.select { AttachmentsTable.visitId eq visitId }.map { attRow ->
                        AttachmentDto(
                            id = attRow[AttachmentsTable.id],
                            visitId = attRow[AttachmentsTable.visitId],
                            fileUrl = attRow[AttachmentsTable.fileUrl],
                            fileType = attRow[AttachmentsTable.fileType],
                            fileSize = attRow[AttachmentsTable.fileSize],
                            caption = attRow[AttachmentsTable.caption],
                            tag = attRow[AttachmentsTable.tag],
                            uploadedAt = attRow[AttachmentsTable.uploadedAt].toString()
                        )
                    }

                    VisitDto(
                        id = visitId,
                        clientId = row[VisitsTable.clientId],
                        visitDateTime = row[VisitsTable.visitDateTime].toString(),
                        durationMinutes = row[VisitsTable.durationMinutes],
                        procedureNotes = row[VisitsTable.procedureNotes],
                        status = row[VisitsTable.status],
                        attachments = attachmentsList,
                        createdAt = row[VisitsTable.createdAt].toString()
                    )
                }
            }
            call.respond(visits)
        }

        get("/{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID"))
            val visit = dbQuery {
                val row = VisitsTable.select { VisitsTable.id eq id }.singleOrNull() ?: return@dbQuery null
                val attachmentsList = AttachmentsTable.select { AttachmentsTable.visitId eq id }.map { attRow ->
                    AttachmentDto(
                        id = attRow[AttachmentsTable.id],
                        visitId = attRow[AttachmentsTable.visitId],
                        fileUrl = attRow[AttachmentsTable.fileUrl],
                        fileType = attRow[AttachmentsTable.fileType],
                        fileSize = attRow[AttachmentsTable.fileSize],
                        caption = attRow[AttachmentsTable.caption],
                        tag = attRow[AttachmentsTable.tag],
                        uploadedAt = attRow[AttachmentsTable.uploadedAt].toString()
                    )
                }

                VisitDto(
                    id = row[VisitsTable.id],
                    clientId = row[VisitsTable.clientId],
                    visitDateTime = row[VisitsTable.visitDateTime].toString(),
                    durationMinutes = row[VisitsTable.durationMinutes],
                    procedureNotes = row[VisitsTable.procedureNotes],
                    status = row[VisitsTable.status],
                    attachments = attachmentsList,
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
            val req = call.receive<CreateVisitRequest>()
            val id = UUID.randomUUID().toString()
            val now = LocalDateTime.now()

            val visitTime = try {
                LocalDateTime.parse(req.visitDateTime)
            } catch (e: Exception) {
                now
            }

            dbQuery {
                VisitsTable.insert {
                    it[VisitsTable.id] = id
                    it[VisitsTable.clientId] = req.clientId
                    it[VisitsTable.visitDateTime] = visitTime
                    it[VisitsTable.durationMinutes] = req.durationMinutes
                    it[VisitsTable.procedureNotes] = req.procedureNotes
                    it[VisitsTable.status] = req.status
                    it[VisitsTable.createdAt] = now
                }
            }

            val created = VisitDto(
                id = id,
                clientId = req.clientId,
                visitDateTime = visitTime.toString(),
                durationMinutes = req.durationMinutes,
                procedureNotes = req.procedureNotes,
                status = req.status,
                attachments = emptyList(),
                createdAt = now.toString()
            )
            call.respond(HttpStatusCode.Created, created)
        }

        put("/{id}") {
            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID"))
            val req = call.receive<UpdateVisitRequest>()

            val updated = dbQuery {
                val existing = VisitsTable.select { VisitsTable.id eq id }.singleOrNull() ?: return@dbQuery null

                val newTime = if (req.visitDateTime != null) {
                    try { LocalDateTime.parse(req.visitDateTime) } catch (e: Exception) { existing[VisitsTable.visitDateTime] }
                } else existing[VisitsTable.visitDateTime]

                val newDuration = req.durationMinutes ?: existing[VisitsTable.durationMinutes]
                val newNotes = req.procedureNotes ?: existing[VisitsTable.procedureNotes]
                val newStatus = req.status ?: existing[VisitsTable.status]

                VisitsTable.update({ VisitsTable.id eq id }) {
                    it[VisitsTable.visitDateTime] = newTime
                    it[VisitsTable.durationMinutes] = newDuration
                    it[VisitsTable.procedureNotes] = newNotes
                    it[VisitsTable.status] = newStatus
                }

                val attachmentsList = AttachmentsTable.select { AttachmentsTable.visitId eq id }.map { attRow ->
                    AttachmentDto(
                        id = attRow[AttachmentsTable.id],
                        visitId = attRow[AttachmentsTable.visitId],
                        fileUrl = attRow[AttachmentsTable.fileUrl],
                        fileType = attRow[AttachmentsTable.fileType],
                        fileSize = attRow[AttachmentsTable.fileSize],
                        caption = attRow[AttachmentsTable.caption],
                        tag = attRow[AttachmentsTable.tag],
                        uploadedAt = attRow[AttachmentsTable.uploadedAt].toString()
                    )
                }

                VisitDto(
                    id = id,
                    clientId = existing[VisitsTable.clientId],
                    visitDateTime = newTime.toString(),
                    durationMinutes = newDuration,
                    procedureNotes = newNotes,
                    status = newStatus,
                    attachments = attachmentsList,
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
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID"))
            val count = dbQuery {
                AttachmentsTable.deleteWhere { AttachmentsTable.visitId eq id }
                VisitsTable.deleteWhere { VisitsTable.id eq id }
            }
            if (count > 0) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "Visit deleted successfully"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Visit not found"))
            }
        }
    }
}
