package com.beauty.auth

import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.OneTimeTokensTable
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

/** What a one-time token entitles its holder to do. */
enum class TokenPurpose {
    EMAIL_VERIFICATION,
    PASSWORD_RESET
}

/**
 * Issues and redeems the single-use tokens sent by email.
 *
 * The security properties, all of which the routes depend on:
 *
 *  - **Unguessable.** 256 bits from [SecureRandom]. Not a UUID: `randomUUID`
 *    provides 122 bits and is designed for uniqueness, not unpredictability.
 *  - **Not stored.** Only a SHA-256 hash goes in the database, so a dump of
 *    `one_time_tokens` yields no usable reset links.
 *  - **Single-use.** Redemption is a conditional UPDATE, so two concurrent
 *    redemptions of the same token cannot both succeed.
 *  - **Expiring.** Short-lived by purpose; the window bounds how long a link
 *    sitting in an inbox stays dangerous.
 *  - **Silent about failure.** Unknown, expired and already-used all return the
 *    same [Redemption.Invalid]. Distinguishing them tells someone probing with
 *    guessed tokens whether they are getting close.
 */
class OneTimeTokenService {
    private val log = LoggerFactory.getLogger(OneTimeTokenService::class.java)
    private val random = SecureRandom()

    sealed interface Redemption {
        /** The token was valid and is now spent. */
        data class Redeemed(val userId: String) : Redemption

        /** Unknown, expired, already used, or the wrong purpose. Never says which. */
        object Invalid : Redemption
    }

    /**
     * Creates a token and returns the raw value to put in the email link.
     *
     * The raw value is returned and never persisted — after this call the
     * server itself cannot recover it, which is the point. Existing unused
     * tokens of the same purpose are invalidated first, so requesting a second
     * reset link silently retires the first: two live reset tokens means two
     * chances for one to leak, for no benefit to the user, who is only ever
     * going to click the newest mail.
     */
    suspend fun issue(userId: String, purpose: TokenPurpose, ttl: java.time.Duration): String {
        invalidateOutstanding(userId, purpose)

        val rawToken = generateToken()
        val now = LocalDateTime.now()

        dbQuery {
            OneTimeTokensTable.insert {
                it[id] = UUID.randomUUID().toString()
                it[OneTimeTokensTable.userId] = userId
                it[tokenHash] = hash(rawToken)
                it[OneTimeTokensTable.purpose] = purpose.name
                it[createdAt] = now
                it[expiresAt] = now.plus(ttl)
                it[usedAt] = null
            }
        }
        return rawToken
    }

    /**
     * Spends a token, if it is spendable.
     *
     * [purpose] is matched, not merely read. Without that check a verification
     * token — which is mailed on every signup, has a 24-hour life, and is
     * treated as low-value — could be presented to the password-reset endpoint
     * and would take over the account. Tokens must only be usable for the thing
     * they were issued for.
     */
    suspend fun redeem(rawToken: String, purpose: TokenPurpose): Redemption {
        if (rawToken.isBlank()) return Redemption.Invalid

        val tokenHash = hash(rawToken)
        val now = LocalDateTime.now()

        val row = dbQuery {
            OneTimeTokensTable.select { OneTimeTokensTable.tokenHash eq tokenHash }.singleOrNull()
        } ?: return Redemption.Invalid

        if (row[OneTimeTokensTable.purpose] != purpose.name) {
            log.warn(
                "One-time token issued for {} was presented to the {} endpoint. Rejecting.",
                row[OneTimeTokensTable.purpose],
                purpose.name
            )
            return Redemption.Invalid
        }
        if (row[OneTimeTokensTable.usedAt] != null) return Redemption.Invalid
        if (row[OneTimeTokensTable.expiresAt].isBefore(now)) return Redemption.Invalid

        // The `usedAt IS NULL` predicate is what actually enforces single use.
        // The check above is a fast path, but two requests carrying the same
        // token can both pass it — this UPDATE is the point at which exactly one
        // of them wins, because the second matches zero rows.
        val claimed = dbQuery {
            OneTimeTokensTable.update({
                (OneTimeTokensTable.id eq row[OneTimeTokensTable.id]) and
                    OneTimeTokensTable.usedAt.isNull()
            }) {
                it[usedAt] = now
            }
        }
        if (claimed == 0) return Redemption.Invalid

        return Redemption.Redeemed(row[OneTimeTokensTable.userId])
    }

    /**
     * Marks every unused token of one purpose for a user as spent.
     *
     * Called on issue (so only the newest link works) and after a successful
     * password reset (so any other reset link already in flight — including one
     * an attacker triggered — dies with it).
     */
    suspend fun invalidateOutstanding(userId: String, purpose: TokenPurpose) {
        dbQuery {
            OneTimeTokensTable.update({
                (OneTimeTokensTable.userId eq userId) and
                    (OneTimeTokensTable.purpose eq purpose.name) and
                    OneTimeTokensTable.usedAt.isNull()
            }) {
                it[usedAt] = LocalDateTime.now()
            }
        }
    }

    /**
     * Deletes long-expired rows so the table does not grow without bound.
     *
     * Unlike refresh tokens there is no reuse-detection grace period to
     * preserve: a redeemed or expired one-time token carries no signal worth
     * keeping, since replaying one is already indistinguishable from a typo.
     */
    suspend fun purgeExpired() {
        val cutoff = LocalDateTime.now().minusDays(PURGE_GRACE_DAYS)
        val removed = dbQuery {
            OneTimeTokensTable.deleteWhere { OneTimeTokensTable.expiresAt less cutoff }
        }
        if (removed > 0) log.info("Purged {} expired one-time tokens.", removed)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        // URL-safe and unpadded: the value goes straight into a query string,
        // and standard Base64's `+` and `/` would need escaping that mail
        // clients and copy-paste routinely mangle.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(rawToken: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(rawToken.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TOKEN_BYTES = 32
        private const val PURGE_GRACE_DAYS = 7L
    }
}
