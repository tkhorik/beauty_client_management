package com.beauty.routes

import com.beauty.db.ClientsTable
import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.VisitsTable
import com.beauty.models.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.LocalDateTime
import java.util.UUID

fun Route.clientRoutes() {
    route("/api/clients") {
        get {
            val q = call.request.queryParameters["q"]?.lowercase()?.trim()
            val tag = call.request.queryParameters["tag"]?.lowercase()?.trim()

            val clients = dbQuery {
                val query = ClientsTable.selectAll()
                query.map { row ->
                    val clientId = row[ClientsTable.id]
                    val totalVisits = VisitsTable.select { VisitsTable.clientId eq clientId }.count().toInt()
                    val tagsList = row[ClientsTable.tags].split(",").map { it.trim() }.filter { it.isNotEmpty() }

                    ClientDto(
                        id = clientId,
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
            }.filter { client ->
                var matches = true
                if (!q.isNullOrEmpty()) {
                    val inName = client.name.lowercase().contains(q)
                    val inPhone = client.phone.lowercase().contains(q)
                    val inEmail = client.email?.lowercase()?.contains(q) == true
                    val inTags = client.tags.any { it.lowercase().contains(q) }
                    val inCustomFields = client.customFields.toString().lowercase().contains(q)
                    matches = inName || inPhone || inEmail || inTags || inCustomFields
                }
                if (matches && !tag.isNullOrEmpty()) {
                    matches = client.tags.any { it.lowercase() == tag }
                }
                matches
            }

            call.respond(clients)
        }

        get("/{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID"))
            val client = dbQuery {
                val row = ClientsTable.select { ClientsTable.id eq id }.singleOrNull() ?: return@dbQuery null
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
            val req = call.receive<CreateClientRequest>()
            val id = UUID.randomUUID().toString()
            val now = LocalDateTime.now()

            val tagsStr = req.tags.joinToString(",")

            dbQuery {
                ClientsTable.insert {
                    it[ClientsTable.id] = id
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
            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID"))
            val req = call.receive<UpdateClientRequest>()
            val now = LocalDateTime.now()

            val updated = dbQuery {
                val existing = ClientsTable.select { ClientsTable.id eq id }.singleOrNull() ?: return@dbQuery null

                val newName = req.name ?: existing[ClientsTable.name]
                val newPhone = req.phone ?: existing[ClientsTable.phone]
                val newEmail = req.email ?: existing[ClientsTable.email]
                val newTagsStr = if (req.tags != null) req.tags.joinToString(",") else existing[ClientsTable.tags]
                val newCustomFields = req.customFields ?: existing[ClientsTable.customFields]

                ClientsTable.update({ ClientsTable.id eq id }) {
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
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing ID"))
            val count = dbQuery {
                VisitsTable.deleteWhere { VisitsTable.clientId eq id }
                ClientsTable.deleteWhere { ClientsTable.id eq id }
            }
            if (count > 0) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "Client deleted successfully"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Client not found"))
            }
        }
    }
}
