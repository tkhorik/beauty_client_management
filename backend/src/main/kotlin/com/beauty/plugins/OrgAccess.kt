package com.beauty.plugins

import com.beauty.auth.GlobalRole
import com.beauty.auth.MembershipService
import com.beauty.auth.OrgRole
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.util.pipeline.PipelineContext

/**
 * The header a client uses to say which organization a request is about.
 *
 * An explicit header rather than a "current organization" stored server-side
 * per session. A user can be signed in to the same account in two tabs looking
 * at two different salons, and server-side state would make one of those tabs
 * silently start writing into the other's data. It is also visible in logs and
 * trivially reproducible with curl, which server-side session state is not.
 */
const val ORG_HEADER = "X-Org-Id"

/**
 * The authorization facts for one request, resolved fresh from the database.
 *
 * @property organizationId The organization to scope every query to, or null
 *   for a super admin who named no organization — meaning "all of them". Route
 *   code must treat null as "apply no organization filter", which is only ever
 *   reachable via [isSuperAdmin]; see [scopedTo].
 * @property role The caller's capability *within* [organizationId]. A super
 *   admin always gets [OrgRole.ORG_ADMIN] here, so admin-only route bodies need
 *   no separate super-admin branch.
 */
data class OrgContext(
    val userId: String,
    val organizationId: String?,
    val role: OrgRole,
    val isSuperAdmin: Boolean
) {
    /** True when the caller may perform organization-management actions. */
    val isAdmin: Boolean get() = isSuperAdmin || role == OrgRole.ORG_ADMIN

    /**
     * The organization id a query should filter on, or null for unrestricted.
     *
     * Exists so the intent reads clearly at the call site: a bare nullable
     * field invites `?: someDefault`, and any default here is a cross-tenant
     * leak waiting to happen.
     */
    val scopedTo: String? get() = organizationId
}

/**
 * Resolves the caller's organization context, answering the request with an
 * error and returning null when they have none.
 *
 * Every data route calls this **on every request**, and it hits the database
 * every time. That is the mechanism behind immediate revocation: an
 * `org_admin` who removes someone has that take effect on the removed user's
 * very next call, rather than whenever their access token happens to lapse.
 * Caching this, or moving it into a JWT claim, reintroduces exactly the
 * staleness window that design was chosen to avoid.
 *
 * Failure modes are distinguished on purpose, because they are not the same
 * problem and the client must react differently to each:
 *  - 401: no valid token at all.
 *  - 400 `MISSING_ORGANIZATION`: authenticated but did not say which
 *    organization — the client should send [ORG_HEADER].
 *  - 403 `NOT_A_MEMBER`: named an organization they do not actively belong to.
 *    Also the answer for an organization that does not exist, and for one where
 *    the caller's request to join is still pending: distinguishing those would
 *    turn this into a probe for which organization ids are real.
 *  - 403 `ADMIN_REQUIRED`: an active member, but not an admin, on an
 *    admin-only route.
 *
 * @param requireAdmin gate the route to `org_admin`/`super_admin`.
 * @param allowGlobal let a super admin omit the header and operate across all
 *   organizations. Off by default so that a route which forgets to think about
 *   the unscoped case cannot accidentally get it.
 */
suspend fun PipelineContext<Unit, ApplicationCall>.requireOrgAccess(
    memberships: MembershipService,
    requireAdmin: Boolean = false,
    allowGlobal: Boolean = false
): OrgContext? {
    val userId = call.userId()
    if (userId == null) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
        return null
    }

    val account = memberships.accountStatus(userId)
    if (account.isSuspended) {
        // Checked before anything else, and from the same row read that
        // resolves SUPER_ADMIN below — a suspended super admin must not fall
        // through to unscoped access. Existing refresh-token families are
        // revoked by the admin route that set this, so the token behind this
        // request is the last one that will ever work; it stops working the
        // moment it expires.
        call.respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "This account has been suspended.", "code" to "ACCOUNT_SUSPENDED")
        )
        return null
    }

    val isSuperAdmin = account.globalRole == GlobalRole.SUPER_ADMIN
    val requestedOrg = call.request.headers[ORG_HEADER]?.trim()?.takeIf { it.isNotEmpty() }

    if (requestedOrg == null) {
        // A super admin with no header is asking about the whole system. Anyone
        // else simply has not told us what they want, and guessing — "use their
        // only organization", "use the first one" — is how a user with two
        // salons writes a client into the wrong one.
        if (isSuperAdmin && allowGlobal) {
            return OrgContext(userId, null, OrgRole.ORG_ADMIN, isSuperAdmin = true)
        }
        call.respond(
            HttpStatusCode.BadRequest,
            mapOf(
                "error" to "No organization selected. Send the $ORG_HEADER header.",
                "code" to "MISSING_ORGANIZATION"
            )
        )
        return null
    }

    if (isSuperAdmin) {
        return OrgContext(userId, requestedOrg, OrgRole.ORG_ADMIN, isSuperAdmin = true)
    }

    val membership = memberships.activeMembership(userId, requestedOrg)
    if (membership == null) {
        // Deliberately the same answer whether the organization does not exist,
        // the caller was never in it, or they were removed a second ago. Any
        // difference is a way to enumerate organization ids from outside.
        call.respond(
            HttpStatusCode.Forbidden,
            mapOf(
                "error" to "You do not have access to this organization.",
                "code" to "NOT_A_MEMBER"
            )
        )
        return null
    }

    if (requireAdmin && membership.role != OrgRole.ORG_ADMIN) {
        call.respond(
            HttpStatusCode.Forbidden,
            mapOf(
                "error" to "This action requires an organization administrator.",
                "code" to "ADMIN_REQUIRED"
            )
        )
        return null
    }

    return OrgContext(userId, requestedOrg, membership.role, isSuperAdmin = false)
}

/**
 * Resolves the caller as a `SUPER_ADMIN` in good standing, answering an error
 * and returning null otherwise.
 *
 * The admin panel's own guard — deliberately simpler than [requireOrgAccess]:
 * there is no organization to scope, just "is this account a super admin".
 * Suspension is checked here too, for the same reason it is checked in
 * [requireOrgAccess]: a suspended account, even a `SUPER_ADMIN` one, must lose
 * access on its very next request rather than whenever its token expires.
 */
suspend fun PipelineContext<Unit, ApplicationCall>.requireSuperAdmin(
    memberships: MembershipService
): String? {
    val userId = call.userId()
    if (userId == null) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
        return null
    }

    val account = memberships.accountStatus(userId)
    if (account.isSuspended) {
        call.respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "This account has been suspended.", "code" to "ACCOUNT_SUSPENDED")
        )
        return null
    }

    if (account.globalRole != GlobalRole.SUPER_ADMIN) {
        call.respond(
            HttpStatusCode.Forbidden,
            mapOf(
                "error" to "This action requires a super administrator.",
                "code" to "SUPER_ADMIN_REQUIRED"
            )
        )
        return null
    }

    return userId
}

/**
 * Resolves the caller as an authenticated account in good standing — no
 * organization scoping at all, unlike [requireOrgAccess].
 *
 * For the "no orgId" routes in `OrganizationRoutes.kt` — listing the
 * caller's own organizations, requesting to join one, creating one — which
 * use `call.userId()` directly rather than [requireOrgAccess] specifically so
 * a brand-new account with no organization yet is never locked out of
 * getting one (see that file's class doc). That reasoning has nothing to do
 * with suspension, though, and a suspended account creating a *new*
 * organization or requesting to join one defeats the point of suspending
 * them. This is the same DB read [requireOrgAccess] and [requireSuperAdmin]
 * already do, applied without the organization-scoping logic those two add.
 */
suspend fun PipelineContext<Unit, ApplicationCall>.requireActiveAccount(
    memberships: MembershipService
): String? {
    val userId = call.userId()
    if (userId == null) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
        return null
    }

    if (memberships.accountStatus(userId).isSuspended) {
        call.respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "This account has been suspended.", "code" to "ACCOUNT_SUSPENDED")
        )
        return null
    }

    return userId
}
