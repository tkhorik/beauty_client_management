package com.beauty.auth

import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.RefreshTokensTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * The design in one line: access tokens are short-lived and stateless, refresh
 * tokens are long-lived and revocable, and using a refresh token consumes it.
 *
 * That last part is what makes theft survivable. If a token can be replayed
 * indefinitely, a copy taken from a stolen laptop is as good as the original
 * forever. With rotation, the legitimate client and the attacker are racing to
 * use the same one-shot credential — and whoever loses that race presents an
 * already-spent token, which is a signal no honest client can produce. That
 * signal revokes the whole family, logging both of them out and forcing a
 * password-backed login the attacker cannot complete.
 */
class RefreshTokenService(
    private val lifetimeDays: Long
) {
    private val log = LoggerFactory.getLogger(RefreshTokenService::class.java)
    private val random = SecureRandom()

    /** The outcome of presenting a refresh token. */
    sealed interface RotationResult {
        /** Token was valid; [token] is its replacement and must be sent to the client. */
        data class Rotated(val userId: String, val token: String) : RotationResult

        /** Token was unknown, expired, or already spent. The client must log in again. */
        object Rejected : RotationResult
    }

    /**
     * Creates a brand-new token family. Call this on login and registration —
     * anywhere a fresh session begins.
     */
    suspend fun issueNewFamily(userId: String): String =
        issue(userId, familyId = UUID.randomUUID().toString())

    /**
     * Exchanges a valid token for its successor, or rejects it.
     *
     * Deliberately returns the same [RotationResult.Rejected] for "never
     * existed", "expired" and "already used". The client can do nothing
     * different in each case, and distinguishing them out loud would tell an
     * attacker probing with guessed tokens whether they got close.
     */
    suspend fun rotate(rawToken: String): RotationResult {
        val hash = hash(rawToken)
        val now = LocalDateTime.now()

        val row = dbQuery {
            RefreshTokensTable.select { RefreshTokensTable.tokenHash eq hash }.singleOrNull()
        } ?: return RotationResult.Rejected

        val userId = row[RefreshTokensTable.userId]
        val familyId = row[RefreshTokensTable.familyId]

        // A spent token being presented again means one of two things: a stolen
        // copy is being replayed, or the real client's replacement never
        // arrived. Both warrant killing the family — the first because it is an
        // active attack, the second because we cannot tell it apart from one.
        if (row[RefreshTokensTable.revokedAt] != null) {
            log.warn(
                "Refresh token reuse detected for user {} (family {}). Revoking the entire family.",
                userId,
                familyId
            )
            revokeFamily(familyId)
            return RotationResult.Rejected
        }

        if (row[RefreshTokensTable.expiresAt].isBefore(now)) {
            return RotationResult.Rejected
        }

        // Spend the presented token before minting its replacement, so a crash
        // between the two leaves the user logged out rather than holding a
        // token that can be replayed.
        dbQuery {
            RefreshTokensTable.update({ RefreshTokensTable.id eq row[RefreshTokensTable.id] }) {
                it[revokedAt] = now
            }
        }

        return RotationResult.Rotated(userId, issue(userId, familyId))
    }

    /** Logout: revokes one token, leaving the user's other devices signed in. */
    suspend fun revoke(rawToken: String) {
        val hash = hash(rawToken)
        dbQuery {
            RefreshTokensTable.update(
                { RefreshTokensTable.tokenHash eq hash and RefreshTokensTable.revokedAt.isNull() }
            ) {
                it[revokedAt] = LocalDateTime.now()
            }
        }
    }

    /**
     * Revokes every session a user has. Used by "log out everywhere" and,
     * importantly, by password reset — a reset that leaves the attacker's
     * existing session alive has not actually locked them out.
     */
    suspend fun revokeAllForUser(userId: String) {
        dbQuery {
            RefreshTokensTable.update(
                { RefreshTokensTable.userId eq userId and RefreshTokensTable.revokedAt.isNull() }
            ) {
                it[revokedAt] = LocalDateTime.now()
            }
        }
    }

    private suspend fun revokeFamily(familyId: String) {
        dbQuery {
            RefreshTokensTable.update(
                { RefreshTokensTable.familyId eq familyId and RefreshTokensTable.revokedAt.isNull() }
            ) {
                it[revokedAt] = LocalDateTime.now()
            }
        }
    }

    /**
     * Drops rows that are long past their expiry. Without this the table grows
     * forever, one row per refresh, which for a 15-minute access token is
     * roughly 100 rows per user per day.
     *
     * Expired rows are kept for a grace period rather than deleted the moment
     * they lapse, so that reuse detection still recognises a recently spent
     * token instead of silently treating it as "never existed".
     */
    suspend fun purgeExpired() {
        val cutoff = LocalDateTime.now().minusDays(REUSE_DETECTION_GRACE_DAYS)
        val removed = dbQuery {
            RefreshTokensTable.deleteWhere { RefreshTokensTable.expiresAt less cutoff }
        }
        if (removed > 0) log.info("Purged {} expired refresh tokens.", removed)
    }

    private suspend fun issue(userId: String, familyId: String): String {
        val rawToken = generateToken()
        val now = LocalDateTime.now()

        dbQuery {
            RefreshTokensTable.insert {
                it[id] = UUID.randomUUID().toString()
                it[RefreshTokensTable.userId] = userId
                it[tokenHash] = hash(rawToken)
                it[RefreshTokensTable.familyId] = familyId
                it[issuedAt] = now
                it[expiresAt] = now.plusDays(lifetimeDays)
                it[revokedAt] = null
            }
        }
        return rawToken
    }

    /**
     * 256 bits from [SecureRandom]. Not [UUID.randomUUID], which yields 122
     * bits and is meant for uniqueness rather than unguessability.
     */
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
        private const val REUSE_DETECTION_GRACE_DAYS = 30L
    }
}
