package com.beauty.routes

import com.beauty.auth.MembershipService
import com.beauty.auth.OrgCreationTokenService
import com.beauty.auth.RefreshTokenService
import com.beauty.config.AppSettings
import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.OrganizationsTable
import com.beauty.db.UsersTable
import com.beauty.models.*
import com.beauty.plugins.requireSuperAdmin
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import java.time.LocalDateTime

/**
 * Global, cross-organization views and actions for a `SUPER_ADMIN`.
 *
 * Everything here is mounted inside `authenticate("auth-jwt")` and gated by
 * [requireSuperAdmin] — there is no organization concept in this file, only
 * the whole system. Deliberately narrow in what it can do to a user's *role*:
 * it can suspend an account and manage organization-creation links, and it
 * can change a member's role *within* an organization via the existing
 * `/api/organizations/{orgId}/members/{userId}` endpoints (a super admin
 * already bypasses that route's membership check — see `OrgAccess.kt`'s
 * `isSuperAdmin` branch — so no new endpoint was needed for that). What it
 * cannot do is grant `SUPER_ADMIN` itself: that stays `SUPER_ADMIN_EMAILS` or
 * a manual `UPDATE`, on purpose — an endpoint able to mint unrestricted
 * cross-organization access is an endpoint worth attacking, and adding one
 * here would undo that standing decision (see `docker-compose.yml`'s
 * `SUPER_ADMIN_EMAILS` comment and `CLAUDE.md`).
 */
fun Route.adminRoutes() {
    val memberships = MembershipService()
    val creationTokens = OrgCreationTokenService()
    val settings = AppSettings(application.environment.config)
    val refreshTokens = RefreshTokenService(settings.refreshTokenDays)

    route("/api/admin") {

        route("/users") {

            /** Every account in the system, for the admin panel's user table. */
            get {
                requireSuperAdmin(memberships) ?: return@get

                val users = dbQuery {
                    UsersTable.select { Op.TRUE }
                        .orderBy(UsersTable.createdAt to SortOrder.DESC)
                        .toList()
                }

                call.respond(
                    users.map { row ->
                        AdminUserDto(
                            id = row[UsersTable.id],
                            email = row[UsersTable.email],
                            fullName = row[UsersTable.fullName],
                            globalRole = row[UsersTable.globalRole],
                            emailVerified = row[UsersTable.emailVerifiedAt] != null,
                            suspendedAt = row[UsersTable.suspendedAt]?.toString(),
                            organizationCount = memberships.organizationCountForUser(row[UsersTable.id]).toInt(),
                            createdAt = row[UsersTable.createdAt].toString()
                        )
                    }
                )
            }

            /**
             * Suspends or unsuspends an account.
             *
             * Suspending also revokes every refresh-token family for the
             * target: without that, an access token already in hand keeps
             * working for the rest of its (short) life, but the user could
             * otherwise refresh forever and never actually be locked out.
             * Unsuspending does not restore sessions — the user signs in
             * again, which is the same experience as any other logout.
             */
            patch("/{userId}") {
                val callerId = requireSuperAdmin(memberships) ?: return@patch
                val targetUserId = call.parameters["userId"]!!

                if (targetUserId == callerId) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "You cannot suspend your own account.")
                    )
                    return@patch
                }

                val target = dbQuery { UsersTable.select { UsersTable.id eq targetUserId }.singleOrNull() }
                if (target == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such user."))
                    return@patch
                }

                val req = call.receive<UpdateUserAdminRequest>()
                if (req.suspended) {
                    memberships.suspendUser(targetUserId)
                    refreshTokens.revokeAllForUser(targetUserId)
                    call.respond(HttpStatusCode.OK, MessageResponse("Account suspended."))
                } else {
                    memberships.unsuspendUser(targetUserId)
                    call.respond(HttpStatusCode.OK, MessageResponse("Account unsuspended."))
                }
            }
        }

        /** Every organization in the system, for the admin panel's organization table. */
        get("/organizations") {
            requireSuperAdmin(memberships) ?: return@get

            val orgs = dbQuery {
                (OrganizationsTable innerJoin UsersTable)
                    .select { Op.TRUE }
                    .orderBy(OrganizationsTable.createdAt to SortOrder.DESC)
                    .toList()
            }

            call.respond(
                orgs.map { row ->
                    val orgId = row[OrganizationsTable.id]
                    AdminOrganizationDto(
                        id = orgId,
                        name = row[OrganizationsTable.name],
                        slug = row[OrganizationsTable.slug],
                        createdByEmail = row[UsersTable.email],
                        memberCount = memberships.activeMemberCount(orgId).toInt(),
                        createdAt = row[OrganizationsTable.createdAt].toString()
                    )
                }
            )
        }

        route("/organization-creation-tokens") {

            /** Every link ever issued, active or not — the raw token is never included. */
            get {
                requireSuperAdmin(memberships) ?: return@get

                call.respond(
                    creationTokens.listAll().map {
                        OrganizationCreationTokenDto(
                            id = it.id,
                            label = it.label,
                            createdByEmail = it.createdByEmail,
                            maxUses = it.maxUses,
                            usesCount = it.usesCount,
                            expiresAt = it.expiresAt.toString(),
                            revokedAt = it.revokedAt?.toString(),
                            createdAt = it.createdAt.toString()
                        )
                    }
                )
            }

            /**
             * Issues a new link. Both bounds are mandatory — see the class doc
             * on `OrganizationCreationTokensTable`: a link with no cap and no
             * expiry is a standing backdoor, not a convenience.
             */
            post {
                val callerId = requireSuperAdmin(memberships) ?: return@post
                val req = call.receive<CreateOrganizationCreationTokenRequest>()

                val errors = buildMap {
                    if (req.maxUses < 1) put("maxUses", "Must allow at least one use.")
                    if (req.expiresInHours < 1) put("expiresInHours", "Must expire at least one hour from now.")
                    if (req.expiresInHours > MAX_EXPIRY_HOURS) {
                        put("expiresInHours", "Cannot exceed $MAX_EXPIRY_HOURS hours (about a year).")
                    }
                }
                if (errors.isNotEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ValidationErrorResponse(errors = errors))
                    return@post
                }

                val (id, rawToken) = creationTokens.issue(
                    createdBy = callerId,
                    label = req.label,
                    maxUses = req.maxUses,
                    expiresAt = LocalDateTime.now().plusHours(req.expiresInHours)
                )
                val issued = creationTokens.getById(id)!! // just inserted, in the same request

                call.respond(
                    HttpStatusCode.Created,
                    CreateOrganizationCreationTokenResponse(
                        token = rawToken,
                        info = OrganizationCreationTokenDto(
                            id = issued.id,
                            label = issued.label,
                            createdByEmail = issued.createdByEmail,
                            maxUses = issued.maxUses,
                            usesCount = issued.usesCount,
                            expiresAt = issued.expiresAt.toString(),
                            revokedAt = issued.revokedAt?.toString(),
                            createdAt = issued.createdAt.toString()
                        )
                    )
                )
            }

            /** Kills a link before its natural expiry. */
            delete("/{id}") {
                requireSuperAdmin(memberships) ?: return@delete
                val id = call.parameters["id"]!!

                if (!creationTokens.revoke(id)) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such link, or it was already revoked."))
                    return@delete
                }
                call.respond(HttpStatusCode.OK, MessageResponse("Link revoked."))
            }
        }
    }
}

/** Upper bound on how far in the future a creation link may expire — about a year. */
private const val MAX_EXPIRY_HOURS = 24L * 365L
