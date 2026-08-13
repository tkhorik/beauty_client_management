package com.beauty.routes

import com.beauty.auth.MembershipService
import com.beauty.auth.MembershipStatus
import com.beauty.auth.OrgCreationTokenService
import com.beauty.auth.OrgRole
import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.OrganizationsTable
import com.beauty.db.UsersTable
import com.beauty.models.*
import com.beauty.plugins.ORG_HEADER
import com.beauty.plugins.OrgContext
import com.beauty.plugins.RATE_LIMIT_AUTH
import com.beauty.plugins.requireActiveAccount
import com.beauty.plugins.requireOrgAccess
import com.beauty.plugins.requireWritableAccount
import com.beauty.validation.Validation
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import java.time.LocalDateTime
import java.util.UUID

/** PostgreSQL SQLSTATE for a unique-constraint violation. */
private const val SQLSTATE_UNIQUE_VIOLATION = "23505"

/**
 * Organization lifecycle and membership management.
 *
 * Mounted inside `authenticate("auth-jwt")` — everything here needs to know who
 * is asking. Note what is deliberately *absent*: there is no endpoint that
 * lists organizations, or looks one up by name. Joining requires knowing the
 * slug, which someone inside has to tell you, so the API never becomes a
 * directory of every salon using the product.
 *
 * The split between the two route groups matters:
 *  - `/api/organizations` (no id): things a user with no organization at all
 *    must still be able to do — list what they have, create one, ask to join
 *    one. These use [requireActiveAccount] rather than [requireOrgAccess],
 *    because requiring an organization context here would lock a brand-new
 *    account out of ever getting one — but a suspended account still must not
 *    reach any of them.
 *  - `/api/organizations/{orgId}/...`: management of a specific organization,
 *    gated by [requireOrgAccess] with `requireAdmin = true`.
 */
fun Route.organizationRoutes() {
    val memberships = MembershipService()
    val creationTokens = OrgCreationTokenService()

    route("/api/organizations") {

        /**
         * Everything the caller belongs to or has asked to belong to.
         *
         * The client calls this immediately after login to decide between the
         * app proper, an organization picker, and the "you're not in one yet"
         * onboarding screen. Pending and invited rows are included so a user
         * who has already asked to join sees that, instead of asking again and
         * hitting the unique index.
         */
        get {
            val userId = requireActiveAccount(memberships) ?: return@get

            call.respond(
                memberships.organizationsForUser(userId).map {
                    OrganizationDto(
                        id = it.organizationId,
                        name = it.organizationName,
                        slug = it.organizationSlug,
                        role = it.role.name,
                        status = it.status.name
                    )
                }
            )
        }

        /**
         * Validates a creation token before the client shows the create form,
         * without spending a use.
         *
         * Purely advisory — a token valid here can still be exhausted, revoked
         * or expired by the time [post] actually redeems it, so that endpoint
         * re-checks unconditionally. This exists only so the UI can say
         * "this link is invalid" up front instead of after the user has
         * filled in a name and handle.
         */
        get("/creation-tokens/validate") {
            val token = call.request.queryParameters["token"].orEmpty()
            call.respond(HttpStatusCode.OK, ValidateCreationTokenResponse(valid = creationTokens.isRedeemable(token)))
        }

        /**
         * Creates an organization; the caller becomes its first `ORG_ADMIN`.
         *
         * No longer self-service. Free organization creation meant anyone who
         * registered could stand up a tenant, which is fine for a single-salon
         * deployment but not for one meant to onboard salons deliberately —
         * see the admin panel's organization-creation links
         * (`AdminRoutes.kt`, `OrgCreationTokenService`). A caller with no
         * organization who lacks a link uses `/join-requests` below instead.
         *
         * Rate-limited under the same bucket as login/register: the token
         * itself makes guessing hopeless (256 bits of `SecureRandom`), but the
         * bound costs nothing.
         *
         * Also gated on email verification, unlike `/join-requests` below.
         * A creation link is issued to a person the operator decided to onboard,
         * so requiring them to confirm the address it was sent to costs nothing
         * legitimate — and the grace window means a new salon acting on a fresh
         * link is never blocked in practice.
         */
        rateLimit(RateLimitName(RATE_LIMIT_AUTH)) {
        post {
            // requireWritableAccount = requireActiveAccount (suspension) plus
            // the verification gate. Joining below deliberately uses the looser
            // one.
            val userId = requireWritableAccount(memberships) ?: return@post

            val req = call.receive<CreateOrganizationRequest>()
            val name = req.name.trim()
            val slug = (req.slug?.trim()?.lowercase() ?: Validation.slugify(name))

            val errors = buildMap {
                Validation.validateOrganizationName(name)?.let { put("name", it) }
                Validation.validateOrganizationSlug(slug)?.let { put("slug", it) }
            }
            if (errors.isNotEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ValidationErrorResponse(errors = errors))
                return@post
            }

            // Checked after field validation (so a malformed request gets a
            // cheap 400 without spending a use) and before the insert (so a
            // request that turns out to be unauthorized never creates
            // anything). A slug conflict discovered after this point still
            // spends the use it already claimed — an accepted, rare cost
            // rather than wrapping two separate services' writes in one
            // cross-table transaction.
            val redemption = creationTokens.redeem(req.creationToken.orEmpty())
            if (redemption is OrgCreationTokenService.Redemption.Invalid) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf(
                        "error" to "A valid organization-creation link is required to create a new organization.",
                        "code" to "CREATION_TOKEN_INVALID"
                    )
                )
                return@post
            }

            val id = UUID.randomUUID().toString()
            val now = LocalDateTime.now()

            // As with registration, the unique index is the real guarantee —
            // two concurrent creates can both pass a pre-check — so the
            // violation is handled here rather than surfacing as a 500.
            try {
                dbQuery {
                    OrganizationsTable.insert {
                        it[OrganizationsTable.id] = id
                        it[OrganizationsTable.name] = name
                        it[OrganizationsTable.slug] = slug
                        it[createdBy] = userId
                        it[createdAt] = now
                    }
                }
            } catch (e: ExposedSQLException) {
                if ((e.cause as? java.sql.SQLException)?.sqlState == SQLSTATE_UNIQUE_VIOLATION ||
                    e.sqlState == SQLSTATE_UNIQUE_VIOLATION
                ) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ValidationErrorResponse(errors = mapOf("slug" to "That organization handle is already taken."))
                    )
                    return@post
                }
                throw e
            }

            memberships.upsert(userId, id, OrgRole.ORG_ADMIN, MembershipStatus.ACTIVE)

            call.respond(
                HttpStatusCode.Created,
                OrganizationDto(
                    id = id,
                    name = name,
                    slug = slug,
                    role = OrgRole.ORG_ADMIN.name,
                    status = MembershipStatus.ACTIVE.name,
                    createdAt = now.toString()
                )
            )
        }
        } // end rateLimit(RATE_LIMIT_AUTH)

        /**
         * Asks to join an organization by slug, or accepts a standing invitation.
         *
         * Creates a `PENDING` row, which grants nothing until an admin approves
         * it — so this endpoint being open to any authenticated user costs
         * nothing but a row in someone's approval queue.
         *
         * The one case that is *not* pending: if the caller was already invited,
         * this activates the membership immediately. Both sides have now agreed,
         * and making the admin re-approve an invitation they themselves sent is
         * a pointless round trip.
         *
         * Answers 404 for an unknown slug. That is an enumeration oracle in the
         * strict sense, but slugs are chosen to be shared out loud and there is
         * no way to offer "type your salon's handle" without confirming whether
         * the handle exists.
         *
         * **Deliberately open to unverified accounts**, and the one write-shaped
         * endpoint that is. Joining grants nothing by itself — a `PENDING` row
         * is inert until an admin approves it, and an `INVITED` one means an
         * admin already vouched for this person. Blocking it would strand a new
         * hire outside the organization they were invited to and give them
         * nothing to look at, which is a worse first impression than an
         * unconfirmed address warrants. They land inside the organization in
         * read-only mode and stay there until they verify, which is exactly the
         * intended shape of the restriction.
         */
        post("/join-requests") {
            val userId = requireActiveAccount(memberships) ?: return@post

            val slug = call.receive<JoinOrganizationRequest>().slug.trim().lowercase()
            val org = dbQuery {
                OrganizationsTable.select { OrganizationsTable.slug eq slug }.singleOrNull()
            }
            if (org == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No organization with that handle."))
                return@post
            }

            val orgId = org[OrganizationsTable.id]
            val existing = memberships.membership(userId, orgId)

            val resulting = when (existing?.status) {
                MembershipStatus.ACTIVE -> {
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "You are already a member of this organization.")
                    )
                    return@post
                }
                // The admin already asked them in; this is the acceptance.
                MembershipStatus.INVITED -> {
                    memberships.activate(userId, orgId)
                    MembershipStatus.ACTIVE
                }
                MembershipStatus.PENDING -> MembershipStatus.PENDING // idempotent re-request
                // A blocked membership does not lift itself by re-requesting —
                // that would make suspension pointless. Only an admin's
                // explicit unsuspend action (PATCH .../members/{userId}) may
                // restore access.
                MembershipStatus.SUSPENDED -> {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf(
                            "error" to "Your access to this organization has been suspended. Contact an administrator.",
                            "code" to "MEMBERSHIP_SUSPENDED"
                        )
                    )
                    return@post
                }
                null -> {
                    memberships.upsert(userId, orgId, OrgRole.ORG_USER, MembershipStatus.PENDING)
                    MembershipStatus.PENDING
                }
            }

            call.respond(
                HttpStatusCode.OK,
                OrganizationDto(
                    id = orgId,
                    name = org[OrganizationsTable.name],
                    slug = slug,
                    role = (existing?.role ?: OrgRole.ORG_USER).name,
                    status = resulting.name
                )
            )
        }

        // -------------------------------------------------------------------
        // Administration of one organization
        // -------------------------------------------------------------------
        route("/{orgId}/members") {

            /**
             * The roster, including pending requests and outstanding invitations
             * — this doubles as the admin's approval queue.
             *
             * Admin-only. A plain member has no need for colleagues' email
             * addresses, and the list is exactly what an attacker who
             * compromised one account would want next.
             */
            get {
                val ctx = requireOrgAccess(memberships, requireAdmin = true) ?: return@get
                val orgId = call.parameters["orgId"]!!
                if (!ctx.matches(orgId)) return@get call.respondOrgMismatch()

                call.respond(
                    memberships.membersOf(orgId).map {
                        MemberDto(
                            userId = it.userId,
                            email = it.email,
                            fullName = it.fullName,
                            role = it.role.name,
                            status = it.status.name,
                            joinedAt = it.joinedAt
                        )
                    }
                )
            }

            /** Approves a pending join request. */
            post("/{userId}/approval") {
                val ctx = requireOrgAccess(memberships, requireAdmin = true) ?: return@post
                val orgId = call.parameters["orgId"]!!
                val targetUserId = call.parameters["userId"]!!
                if (!ctx.matches(orgId)) return@post call.respondOrgMismatch()

                val existing = memberships.membership(targetUserId, orgId)
                if (existing == null || existing.status != MembershipStatus.PENDING) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "No pending request from that user."))
                    return@post
                }

                memberships.activate(targetUserId, orgId, OrgRole.ORG_USER)
                call.respond(HttpStatusCode.OK, MessageResponse("Request approved."))
            }

            /**
             * Invites an existing account into the organization.
             *
             * Only matches accounts that already exist — there is no
             * invite-by-email-to-a-stranger flow, which would make this endpoint
             * a way to send mail to arbitrary addresses. Answers 404 for an
             * unknown address; the caller is an authenticated admin, so this is
             * a far narrower disclosure than the public auth surface, and the
             * alternative is an admin staring at a silent no-op.
             */
            post("/invitations") {
                val ctx = requireOrgAccess(memberships, requireAdmin = true) ?: return@post
                val orgId = call.parameters["orgId"]!!
                if (!ctx.matches(orgId)) return@post call.respondOrgMismatch()

                val req = call.receive<InviteMemberRequest>()
                val email = Validation.normaliseEmail(req.email)
                val role = OrgRole.parse(req.role)

                val target = dbQuery { UsersTable.select { UsersTable.email eq email }.singleOrNull() }
                if (target == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "No account with that email address. Ask them to register first.")
                    )
                    return@post
                }

                val targetUserId = target[UsersTable.id]
                val existing = memberships.membership(targetUserId, orgId)

                when (existing?.status) {
                    MembershipStatus.ACTIVE -> {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "That user is already a member."))
                        return@post
                    }
                    // They asked, the admin is now asking back: both sides agree,
                    // so this is an approval rather than a second invitation.
                    MembershipStatus.PENDING -> memberships.activate(targetUserId, orgId, role)
                    // A suspension is a deliberate block; re-inviting must not
                    // be a side-channel around it. The admin has to unsuspend
                    // explicitly via PATCH .../members/{userId} first.
                    MembershipStatus.SUSPENDED -> {
                        call.respond(
                            HttpStatusCode.Conflict,
                            mapOf("error" to "That user is suspended in this organization. Unsuspend them first.")
                        )
                        return@post
                    }
                    else -> memberships.upsert(
                        userId = targetUserId,
                        organizationId = orgId,
                        role = role,
                        status = MembershipStatus.INVITED,
                        invitedBy = ctx.userId
                    )
                }

                call.respond(HttpStatusCode.OK, MessageResponse("Invitation sent."))
            }

            /**
             * Changes a member's role.
             *
             * Refuses to demote the last admin. An organization with no admin
             * can no longer approve, invite, or promote anyone — it is
             * unrecoverable without direct database access, so this check earns
             * its extra query.
             */
            patch("/{userId}") {
                val ctx = requireOrgAccess(memberships, requireAdmin = true) ?: return@patch
                val orgId = call.parameters["orgId"]!!
                val targetUserId = call.parameters["userId"]!!
                if (!ctx.matches(orgId)) return@patch call.respondOrgMismatch()

                val role = OrgRole.parse(call.receive<ChangeMemberRoleRequest>().role)
                val existing = memberships.membership(targetUserId, orgId)
                if (existing == null || existing.status != MembershipStatus.ACTIVE) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "That user is not a member."))
                    return@patch
                }

                if (existing.role == OrgRole.ORG_ADMIN &&
                    role != OrgRole.ORG_ADMIN &&
                    memberships.activeAdminCount(orgId) <= 1
                ) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "This is the only administrator. Promote someone else first.")
                    )
                    return@patch
                }

                memberships.changeRole(targetUserId, orgId, role)
                call.respond(HttpStatusCode.OK, MessageResponse("Role updated."))
            }

            /**
             * Removes a member, or withdraws a pending/invited row.
             *
             * Their access ends on their **next request**, not when their token
             * expires: every data route re-reads membership from the database
             * (see `plugins/OrgAccess.kt`), so there is no window in which a
             * removed user's still-valid access token keeps working.
             *
             * The organization's clients and visits are untouched, including
             * ones this user created. That is the point of hanging ownership off
             * `organization_id` rather than a creator id — a salon does not lose
             * its client history because a stylist left.
             */
            delete("/{userId}") {
                val ctx = requireOrgAccess(memberships, requireAdmin = true) ?: return@delete
                val orgId = call.parameters["orgId"]!!
                val targetUserId = call.parameters["userId"]!!
                if (!ctx.matches(orgId)) return@delete call.respondOrgMismatch()

                val existing = memberships.membership(targetUserId, orgId)
                if (existing == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "That user is not a member."))
                    return@delete
                }

                if (existing.status == MembershipStatus.ACTIVE &&
                    existing.role == OrgRole.ORG_ADMIN &&
                    memberships.activeAdminCount(orgId) <= 1
                ) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "This is the only administrator. Promote someone else first.")
                    )
                    return@delete
                }

                memberships.remove(targetUserId, orgId)
                call.respond(HttpStatusCode.OK, MessageResponse("Member removed."))
            }
        }
    }
}

/**
 * Guards against a path id that disagrees with the header the access check used.
 *
 * [requireOrgAccess] authorizes the organization named in `X-Org-Id`, while
 * these routes act on `{orgId}` from the path. If a client sends a header for
 * an organization it administers and a path id for one it does not, the check
 * would pass for the wrong organization — so the two must be identical.
 *
 * A super admin operating globally (null [OrgContext.organizationId]) matches
 * anything by definition.
 */
private fun OrgContext.matches(pathOrgId: String): Boolean =
    organizationId == null || organizationId == pathOrgId

private suspend fun ApplicationCall.respondOrgMismatch() = respond(
    HttpStatusCode.Forbidden,
    mapOf(
        "error" to "The $ORG_HEADER header does not match the organization in the path.",
        "code" to "ORGANIZATION_MISMATCH"
    )
)
