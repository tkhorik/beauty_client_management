package com.beauty.auth

import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.OrganizationInviteLinksTable
import com.beauty.db.OrganizationsTable
import com.beauty.db.UserOrganizationsTable
import com.beauty.db.UsersTable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID

/** One outstanding invite link, joined to the admin who issued it. The raw token is never part of it. */
data class OrganizationInviteLink(
    val id: String,
    val createdByName: String,
    val expiresAt: LocalDateTime,
    val createdAt: LocalDateTime
)

/** The outcome of [OrgInviteLinkService.accept]. */
sealed interface InviteLinkResult {
    /** The caller is now an `ACTIVE` `ORG_USER`; the link is spent. */
    data class Joined(val org: OrgRef, val issuedBy: String) : InviteLinkResult
    /** Already in; the link is left unspent for whoever it was really meant for. */
    data class AlreadyMember(val org: OrgRef) : InviteLinkResult
    /** Revoked in this organization; only an admin's restore lifts that. The link is left unspent. */
    data class Suspended(val org: OrgRef) : InviteLinkResult
    /** Unknown, expired, revoked, used, or its organization is archived. Never says which. */
    data object Invalid : InviteLinkResult
}

/**
 * Issues and redeems an organization admin's single-use invite links.
 *
 * The same token handling as [OrgCreationTokenService] — 256-bit
 * `SecureRandom`, only the SHA-256 hash stored, one generic answer for every
 * kind of rejection — but scoped to one organization, single-use, and always
 * expiring after [LINK_LIFETIME]. Redeeming a link activates the membership
 * outright: the admin issuing it is the approval, see
 * [OrganizationInviteLinksTable].
 */
class OrgInviteLinkService {
    private val random = SecureRandom()

    /** Creates a link for [organizationId] and returns its id alongside the raw token, which is never persisted. */
    suspend fun issue(organizationId: String, createdBy: String, now: LocalDateTime = LocalDateTime.now()): Pair<String, String> {
        val id = UUID.randomUUID().toString()
        val rawToken = generateToken()
        dbQuery {
            OrganizationInviteLinksTable.insert {
                it[OrganizationInviteLinksTable.id] = id
                it[OrganizationInviteLinksTable.organizationId] = organizationId
                it[tokenHash] = hash(rawToken)
                it[OrganizationInviteLinksTable.createdBy] = createdBy
                it[expiresAt] = now.plus(LINK_LIFETIME)
                it[createdAt] = now
            }
        }
        return id to rawToken
    }

    /**
     * The organization a still-redeemable link leads to, for the confirmation
     * screen. Read-only and advisory: [accept] re-checks everything.
     */
    suspend fun preview(rawToken: String): OrgRef? {
        if (rawToken.isBlank()) return null
        val digest = hash(rawToken)
        val now = LocalDateTime.now()
        return dbQuery {
            OrganizationInviteLinksTable
                .join(OrganizationsTable, JoinType.INNER, OrganizationInviteLinksTable.organizationId, OrganizationsTable.id)
                .select { (OrganizationInviteLinksTable.tokenHash eq digest) and redeemable(now) }
                .singleOrNull()
                ?.toOrgRef()
        }
    }

    /**
     * Joins [userId] to the link's organization, spending the link.
     *
     * One transaction, so a link is never spent without the membership it
     * paid for. The claim is an `UPDATE ... WHERE used_at IS NULL`, which is
     * what makes the link single-use under concurrency — a preceding `SELECT`
     * alone would let two people redeem it at once. A caller who is already a
     * member, or suspended here, is answered before the claim so the link
     * survives for the person it was meant for.
     *
     * Any other existing row — pending, invited, declined — becomes `ACTIVE`
     * `ORG_USER`: the link overrides a decline the way an admin's invitation
     * does. The issuing admin is recorded as the decision-maker.
     */
    suspend fun accept(userId: String, rawToken: String, now: LocalDateTime = LocalDateTime.now()): InviteLinkResult {
        if (rawToken.isBlank()) return InviteLinkResult.Invalid
        val digest = hash(rawToken)

        return dbQuery {
            val link = OrganizationInviteLinksTable
                .join(OrganizationsTable, JoinType.INNER, OrganizationInviteLinksTable.organizationId, OrganizationsTable.id)
                .select { (OrganizationInviteLinksTable.tokenHash eq digest) and redeemable(now) }
                .singleOrNull()
                ?: return@dbQuery InviteLinkResult.Invalid
            val org = link.toOrgRef()
            val issuedBy = link[OrganizationInviteLinksTable.createdBy]

            val existing = UserOrganizationsTable
                .select { (UserOrganizationsTable.userId eq userId) and (UserOrganizationsTable.organizationId eq org.id) }
                .singleOrNull()
            when (existing?.get(UserOrganizationsTable.status)) {
                MembershipStatus.ACTIVE.name -> return@dbQuery InviteLinkResult.AlreadyMember(org)
                MembershipStatus.SUSPENDED.name -> return@dbQuery InviteLinkResult.Suspended(org)
            }

            val claimed = OrganizationInviteLinksTable.update({
                (OrganizationInviteLinksTable.tokenHash eq digest) and unspent(now)
            }) {
                it[usedAt] = now
                it[usedBy] = userId
            }
            if (claimed == 0) return@dbQuery InviteLinkResult.Invalid

            if (existing != null) {
                UserOrganizationsTable.update({ UserOrganizationsTable.id eq existing[UserOrganizationsTable.id] }) {
                    it[role] = OrgRole.ORG_USER.name
                    it[status] = MembershipStatus.ACTIVE.name
                    it[decidedBy] = issuedBy
                    it[decidedAt] = now
                    it[updatedAt] = now
                }
            } else {
                UserOrganizationsTable.insert {
                    it[id] = UUID.randomUUID().toString()
                    it[UserOrganizationsTable.userId] = userId
                    it[organizationId] = org.id
                    it[role] = OrgRole.ORG_USER.name
                    it[status] = MembershipStatus.ACTIVE.name
                    it[decidedBy] = issuedBy
                    it[decidedAt] = now
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            InviteLinkResult.Joined(org, issuedBy)
        }
    }

    /** Links of [organizationId] that could still be redeemed, newest first. */
    suspend fun listActive(organizationId: String): List<OrganizationInviteLink> {
        val now = LocalDateTime.now()
        return dbQuery {
            OrganizationInviteLinksTable
                .join(UsersTable, JoinType.INNER, OrganizationInviteLinksTable.createdBy, UsersTable.id)
                .select {
                    (OrganizationInviteLinksTable.organizationId eq organizationId) and
                        OrganizationInviteLinksTable.usedAt.isNull() and
                        OrganizationInviteLinksTable.revokedAt.isNull() and
                        (OrganizationInviteLinksTable.expiresAt greater now)
                }
                .orderBy(OrganizationInviteLinksTable.createdAt to SortOrder.DESC)
                .map { it.toLink() }
        }
    }

    /** One link by id, within [organizationId]. */
    suspend fun getById(organizationId: String, id: String): OrganizationInviteLink? = dbQuery {
        OrganizationInviteLinksTable
            .join(UsersTable, JoinType.INNER, OrganizationInviteLinksTable.createdBy, UsersTable.id)
            .select { (OrganizationInviteLinksTable.id eq id) and (OrganizationInviteLinksTable.organizationId eq organizationId) }
            .singleOrNull()
            ?.toLink()
    }

    /**
     * Kills an unused link. The organization predicate is part of the
     * `UPDATE`, so another organization's link id matches nothing. Returns
     * false if there was no unused, unrevoked link with that id here.
     */
    suspend fun revoke(organizationId: String, id: String): Boolean = dbQuery {
        OrganizationInviteLinksTable.update({
            (OrganizationInviteLinksTable.id eq id) and
                (OrganizationInviteLinksTable.organizationId eq organizationId) and
                OrganizationInviteLinksTable.usedAt.isNull() and
                OrganizationInviteLinksTable.revokedAt.isNull()
        }) {
            it[revokedAt] = LocalDateTime.now()
        } > 0
    }

    /** The link itself is still good. Usable in the claiming `UPDATE`, which cannot see `organizations`. */
    private fun unspent(now: LocalDateTime): Op<Boolean> =
        OrganizationInviteLinksTable.usedAt.isNull() and
            OrganizationInviteLinksTable.revokedAt.isNull() and
            (OrganizationInviteLinksTable.expiresAt greater now)

    /** [unspent], and its organization is not archived. Needs the join to `organizations`. */
    private fun redeemable(now: LocalDateTime): Op<Boolean> =
        unspent(now) and OrganizationsTable.archivedAt.isNull()

    private fun ResultRow.toOrgRef() =
        OrgRef(this[OrganizationsTable.id], this[OrganizationsTable.name], this[OrganizationsTable.slug])

    private fun ResultRow.toLink() = OrganizationInviteLink(
        id = this[OrganizationInviteLinksTable.id],
        createdByName = this[UsersTable.fullName],
        expiresAt = this[OrganizationInviteLinksTable.expiresAt],
        createdAt = this[OrganizationInviteLinksTable.createdAt]
    )

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(rawToken: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(rawToken.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TOKEN_BYTES = 32

        /** Fixed rather than admin-chosen: long enough to reach a new hire, short enough that a forgotten link dies. */
        val LINK_LIFETIME: Duration = Duration.ofDays(7)
    }
}
