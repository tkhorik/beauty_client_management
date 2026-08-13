package com.beauty.plugins

import com.beauty.auth.AccountStatus
import com.beauty.auth.GlobalRole
import com.beauty.auth.MembershipService
import com.beauty.auth.OrgRole
import com.beauty.auth.VerificationPolicy
import com.beauty.config.AppSettings
import com.beauty.models.VerificationRequiredResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
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
 *  - 403 `EMAIL_NOT_VERIFIED`: everything checks out, but the caller has never
 *    confirmed their address and their grace window has run out. Reads still
 *    succeed; only writes land here.
 *
 * @param requireAdmin gate the route to `org_admin`/`super_admin`.
 * @param allowGlobal let a super admin omit the header and operate across all
 *   organizations. Off by default so that a route which forgets to think about
 *   the unscoped case cannot accidentally get it.
 * @param requireVerified override the email-verification gate. Null — the
 *   default — derives it from the HTTP method, which is the important part:
 *   see [requiresWriteAccess].
 */
suspend fun PipelineContext<Unit, ApplicationCall>.requireOrgAccess(
    memberships: MembershipService,
    requireAdmin: Boolean = false,
    allowGlobal: Boolean = false,
    requireVerified: Boolean? = null
): OrgContext? {
    val userId = call.userId()
    if (userId == null) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
        return null
    }

    // One row read, serving the role check, the suspension check and the
    // email-verification gate below.
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

    // Checked last, after membership and role. A caller who is both a
    // non-member and unverified should be told the membership problem: it is
    // the one that explains why they cannot see the organization at all, and
    // fixing verification would not help them.
    if (!passesVerificationGate(account, requireVerified)) return null

    // The verification state deliberately does not travel on the context.
    // Route bodies never need it — by the time one runs, the gate has already
    // allowed the request — and the refusal response carries the deadline for
    // the clients. A field here would be read by nobody and wrong for the
    // super-admin paths above, which return before the account is consulted.
    return OrgContext(userId, requestedOrg, membership.role, isSuperAdmin = false)
}

/**
 * Whether this request should be treated as a write for verification purposes.
 *
 * **Derived from the HTTP method rather than declared per route, and that is
 * deliberate.** The obvious design — a `requireVerified = true` argument at
 * each write call site — fails open: a route added later by someone who has
 * never heard of this feature simply omits it and is silently exempt. There is
 * no compiler error and no failing test for a gate nobody wrote. Deriving it
 * from the method inverts that: a new `post`/`put`/`patch`/`delete` handler is
 * covered the moment it calls [requireOrgAccess], and *exempting* one requires
 * writing `requireVerified = false`, which is visible in review.
 *
 * GET and HEAD are reads. OPTIONS is a CORS preflight and never carries intent.
 */
private fun requiresWriteAccess(method: HttpMethod): Boolean =
    method !in setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

/**
 * Applies the verification gate, answering 403 and returning false when it
 * fails. Shared by [requireOrgAccess] and [requireWritableAccount].
 */
private suspend fun PipelineContext<Unit, ApplicationCall>.passesVerificationGate(
    account: AccountStatus,
    requireVerified: Boolean?
): Boolean {
    val gated = requireVerified ?: requiresWriteAccess(call.request.httpMethod)
    if (!gated) return true

    val policy = call.application.verificationPolicy()
    if (policy.canWrite(account)) return true

    call.respond(
        HttpStatusCode.Forbidden,
        VerificationRequiredResponse(
            error = "Confirm your email address to make changes. " +
                "We sent a link when you registered — request a new one from your account settings.",
            code = EMAIL_NOT_VERIFIED,
            // Echoed so a client can say "your window closed on the 3rd"
            // rather than leaving the user guessing why yesterday worked.
            verificationDeadline = policy.deadlineFor(account)?.toString()
        )
    )
    return false
}

/** The error code clients match on to show the verification banner. */
const val EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED"

/**
 * [requireActiveAccount] plus the email-verification gate, for a *write* with
 * no organization context — `POST /api/organizations` is the only one today.
 *
 * Kept separate from [requireActiveAccount] rather than folded into it, because
 * the routes that use that one are reads and a join request: listing your own
 * organizations and asking to join one both stay open to an unverified account
 * on purpose. Only the route that mints a new tenant needs this stricter
 * version.
 */
suspend fun PipelineContext<Unit, ApplicationCall>.requireWritableAccount(
    memberships: MembershipService
): String? {
    val userId = call.userId()
    if (userId == null) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
        return null
    }

    val account = memberships.accountStatus(userId)

    // Suspension is checked first: it is the more severe state and the one the
    // caller can do nothing about on their own, so telling a suspended account
    // to "confirm your email" would send them off to fix the wrong problem.
    if (account.isSuspended) {
        call.respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "This account has been suspended.", "code" to "ACCOUNT_SUSPENDED")
        )
        return null
    }

    if (!passesVerificationGate(account, requireVerified = null)) return null

    return userId
}

private val VerificationPolicyKey = AttributeKey<VerificationPolicy>("VerificationPolicy")

/**
 * The policy, built once per application rather than per request.
 *
 * `AppSettings` re-reads and re-parses configuration on construction, and this
 * sits on the path of every authorized request. Caching it on the application
 * keeps that off the hot path while leaving the value a plain function of
 * configuration — it is rebuilt on restart, which is exactly when the
 * environment variable can change.
 */
fun Application.verificationPolicy(): VerificationPolicy =
    attributes.computeIfAbsent(VerificationPolicyKey) {
        VerificationPolicy(AppSettings(environment.config))
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
