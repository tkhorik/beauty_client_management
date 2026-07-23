package com.beauty.routes

import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.UsersTable
import com.beauty.models.*
import com.beauty.plugins.generateJwtToken
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.mindrot.jbcrypt.BCrypt
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import java.time.LocalDateTime
import java.util.UUID

fun Route.authRoutes() {
    val secret = application.environment.config.propertyOrNull("jwt.secret")?.getString() ?: "beauty_secret_jwt_key"
    val issuer = application.environment.config.propertyOrNull("jwt.issuer")?.getString() ?: "http://0.0.0.0:8080/"
    val audience = application.environment.config.propertyOrNull("jwt.audience")?.getString() ?: "http://0.0.0.0:8080/users"

    route("/api/auth") {
        post("/register") {
            val req = call.receive<RegisterRequest>()
            val existing = dbQuery {
                UsersTable.select { UsersTable.email eq req.email }.singleOrNull()
            }
            if (existing != null) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "User with email already exists"))
                return@post
            }

            val id = UUID.randomUUID().toString()
            val hashedPassword = BCrypt.hashpw(req.password, BCrypt.gensalt())
            val now = LocalDateTime.now().toString()

            dbQuery {
                UsersTable.insert {
                    it[UsersTable.id] = id
                    it[UsersTable.email] = req.email
                    it[UsersTable.passwordHash] = hashedPassword
                    it[UsersTable.fullName] = req.fullName
                    it[UsersTable.createdAt] = LocalDateTime.now()
                }
            }

            val token = generateJwtToken(id, req.email, secret, issuer, audience)
            val userDto = UserDto(id, req.email, req.fullName, now)
            call.respond(HttpStatusCode.Created, AuthResponse(token, userDto))
        }

        post("/login") {
            val req = call.receive<AuthRequest>()
            val row = dbQuery {
                UsersTable.select { UsersTable.email eq req.email }.singleOrNull()
            }

            if (row == null || !BCrypt.checkpw(req.password, row[UsersTable.passwordHash])) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
                return@post
            }

            val id = row[UsersTable.id]
            val email = row[UsersTable.email]
            val fullName = row[UsersTable.fullName]
            val createdAt = row[UsersTable.createdAt].toString()

            val token = generateJwtToken(id, email, secret, issuer, audience)
            val userDto = UserDto(id, email, fullName, createdAt)
            call.respond(HttpStatusCode.OK, AuthResponse(token, userDto))
        }
    }
}
