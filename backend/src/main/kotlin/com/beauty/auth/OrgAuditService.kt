package com.beauty.auth

import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.OrganizationAuditTable
import com.beauty.db.UsersTable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import java.time.LocalDateTime
import java.util.UUID

/** One audit entry, with the names resolved for display. */
data class OrgAuditEvent(
    val id: String,
    val action: OrgAuditService.Action,
    val actorUserId: String,
    /** Null once the actor's account no longer exists. */
    val actorName: String?,
    val targetUserId: String?,
    val targetName: String?,
    val detail: String?,
    val createdAt: LocalDateTime
)

/**
 * Append-only record of membership decisions in an organization.
 *
 * Writes are best-effort in one direction only: they happen after the change
 * they describe has committed, in their own transaction. A failed audit write
 * therefore surfaces as a 500 on a change that did take effect — preferable to
 * the reverse, a change silently left out of the trail.
 */
class OrgAuditService {

    enum class Action {
        ORG_CREATED,
        JOIN_REQUESTED,
        APPROVED,
        DECLINED,
        INVITED,
        INVITATION_ACCEPTED,
        ROLE_CHANGED,
        REMOVED,
        REVOKED,
        RESTORED;

        companion object {
            fun parse(raw: String): Action? = entries.firstOrNull { it.name == raw }
        }
    }

    suspend fun record(
        organizationId: String,
        actorUserId: String,
        action: Action,
        targetUserId: String? = null,
        detail: String? = null
    ) {
        dbQuery {
            OrganizationAuditTable.insert {
                it[id] = UUID.randomUUID().toString()
                it[OrganizationAuditTable.organizationId] = organizationId
                it[OrganizationAuditTable.actorUserId] = actorUserId
                it[OrganizationAuditTable.targetUserId] = targetUserId
                it[OrganizationAuditTable.action] = action.name
                it[OrganizationAuditTable.detail] = detail?.take(255)
                it[createdAt] = LocalDateTime.now()
            }
        }
    }

    /**
     * Newest first. [before] is the `createdAt` of the last event the caller
     * already has, for paging; ties at the same instant are rare enough at
     * this write rate that a timestamp cursor is adequate.
     */
    suspend fun eventsFor(organizationId: String, limit: Int, before: LocalDateTime?): List<OrgAuditEvent> = dbQuery {
        val actor = UsersTable.alias("actor")
        val target = UsersTable.alias("target")
        OrganizationAuditTable
            .join(actor, JoinType.LEFT, OrganizationAuditTable.actorUserId, actor[UsersTable.id])
            .join(target, JoinType.LEFT, OrganizationAuditTable.targetUserId, target[UsersTable.id])
            .select {
                val scoped = OrganizationAuditTable.organizationId eq organizationId
                if (before == null) scoped else scoped and (OrganizationAuditTable.createdAt less before)
            }
            .orderBy(OrganizationAuditTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .mapNotNull { row ->
                // An action name this build does not know (written by a newer
                // version during a rollback) is skipped rather than failing
                // the whole page.
                val action = Action.parse(row[OrganizationAuditTable.action]) ?: return@mapNotNull null
                OrgAuditEvent(
                    id = row[OrganizationAuditTable.id],
                    action = action,
                    actorUserId = row[OrganizationAuditTable.actorUserId],
                    actorName = row.getOrNull(actor[UsersTable.fullName]),
                    targetUserId = row[OrganizationAuditTable.targetUserId],
                    targetName = row.getOrNull(target[UsersTable.fullName]),
                    detail = row[OrganizationAuditTable.detail],
                    createdAt = row[OrganizationAuditTable.createdAt]
                )
            }
    }
}
