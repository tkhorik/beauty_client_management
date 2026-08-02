package com.beauty.auth

import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.OrganizationsTable
import com.beauty.db.UserOrganizationsTable
import com.beauty.db.UsersTable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

/**
 * One membership row, resolved.
 *
 * [status] is carried rather than filtered out at the source so callers that
 * legitimately care about pending and invited rows — the admin's queue, the
 * user's own list of outstanding requests — can use the same type. Callers
 * making an *authorization* decision must go through [MembershipService.activeMembership],
 * which never returns anything but [MembershipStatus.ACTIVE].
 */
data class Membership(
    val id: String,
    val userId: String,
    val organizationId: String,
    val role: OrgRole,
    val status: MembershipStatus
)

/** A membership joined to the organization it belongs to, for listing to a user. */
data class MembershipWithOrg(
    val organizationId: String,
    val organizationName: String,
    val organizationSlug: String,
    val role: OrgRole,
    val status: MembershipStatus
)

/** A membership joined to the user it belongs to, for an admin's member list. */
data class MembershipWithUser(
    val userId: String,
    val email: String,
    val fullName: String,
    val role: OrgRole,
    val status: MembershipStatus,
    val joinedAt: String
)

/**
 * The single source of truth for "who belongs to what, and how".
 *
 * Every authorization decision in the API funnels through [activeMembership] or
 * [globalRole]. That concentration is the point: membership is checked against
 * the database on every request rather than read from the access token, so that
 * removing someone from an organization takes effect on their very next call
 * instead of whenever their 15-minute JWT happens to expire. Immediate
 * revocation was a requirement, and a token claim cannot provide it.
 *
 * The cost is one indexed lookup per request. That is cheap, and the
 * alternative — a cache — reintroduces exactly the staleness window the token
 * approach was rejected for.
 */
class MembershipService {

    // -----------------------------------------------------------------------
    // Authorization reads
    // -----------------------------------------------------------------------

    /**
     * The user's membership of [organizationId], or null if they have none that
     * grants access.
     *
     * Matches `status = ACTIVE` in SQL rather than fetching the row and testing
     * afterwards. A caller who forgets the follow-up check would otherwise
     * treat a `PENDING` request — something any stranger can create — as
     * membership.
     */
    suspend fun activeMembership(userId: String, organizationId: String): Membership? = dbQuery {
        UserOrganizationsTable
            .select {
                (UserOrganizationsTable.userId eq userId) and
                    (UserOrganizationsTable.organizationId eq organizationId) and
                    (UserOrganizationsTable.status eq MembershipStatus.ACTIVE.name)
            }
            .singleOrNull()
            ?.toMembership()
    }

    /** The account's system-wide role. Fails closed to [GlobalRole.USER]. */
    suspend fun globalRole(userId: String): GlobalRole = dbQuery {
        UsersTable.select { UsersTable.id eq userId }
            .singleOrNull()
            ?.let { GlobalRole.parse(it[UsersTable.globalRole]) }
            ?: GlobalRole.USER
    }

    /** Every organization id the user is an active member of. */
    suspend fun activeOrganizationIds(userId: String): List<String> = dbQuery {
        UserOrganizationsTable
            .select {
                (UserOrganizationsTable.userId eq userId) and
                    (UserOrganizationsTable.status eq MembershipStatus.ACTIVE.name)
            }
            .map { it[UserOrganizationsTable.organizationId] }
    }

    // -----------------------------------------------------------------------
    // Listing
    // -----------------------------------------------------------------------

    /**
     * Everything the user has a row for, active or not.
     *
     * Pending and invited rows are included deliberately: a user who has asked
     * to join and sees nothing will simply ask again, and the unique index
     * would reject the duplicate with an error they cannot interpret.
     */
    suspend fun organizationsForUser(userId: String): List<MembershipWithOrg> = dbQuery {
        (UserOrganizationsTable innerJoin OrganizationsTable)
            .select { UserOrganizationsTable.userId eq userId }
            .orderBy(OrganizationsTable.name to SortOrder.ASC)
            .map {
                MembershipWithOrg(
                    organizationId = it[OrganizationsTable.id],
                    organizationName = it[OrganizationsTable.name],
                    organizationSlug = it[OrganizationsTable.slug],
                    role = OrgRole.parse(it[UserOrganizationsTable.role]),
                    status = MembershipStatus.parse(it[UserOrganizationsTable.status])
                        ?: MembershipStatus.PENDING
                )
            }
    }

    /** The organization's roster, for an admin. Includes pending and invited rows. */
    suspend fun membersOf(organizationId: String): List<MembershipWithUser> = dbQuery {
        // The join condition is spelled out because `user_organizations` has
        // *two* foreign keys into `users` — `user_id` and `invited_by`. Exposed
        // refuses to guess between them, and guessing wrong would list the
        // inviters instead of the members.
        UserOrganizationsTable
            .join(
                UsersTable,
                JoinType.INNER,
                onColumn = UserOrganizationsTable.userId,
                otherColumn = UsersTable.id
            )
            .select { UserOrganizationsTable.organizationId eq organizationId }
            .orderBy(UsersTable.fullName to SortOrder.ASC)
            .map {
                MembershipWithUser(
                    userId = it[UsersTable.id],
                    email = it[UsersTable.email],
                    fullName = it[UsersTable.fullName],
                    role = OrgRole.parse(it[UserOrganizationsTable.role]),
                    status = MembershipStatus.parse(it[UserOrganizationsTable.status])
                        ?: MembershipStatus.PENDING,
                    joinedAt = it[UserOrganizationsTable.createdAt].toString()
                )
            }
    }

    /** The raw row for one (user, organization) pair, whatever its status. */
    suspend fun membership(userId: String, organizationId: String): Membership? = dbQuery {
        UserOrganizationsTable
            .select {
                (UserOrganizationsTable.userId eq userId) and
                    (UserOrganizationsTable.organizationId eq organizationId)
            }
            .singleOrNull()
            ?.toMembership()
    }

    // -----------------------------------------------------------------------
    // Writes
    // -----------------------------------------------------------------------

    /** Inserts a membership row. Callers must have already checked authorization. */
    suspend fun upsert(
        userId: String,
        organizationId: String,
        role: OrgRole,
        status: MembershipStatus,
        invitedBy: String? = null
    ): Membership {
        val now = LocalDateTime.now()
        val existing = membership(userId, organizationId)

        if (existing != null) {
            dbQuery {
                UserOrganizationsTable.update({ UserOrganizationsTable.id eq existing.id }) {
                    it[UserOrganizationsTable.role] = role.name
                    it[UserOrganizationsTable.status] = status.name
                    it[UserOrganizationsTable.invitedBy] = invitedBy
                    it[updatedAt] = now
                }
            }
            return existing.copy(role = role, status = status)
        }

        val id = UUID.randomUUID().toString()
        dbQuery {
            UserOrganizationsTable.insert {
                it[UserOrganizationsTable.id] = id
                it[UserOrganizationsTable.userId] = userId
                it[UserOrganizationsTable.organizationId] = organizationId
                it[UserOrganizationsTable.role] = role.name
                it[UserOrganizationsTable.status] = status.name
                it[UserOrganizationsTable.invitedBy] = invitedBy
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        return Membership(id, userId, organizationId, role, status)
    }

    /** Promotes a `PENDING`/`INVITED` row to `ACTIVE`. Returns false if there was no such row. */
    suspend fun activate(userId: String, organizationId: String, role: OrgRole? = null): Boolean = dbQuery {
        UserOrganizationsTable.update({
            (UserOrganizationsTable.userId eq userId) and
                (UserOrganizationsTable.organizationId eq organizationId)
        }) {
            it[status] = MembershipStatus.ACTIVE.name
            if (role != null) it[UserOrganizationsTable.role] = role.name
            it[updatedAt] = LocalDateTime.now()
        } > 0
    }

    /** Changes an existing member's role. Returns false if they are not a member. */
    suspend fun changeRole(userId: String, organizationId: String, role: OrgRole): Boolean = dbQuery {
        UserOrganizationsTable.update({
            (UserOrganizationsTable.userId eq userId) and
                (UserOrganizationsTable.organizationId eq organizationId) and
                (UserOrganizationsTable.status eq MembershipStatus.ACTIVE.name)
        }) {
            it[UserOrganizationsTable.role] = role.name
            it[updatedAt] = LocalDateTime.now()
        } > 0
    }

    /**
     * Removes the user from the organization entirely.
     *
     * Deletes the row rather than marking it removed. Access is revoked on the
     * next request because every check re-reads this table — no token needs to
     * expire first. The organization's clients and visits are untouched: they
     * belong to the organization, not to whoever typed them in, which is the
     * whole reason `clients.organization_id` exists instead of a `user_id`.
     */
    suspend fun remove(userId: String, organizationId: String): Boolean = dbQuery {
        UserOrganizationsTable.deleteWhere {
            (UserOrganizationsTable.userId eq userId) and
                (UserOrganizationsTable.organizationId eq organizationId)
        } > 0
    }

    /**
     * How many active admins the organization has.
     *
     * Used to refuse the removal or demotion of the last one. An organization
     * with no admin cannot approve joins, invite anyone, or restore itself —
     * it is unrecoverable without database access, so the check is worth the
     * extra query.
     */
    suspend fun activeAdminCount(organizationId: String): Long = dbQuery {
        UserOrganizationsTable
            .select {
                (UserOrganizationsTable.organizationId eq organizationId) and
                    (UserOrganizationsTable.status eq MembershipStatus.ACTIVE.name) and
                    (UserOrganizationsTable.role eq OrgRole.ORG_ADMIN.name)
            }
            .count()
    }

    private fun ResultRow.toMembership() = Membership(
        id = this[UserOrganizationsTable.id],
        userId = this[UserOrganizationsTable.userId],
        organizationId = this[UserOrganizationsTable.organizationId],
        role = OrgRole.parse(this[UserOrganizationsTable.role]),
        status = MembershipStatus.parse(this[UserOrganizationsTable.status]) ?: MembershipStatus.PENDING
    )

    companion object {
        /**
         * Promotes the configured addresses to [GlobalRole.SUPER_ADMIN].
         *
         * Called once at startup. Only ever promotes — see the note on
         * `AppSettings.superAdminEmails` for why removing an address does not
         * demote the account.
         */
        suspend fun bootstrapSuperAdmins(emails: List<String>): Int {
            if (emails.isEmpty()) return 0
            var promoted = 0
            for (email in emails) {
                promoted += dbQuery {
                    UsersTable.update({
                        (UsersTable.email eq email) and
                            (UsersTable.globalRole neq GlobalRole.SUPER_ADMIN.name)
                    }) {
                        it[globalRole] = GlobalRole.SUPER_ADMIN.name
                    }
                }
            }
            return promoted
        }
    }
}
