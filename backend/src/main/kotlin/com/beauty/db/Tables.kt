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

    /**
     * When the user proved they control this address, or null if they have not.
     *
     * A nullable timestamp rather than a boolean: "verified" and "verified at
     * 09:12 on Tuesday" cost the same to store, and the second answers support
     * questions the first cannot.
     *
     * NOTE: `SchemaUtils.create` does not add columns to an existing table, so
     * this needs `backend/migrations/001_email_verification.sql` run against
     * any database that already has a `users` table.
     */
    val emailVerifiedAt = datetime("email_verified_at").nullable()

    /**
     * System-wide role: `USER` or `SUPER_ADMIN` (see `auth/Roles.GlobalRole`).
     *
     * Deliberately *not* the same column as organization role. Organization
     * membership is many-to-many and lives in [UserOrganizationsTable]; this is
     * the one privilege that has no organization to scope it to. Keeping them
     * apart means an `org_admin` promotion can never accidentally grant global
     * access, which is exactly the mistake a single `role` column invites.
     *
     * Defaults to `USER` so that any insert that forgets it fails closed.
     */
    val globalRole = varchar("global_role", 32).default("USER")

    /**
     * When a `SUPER_ADMIN` locked this account out entirely, or null if it is
     * in good standing.
     *
     * A nullable timestamp rather than a boolean, for the same reason as
     * [emailVerifiedAt]: "suspended since when" is worth having for free.
     * Unlike organization removal, suspension does not delete anything — an
     * admin can lift it — so it is a flag on the row rather than a deleted
     * row. Checked in `MembershipService.accountStatus()`, which already
     * selects this row for every org-scoped request, so enforcing it costs no extra
     * query. `RefreshTokenService.revokeAllForUser()` is called the moment
     * this is set, so a suspended user cannot mint a new access token even
     * though their current one, if any, is not individually revocable.
     *
     * NOTE: `SchemaUtils.create` does not add columns to an existing table —
     * `backend/migrations/003_admin_panel_and_org_creation_links.sql` must run
     * against any database that already has a `users` table.
     */
    val suspendedAt = datetime("suspended_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * A tenant. Every client and visit belongs to exactly one of these.
 *
 * Ownership sits on the organization rather than the user who typed the record
 * in: a salon's client history has to outlive the receptionist who created it,
 * so removing a person from the organization must revoke *their* access without
 * touching the data (see [UserOrganizationsTable]).
 */
object OrganizationsTable : Table("organizations") {
    val id = varchar("id", 64)
    val name = varchar("name", 255)

    /**
     * Lowercase URL-safe handle, unique across the system.
     *
     * This is what a user types when asking to join an organization they were
     * told about verbally. Requests are made by slug rather than by id so that
     * the flow does not require the admin to send a UUID, and unique so that
     * "join aura-downtown" is never ambiguous.
     */
    val slug = varchar("slug", 100).uniqueIndex()

    /** The user who created it. Nullable so deleting a founder never orphans the org. */
    val createdBy = varchar("created_by", 64).references(UsersTable.id).nullable()

    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Membership: which users belong to which organizations, in what capacity.
 *
 * A join table rather than a column on `users`, because a user can belong to
 * several organizations at once with a *different* role in each — a person can
 * own their own studio and be a plain member of a partner salon.
 *
 * [status] carries the onboarding handshake. `PENDING` is a user asking to get
 * in, `INVITED` is an admin asking a user in, and only `ACTIVE` grants any
 * access at all. Every authorization check in `plugins/OrgAccess.kt` matches on
 * `ACTIVE` explicitly — treating "row exists" as "is a member" would let anyone
 * who has merely *requested* to join read the organization's data.
 *
 * Removal deletes the row. There is no `REMOVED` tombstone: an expired
 * membership that is still queryable is one forgotten status check away from
 * being honoured, and the audit value does not justify that risk.
 */
object UserOrganizationsTable : Table("user_organizations") {
    val id = varchar("id", 64)
    val userId = varchar("user_id", 64).references(UsersTable.id).index()
    val organizationId = varchar("organization_id", 64).references(OrganizationsTable.id).index()

    /** `ORG_ADMIN` or `ORG_USER`. See `auth/Roles.OrgRole`. */
    val role = varchar("role", 32)

    /** `ACTIVE`, `PENDING` or `INVITED`. See `auth/Roles.MembershipStatus`. */
    val status = varchar("status", 32)

    /** Who issued the invitation, when this row started as one. */
    val invitedBy = varchar("invited_by", 64).references(UsersTable.id).nullable()

    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        // One row per (user, organization). Without this, a second join request
        // from someone already removed — or a race between an invitation and a
        // request — leaves two rows, and a membership check that finds the
        // wrong one silently grants or denies the wrong thing.
        uniqueIndex(userId, organizationId)
    }
}

/**
 * Single-use tokens for email verification and password reset.
 *
 * One table with a [purpose] discriminator rather than two near-identical
 * tables. The lifecycle is genuinely the same — issue, mail, redeem once,
 * expire — and splitting it would mean two copies of the redemption logic,
 * which is precisely the code where a subtle difference becomes a
 * vulnerability.
 *
 * As with `RefreshTokensTable`, only the SHA-256 hash is stored. These tokens
 * are bearer credentials: anyone holding a valid reset token can take over the
 * account without knowing the password, so a database dump must not hand an
 * attacker a working set of them. (A fast hash is right here, not BCrypt — the
 * token is 256 bits of `SecureRandom` output, so there is nothing to guess and
 * nothing for a slow hash to frustrate.)
 */
object OneTimeTokensTable : Table("one_time_tokens") {
    val id = varchar("id", 64)
    val userId = varchar("user_id", 64).references(UsersTable.id).index()

    /** SHA-256 hex digest of the token. Never the token itself. */
    val tokenHash = varchar("token_hash", 64).uniqueIndex()

    /** `EMAIL_VERIFICATION` or `PASSWORD_RESET`. See `auth/TokenPurpose`. */
    val purpose = varchar("purpose", 32).index()

    val createdAt = datetime("created_at")
    val expiresAt = datetime("expires_at")

    /**
     * Set the moment the token is redeemed.
     *
     * This is what makes the token single-use, and it is not optional: a reset
     * link that still works after the password has been changed lets an
     * attacker who saw the mail once reset the account again at any time before
     * expiry.
     */
    val usedAt = datetime("used_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Long-lived refresh tokens, one row per issued token.
 *
 * Access tokens are short-lived JWTs and are deliberately *not* stored — that
 * is the point of a stateless token. Refresh tokens are the opposite: they
 * live for weeks, so they must be revocable, which means the server has to
 * know about them.
 *
 * Only the SHA-256 hash of the token is stored. A refresh token is a bearer
 * credential, so a dump of this table must not hand an attacker a working set
 * of logins. (Unlike a password, the token is high-entropy random, so a plain
 * fast hash is appropriate here — BCrypt's slowness exists to frustrate
 * dictionary attacks on guessable input, and there is nothing to guess.)
 */
object RefreshTokensTable : Table("refresh_tokens") {
    val id = varchar("id", 64)
    val userId = varchar("user_id", 64).references(UsersTable.id)

    /** SHA-256 hex digest of the token. Never the token itself. */
    val tokenHash = varchar("token_hash", 64).uniqueIndex()

    /**
     * Groups every token descended from one login. Rotation issues a new token
     * in the same family, so detecting reuse of a spent token lets us revoke
     * the entire chain rather than just the one row.
     */
    val familyId = varchar("family_id", 64).index()

    val issuedAt = datetime("issued_at")
    val expiresAt = datetime("expires_at")

    /** Set when the token is spent by rotation, or explicitly revoked by logout. */
    val revokedAt = datetime("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Admin-issued tokens that gate `POST /api/organizations`.
 *
 * Organization creation stopped being self-service (see `OrganizationRoutes.kt`
 * for the join-by-slug path everyone else uses): a caller now needs one of
 * these, unspent, to create a new tenant. Deliberately not a row in
 * [OneTimeTokensTable] despite the similar security properties — that table's
 * whole design assumes one owning [OneTimeTokensTable.userId] and exactly one
 * redemption. A creation link is handed out by an admin to *whoever* has it,
 * for a configurable number of uses, which needs a counter rather than a
 * single `used_at` timestamp.
 *
 * As with the other bearer-token tables, only the SHA-256 hash is stored.
 */
object OrganizationCreationTokensTable : Table("organization_creation_tokens") {
    val id = varchar("id", 64)

    /** SHA-256 hex digest of the token. Never the raw value — see [RefreshTokensTable]. */
    val tokenHash = varchar("token_hash", 64).uniqueIndex()

    /** Admin-facing note only, e.g. "Q3 salon onboarding batch". Never shown to the redeemer. */
    val label = varchar("label", 255).nullable()

    val createdBy = varchar("created_by", 64).references(UsersTable.id)

    /**
     * How many times this link may be redeemed, and how many times it has
     * been. Required rather than nullable-for-unlimited: an admin panel that
     * can mint an infinite-use, non-expiring link is a standing backdoor, so
     * both bounds are mandatory at creation time.
     *
     * Enforced by an atomic `UPDATE ... WHERE uses_count < max_uses`, the same
     * technique [OneTimeTokensTable.usedAt] uses for single-use tokens,
     * generalised from a boolean to a counter so concurrent redemptions of an
     * N-use link cannot oversell it.
     */
    val maxUses = integer("max_uses")
    val usesCount = integer("uses_count").default(0)

    /** Required, not nullable — every link expires. */
    val expiresAt = datetime("expires_at")

    /** Set when an admin kills the link before its natural expiry. */
    val revokedAt = datetime("revoked_at").nullable()

    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

object ClientsTable : Table("clients") {
    val id = varchar("id", 64)

    /**
     * The owning tenant. Not nullable, and not defaulted.
     *
     * Every read of this table must be filtered on it. A nullable column would
     * mean "belongs to no one", and the natural handling of that — skip the
     * filter — is precisely the cross-tenant leak this whole feature exists to
     * prevent. The database refusing the insert is a better failure than a row
     * nobody can safely query.
     */
    val organizationId = varchar("organization_id", 64).references(OrganizationsTable.id).index()

    /** Who first entered this record. Informational only — access comes from [organizationId]. */
    val createdBy = varchar("created_by", 64).references(UsersTable.id).nullable()

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

    /**
     * Denormalised copy of the parent client's organization.
     *
     * Redundant — it is always `clients.organization_id` for [clientId] — and
     * kept anyway, because the alternative is that every visit query joins
     * `clients` to find out who may see it, and the one query that forgets the
     * join leaks. A `WHERE organization_id = ?` that is impossible to omit is
     * worth the duplication. `VisitRoutes` derives it from the client on insert
     * and never accepts it from the request body, so the two cannot diverge.
     */
    val organizationId = varchar("organization_id", 64).references(OrganizationsTable.id).index()

    /** Who recorded this visit. Informational only — access comes from [organizationId]. */
    val createdBy = varchar("created_by", 64).references(UsersTable.id).nullable()

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
