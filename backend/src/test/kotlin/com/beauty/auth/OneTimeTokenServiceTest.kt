package com.beauty.auth

import com.beauty.db.OneTimeTokensTable
import com.beauty.db.UsersTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for the token half of email verification and password reset.
 *
 * These are the properties an attacker attacks, so each one gets a test that
 * fails loudly if a future refactor drops it: single use, expiry, purpose
 * binding, and the fact that the raw token never reaches the database.
 *
 * Runs against in-memory H2 rather than a mock, because the single-use
 * guarantee is enforced by a conditional SQL UPDATE — mocking the database
 * would test everything except the mechanism that actually matters.
 */
class OneTimeTokenServiceTest {

    private val service = OneTimeTokenService()

    @BeforeTest
    fun setUp() {
        Database.connect("jdbc:h2:mem:tokentest;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        transaction {
            SchemaUtils.create(UsersTable, OneTimeTokensTable)
            // Start from a clean slate: H2's DB_CLOSE_DELAY=-1 keeps the
            // database alive between tests in the same JVM, so rows would
            // otherwise leak from one test into the next.
            OneTimeTokensTable.deleteAll()
            UsersTable.deleteAll()
        }
    }

    private fun newUser(): String {
        val id = UUID.randomUUID().toString()
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = id
                it[email] = "$id@example.test"
                it[passwordHash] = "not-a-real-hash"
                it[fullName] = "Test User"
                it[createdAt] = LocalDateTime.now()
                it[emailVerifiedAt] = null
            }
        }
        return id
    }

    @Test
    fun `a fresh token redeems once and returns its user`() = runBlocking<Unit> {
        val userId = newUser()
        val token = service.issue(userId, TokenPurpose.PASSWORD_RESET, Duration.ofMinutes(60))

        val result = service.redeem(token, TokenPurpose.PASSWORD_RESET)

        assertIs<OneTimeTokenService.Redemption.Redeemed>(result)
        assertEquals(userId, result.userId)
    }

    @Test
    fun `a redeemed token cannot be redeemed again`() = runBlocking<Unit> {
        val userId = newUser()
        val token = service.issue(userId, TokenPurpose.PASSWORD_RESET, Duration.ofMinutes(60))

        service.redeem(token, TokenPurpose.PASSWORD_RESET)
        val second = service.redeem(token, TokenPurpose.PASSWORD_RESET)

        // The whole point of single use. A reset link that still works after
        // the password has been changed lets anyone who saw the email once
        // take the account again at any time before expiry.
        assertIs<OneTimeTokenService.Redemption.Invalid>(second)
    }

    @Test
    fun `an expired token is rejected`() = runBlocking<Unit> {
        val userId = newUser()
        val token = service.issue(userId, TokenPurpose.PASSWORD_RESET, Duration.ofMinutes(60))

        // Reach into the row rather than sleeping: the expiry window is an hour.
        transaction {
            OneTimeTokensTable.update({ OneTimeTokensTable.userId eq userId }) {
                it[expiresAt] = LocalDateTime.now().minusMinutes(1)
            }
        }

        assertIs<OneTimeTokenService.Redemption.Invalid>(
            service.redeem(token, TokenPurpose.PASSWORD_RESET)
        )
    }

    @Test
    fun `a verification token cannot be used to reset a password`() = runBlocking<Unit> {
        val userId = newUser()
        val token = service.issue(userId, TokenPurpose.EMAIL_VERIFICATION, Duration.ofHours(24))

        // Privilege escalation if this ever passes: verification tokens are
        // mailed on every signup and live for a day, so accepting one at the
        // reset endpoint would turn a low-value token into account takeover.
        assertIs<OneTimeTokenService.Redemption.Invalid>(
            service.redeem(token, TokenPurpose.PASSWORD_RESET)
        )

        // ...and it must still work for what it was actually issued for. A
        // rejected cross-purpose attempt must not consume the token.
        assertIs<OneTimeTokenService.Redemption.Redeemed>(
            service.redeem(token, TokenPurpose.EMAIL_VERIFICATION)
        )
    }

    @Test
    fun `issuing a second token retires the first`() = runBlocking<Unit> {
        val userId = newUser()
        val first = service.issue(userId, TokenPurpose.PASSWORD_RESET, Duration.ofMinutes(60))
        val second = service.issue(userId, TokenPurpose.PASSWORD_RESET, Duration.ofMinutes(60))

        assertNotEquals(first, second)
        assertIs<OneTimeTokenService.Redemption.Invalid>(
            service.redeem(first, TokenPurpose.PASSWORD_RESET)
        )
        assertIs<OneTimeTokenService.Redemption.Redeemed>(
            service.redeem(second, TokenPurpose.PASSWORD_RESET)
        )
    }

    @Test
    fun `the raw token is never stored`() = runBlocking<Unit> {
        val userId = newUser()
        val token = service.issue(userId, TokenPurpose.PASSWORD_RESET, Duration.ofMinutes(60))

        val stored = transaction {
            OneTimeTokensTable.select { OneTimeTokensTable.userId eq userId }
                .single()[OneTimeTokensTable.tokenHash]
        }

        // A database dump must not yield working reset links.
        assertNotEquals(token, stored)
        assertEquals(64, stored.length, "Expected a SHA-256 hex digest.")
        assertTrue(stored.all { it in "0123456789abcdef" }, "Expected lowercase hex.")
    }

    @Test
    fun `an unknown token is rejected without error`() = runBlocking<Unit> {
        assertIs<OneTimeTokenService.Redemption.Invalid>(
            service.redeem("definitely-not-a-real-token", TokenPurpose.PASSWORD_RESET)
        )
        assertIs<OneTimeTokenService.Redemption.Invalid>(
            service.redeem("", TokenPurpose.PASSWORD_RESET)
        )
    }
}
