package com.beauty.routes

import com.beauty.auth.RefreshTokenService
import com.beauty.auth.VerificationPolicy
import com.beauty.config.AppSettings
import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.UsersTable
import com.beauty.i18n.Languages
import com.beauty.models.UpdateLanguageRequest
import com.beauty.models.LanguageResponse
import org.jetbrains.exposed.sql.and
import com.beauty.models.ChangePasswordRequest
import com.beauty.models.UpdateProfileRequest
import com.beauty.models.ValidationErrorResponse
import com.beauty.plugins.userId
import com.beauty.validation.Validation
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.mindrot.jbcrypt.BCrypt

/**
 * Self-service profile management for the signed-in user: viewing/editing the
 * display name and rotating a password.
 *
 * Mounted inside `authenticate("auth-jwt")` in `Routing.kt` — every route here
 * acts on "whoever this access token belongs to" (`call.userId()`), never on
 * an id supplied by the client, so one user can never edit another's profile.
 *
 * **Deliberately exempt from the email-verification write gate**, even though
 * two of these routes are writes. They resolve the caller with `call.userId()`
 * rather than `requireOrgAccess`, so the method-derived gate never sees them —
 * which is the intended outcome, not an oversight. Changing a password is the
 * action a user takes when they think their account is compromised, and a
 * person whose confirmation mail bounced would have no way to perform it. The
 * same reasoning covers the display name: it touches no organization's data.
 */
fun Route.userRoutes() {
    val settings = AppSettings(application.environment.config)
    val secret = settings.jwtSecret
    val issuer = settings.jwtIssuer
    val audience = settings.jwtAudience
    val accessTokenMinutes = settings.accessTokenMinutes
    val refreshTokens = RefreshTokenService(settings.refreshTokenDays)

    val verification = VerificationPolicy(settings)

    // Delegates to the shared mapper in AuthRoutes.kt rather than repeating the
    // field list, so `emailVerified` cannot silently default to false here.
    fun userRowToDto(row: org.jetbrains.exposed.sql.ResultRow) = userDto(row, verification)

    route("/api/users/me") {
        put("/language") {
            val userId = call.userId()
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token", "code" to "INVALID_TOKEN"))
                return@put
            }
            val req = try {
                call.receive<UpdateLanguageRequest>()
            } catch (_: io.ktor.server.plugins.BadRequestException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid language preference or revision", "code" to "INVALID_LANGUAGE"))
                return@put
            }
            if (req.preference !in Languages.preferences || req.expectedRevision < 0) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid language preference or revision", "code" to "INVALID_LANGUAGE"))
                return@put
            }
            // Row locking serializes different selections as well as idempotent retries.
            val result = dbQuery {
                val row = UsersTable.select { UsersTable.id eq userId }.forUpdate().singleOrNull()
                    ?: return@dbQuery null
                val current = LanguageResponse(row[UsersTable.languagePreference], row[UsersTable.languageRevision])
                if (current.preference == req.preference) return@dbQuery current to true
                if (current.revision != req.expectedRevision) return@dbQuery current to false
                UsersTable.update({ (UsersTable.id eq userId) and (UsersTable.languageRevision eq req.expectedRevision) }) {
                    it[languagePreference] = req.preference
                    it[languageRevision] = current.revision + 1
                }
                LanguageResponse(req.preference, current.revision + 1) to true
            }
            if (result == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found", "code" to "USER_NOT_FOUND"))
            } else {
                call.respond(if (result.second) HttpStatusCode.OK else HttpStatusCode.Conflict, result.first)
            }
        }

        /** Lets the client (re)hydrate the profile it doesn't otherwise have — the JWT carries only id and email, not the display name. */
        get {
            val userId = call.userId()
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token", "code" to "INVALID_TOKEN"))
                return@get
            }

            val row = dbQuery { UsersTable.select { UsersTable.id eq userId }.singleOrNull() }
            if (row == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found", "code" to "USER_NOT_FOUND"))
                return@get
            }

            call.respond(HttpStatusCode.OK, userRowToDto(row))
        }

        patch {
            val userId = call.userId()
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token", "code" to "INVALID_TOKEN"))
                return@patch
            }

            val req = call.receive<UpdateProfileRequest>()
            val fullName = req.fullName.trim()

            Validation.fullNameIssue(fullName)?.let { message ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ValidationErrorResponse.from(mapOf("fullName" to message))
                )
                return@patch
            }

            val row = dbQuery {
                val updated = UsersTable.update({ UsersTable.id eq userId }) {
                    it[UsersTable.fullName] = fullName
                }
                if (updated == 0) null else UsersTable.select { UsersTable.id eq userId }.singleOrNull()
            }
            if (row == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found", "code" to "USER_NOT_FOUND"))
                return@patch
            }

            call.respond(HttpStatusCode.OK, userRowToDto(row))
        }

        /**
         * Changes the password and, on success, ends every other session.
         *
         * A password change is exactly the moment a stolen-but-not-yet-expired
         * session should stop working: either this is the legitimate owner
         * locking an attacker out, or it's routine hygiene, and in neither case
         * should some other already-open tab or device keep working on the old
         * credential. The device making this request gets a brand-new session
         * so it is not logged out by its own action.
         */
        post("/password") {
            val userId = call.userId()
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token", "code" to "INVALID_TOKEN"))
                return@post
            }

            val req = call.receive<ChangePasswordRequest>()

            Validation.passwordIssue(req.newPassword)?.let { message ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ValidationErrorResponse.from(mapOf("newPassword" to message))
                )
                return@post
            }

            val row = dbQuery { UsersTable.select { UsersTable.id eq userId }.singleOrNull() }
            if (row == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found", "code" to "USER_NOT_FOUND"))
                return@post
            }

            if (!BCrypt.checkpw(req.currentPassword, row[UsersTable.passwordHash])) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Current password is incorrect", "code" to "CURRENT_PASSWORD_INCORRECT"))
                return@post
            }

            // Reusing the same password doesn't weaken anything, but there's no
            // reason to churn every other session over a no-op change.
            if (BCrypt.checkpw(req.newPassword, row[UsersTable.passwordHash])) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ValidationErrorResponse.from(mapOf("newPassword" to com.beauty.validation.ValidationIssue("PASSWORD_UNCHANGED", "New password must be different from the current password.")))
                )
                return@post
            }

            val newHash = BCrypt.hashpw(req.newPassword, BCrypt.gensalt())
            dbQuery {
                UsersTable.update({ UsersTable.id eq userId }) {
                    it[passwordHash] = newHash
                }
            }

            refreshTokens.revokeAllForUser(userId)

            call.respondWithNewSession(
                status = HttpStatusCode.OK,
                user = userRowToDto(row),
                settings = settings,
                secret = secret,
                issuer = issuer,
                audience = audience,
                accessTokenMinutes = accessTokenMinutes,
                refreshTokens = refreshTokens
            )
        }
    }
}
