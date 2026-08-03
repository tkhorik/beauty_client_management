package com.beauty.auth

import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.OrganizationCreationTokensTable
import com.beauty.db.UsersTable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID

/** One organization-creation link, joined to the admin who issued it. */
data class OrganizationCreationToken(
    val id: String,
    val label: String?,
    val createdByEmail: String,
    val maxUses: Int,
    val usesCount: Int,
    val expiresAt: LocalDateTime,
    val revokedAt: LocalDateTime?,
    val createdAt: LocalDateTime
)

/**
 * Issues and redeems the tokens that gate `POST /api/organizations`.
 *
 * Modeled on [OneTimeTokenService]'s security properties — 256-bit
 * `SecureRandom`, SHA-256 hash storage only, a single generic response for
 * every kind of rejection — but a genuinely different lifecycle: these are
 * issued by a `SUPER_ADMIN` to whoever ends up holding the link rather than
 * to one specific user, and a link can be redeemed more than once. See the
 * doc comment on `db/Tables.kt`'s `OrganizationCreationTokensTable` for why
 * that rules out reusing `OneTimeTokensTable` with a new purpose.
 */
class OrgCreationTokenService {
    private val random = SecureRandom()

    sealed interface Redemption {
        object Redeemed : Redemption

        /** Unknown, expired, revoked, or already at its use limit. Never says which. */
        object Invalid : Redemption
    }

    /**
     * Creates a link and returns its id alongside the raw token.
     *
     * The raw value is returned once and never persisted — only its hash is
     * stored, so after this call even the server cannot recover it. The
     * caller (an admin route) is responsible for handing it to whoever should
     * have it.
     */
    suspend fun issue(
        createdBy: String,
        label: String?,
        maxUses: Int,
        expiresAt: LocalDateTime
    ): Pair<String, String> {
        require(maxUses >= 1) { "maxUses must be at least 1" }

        val id = UUID.randomUUID().toString()
        val rawToken = generateToken()

        dbQuery {
            OrganizationCreationTokensTable.insert {
                it[OrganizationCreationTokensTable.id] = id
                it[tokenHash] = hash(rawToken)
                it[OrganizationCreationTokensTable.label] = label?.trim()?.takeIf { l -> l.isNotEmpty() }
                it[OrganizationCreationTokensTable.createdBy] = createdBy
                it[OrganizationCreationTokensTable.maxUses] = maxUses
                it[usesCount] = 0
                it[OrganizationCreationTokensTable.expiresAt] = expiresAt
                it[revokedAt] = null
                it[createdAt] = LocalDateTime.now()
            }
        }

        return id to rawToken
    }

    /**
     * Spends one use of a token, if it has one to spend.
     *
     * The `UPDATE`'s `WHERE` clause is what actually enforces the cap, not a
     * preceding `SELECT`: it matches unrevoked, unexpired rows with
     * `uses_count < max_uses` as they stand at the moment the statement runs,
     * so N concurrent redemptions of an N-use link can each claim exactly one
     * slot and the (N+1)th matches zero rows — the same technique
     * [OneTimeTokenService.redeem] uses for `used_at IS NULL`, generalised
     * from a boolean to a counter.
     *
     * The increment itself is `uses_count = uses_count + 1` evaluated by the
     * database, not read-then-write from application code: two concurrent
     * winners each computing "the count I last read, plus one" would both
     * write the same resulting value and silently lose one of the two
     * increments, which is exactly the race this method exists to close.
     */
    suspend fun redeem(rawToken: String): Redemption {
        if (rawToken.isBlank()) return Redemption.Invalid

        val digest = hash(rawToken)
        val now = LocalDateTime.now()

        val claimed = dbQuery {
            OrganizationCreationTokensTable.update({
                (OrganizationCreationTokensTable.tokenHash eq digest) and
                    OrganizationCreationTokensTable.revokedAt.isNull() and
                    (OrganizationCreationTokensTable.expiresAt greater now) and
                    (OrganizationCreationTokensTable.usesCount less OrganizationCreationTokensTable.maxUses)
            }) {
                it[usesCount] = usesCount plus 1
            }
        }

        return if (claimed > 0) Redemption.Redeemed else Redemption.Invalid
    }

    /**
     * Read-only check for the UI's pre-flight validation — does not spend a
     * use. Purely advisory: the actual gate is [redeem], called from
     * `POST /api/organizations` itself, since a link valid at validation time
     * can still be exhausted, revoked, or expired a moment later.
     */
    suspend fun isRedeemable(rawToken: String): Boolean {
        if (rawToken.isBlank()) return false

        val digest = hash(rawToken)
        val now = LocalDateTime.now()

        return dbQuery {
            OrganizationCreationTokensTable.select {
                (OrganizationCreationTokensTable.tokenHash eq digest) and
                    OrganizationCreationTokensTable.revokedAt.isNull() and
                    (OrganizationCreationTokensTable.expiresAt greater now) and
                    (OrganizationCreationTokensTable.usesCount less OrganizationCreationTokensTable.maxUses)
            }.count() > 0
        }
    }

    /** Every link ever issued, newest first, joined to its issuer's email for display. */
    suspend fun listAll(): List<OrganizationCreationToken> = dbQuery {
        // The join condition is spelled out for the same reason
        // `MembershipService.membersOf` spells it out: an explicit onColumn
        // pair, not Exposed's implicit-FK join, so the intent survives even
        // if `organization_creation_tokens` grows a second FK into `users`.
        OrganizationCreationTokensTable
            .join(
                UsersTable,
                JoinType.INNER,
                onColumn = OrganizationCreationTokensTable.createdBy,
                otherColumn = UsersTable.id
            )
            .select { Op.TRUE }
            .orderBy(OrganizationCreationTokensTable.createdAt to SortOrder.DESC)
            .map { it.toToken() }
    }

    /**
     * One link by id, joined to its issuer's email.
     *
     * Used to build the response to `POST .../organization-creation-tokens`
     * from the row actually written, rather than reconstructing the DTO by
     * hand from the request — the same reasoning behind `userDto()` in
     * `AuthRoutes.kt`: a hand-built copy can drift from what was really
     * persisted (a `createdAt` a few milliseconds off, a value that failed to
     * round-trip), and a second construction site is a second place to forget
     * a field.
     */
    suspend fun getById(id: String): OrganizationCreationToken? = dbQuery {
        OrganizationCreationTokensTable
            .join(
                UsersTable,
                JoinType.INNER,
                onColumn = OrganizationCreationTokensTable.createdBy,
                otherColumn = UsersTable.id
            )
            .select { OrganizationCreationTokensTable.id eq id }
            .singleOrNull()
            ?.toToken()
    }

    /** Revokes a link before its natural expiry. Returns false if it did not exist or was already revoked. */
    suspend fun revoke(id: String): Boolean = dbQuery {
        OrganizationCreationTokensTable.update({
            (OrganizationCreationTokensTable.id eq id) and OrganizationCreationTokensTable.revokedAt.isNull()
        }) {
            it[revokedAt] = LocalDateTime.now()
        } > 0
    }

    private fun ResultRow.toToken() = OrganizationCreationToken(
        id = this[OrganizationCreationTokensTable.id],
        label = this[OrganizationCreationTokensTable.label],
        createdByEmail = this[UsersTable.email],
        maxUses = this[OrganizationCreationTokensTable.maxUses],
        usesCount = this[OrganizationCreationTokensTable.usesCount],
        expiresAt = this[OrganizationCreationTokensTable.expiresAt],
        revokedAt = this[OrganizationCreationTokensTable.revokedAt],
        createdAt = this[OrganizationCreationTokensTable.createdAt]
    )

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        // URL-safe and unpadded: this value goes straight into a query
        // string, same reasoning as OneTimeTokenService.generateToken.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(rawToken: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(rawToken.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TOKEN_BYTES = 32
    }
}
