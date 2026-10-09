package com.beauty.auth

import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.UsersTable
import com.beauty.mail.AccountMailer
import org.jetbrains.exposed.sql.select

/**
 * What happens after a join request is newly filed, from either the
 * registration form or the post-sign-up join form: an audit entry, and a mail
 * to each of the organization's admins.
 *
 * Only called for [JoinResult.Filed] — re-asking while already pending must
 * not re-mail anyone, or the join endpoint becomes a way to flood an admin's
 * inbox.
 */
class JoinRequestNotifier(
    private val memberships: MembershipService,
    private val audit: OrgAuditService,
    private val mailer: AccountMailer
) {
    suspend fun requestFiled(org: OrgRef, requesterId: String) {
        audit.record(org.id, requesterId, OrgAuditService.Action.JOIN_REQUESTED, targetUserId = requesterId)

        val requester = dbQuery {
            UsersTable.select { UsersTable.id eq requesterId }.singleOrNull()
        } ?: return
        mailer.sendJoinRequestToAdmins(
            organizationId = org.id,
            organizationName = org.name,
            requesterName = requester[UsersTable.fullName],
            requesterEmail = requester[UsersTable.email],
            admins = memberships.activeAdmins(org.id)
        )
    }

    /** Tells the requester the outcome. The caller has already recorded the decision. */
    suspend fun decisionMade(org: OrgRef, requesterId: String, approved: Boolean) {
        val requester = dbQuery {
            UsersTable.select { UsersTable.id eq requesterId }.singleOrNull()
        } ?: return
        mailer.sendJoinDecision(
            email = requester[UsersTable.email],
            fullName = requester[UsersTable.fullName],
            languagePreference = requester[UsersTable.languagePreference],
            organizationName = org.name,
            approved = approved
        )
    }
}
