package com.beauty.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.json.jsonb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json

object UsersTable : Table("users") {
    val id = varchar("id", 64)
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val fullName = varchar("full_name", 255)
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

object ClientsTable : Table("clients") {
    val id = varchar("id", 64)
    val name = varchar("name", 255)
    val phone = varchar("phone", 50)
    val email = varchar("email", 255).nullable()
    val tags = text("tags") // Comma-separated or JSON list
    val customFields = jsonb<JsonObject>("custom_fields", Json.Default)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object VisitsTable : Table("visits") {
    val id = varchar("id", 64)
    val clientId = varchar("client_id", 64).references(ClientsTable.id)
    val visitDateTime = datetime("visit_date_time")
    val durationMinutes = integer("duration_minutes")
    val procedureNotes = text("procedure_notes")
    val status = varchar("status", 50) // COMPLETED, SCHEDULED, CANCELLED
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

object AttachmentsTable : Table("attachments") {
    val id = varchar("id", 64)
    val visitId = varchar("visit_id", 64).references(VisitsTable.id)
    val fileUrl = varchar("file_url", 512)
    val fileType = varchar("file_type", 100)
    val fileSize = long("file_size")
    val caption = text("caption").nullable()
    val tag = varchar("tag", 50) // BEFORE, AFTER, PROCEDURE, DOCUMENT
    val uploadedAt = datetime("uploaded_at")

    override val primaryKey = PrimaryKey(id)
}
