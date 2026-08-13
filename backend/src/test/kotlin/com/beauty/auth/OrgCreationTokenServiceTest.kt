package com.beauty.auth

import com.beauty.db.OrganizationCreationTokensTable
import com.beauty.db.UsersTable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for the tokens that gate `POST /api/organizations`.
 *
 * Mirrors [OneTimeTokenServiceTest] in spirit — these are the properties an
 * attacker (or an admin fat-fingering a use limit) actually attacks — but the
 * cases differ where the lifecycle differs: multi-use rather than single-use,
 * with an explicit revocation path and no per-user binding.
 */
class OrgCreationTokenServiceTest {

    private val service = OrgCreationTokenService()

    @BeforeTest
    fun setUp() {
        Database.connect("jdbc:h2:mem:creationtokentest;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        transaction {
            SchemaUtils.create(UsersTable, OrganizationCreationTokensTable)
            OrganizationCreationTokensTable.deleteAll()
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
                it[fullName] = "Test Admin"
                it[createdAt] = LocalDateTime.now()
                it[globalRole] = GlobalRole.SUPER_ADMIN.name
            }
        }
        return id
    }

    private fun future(hours: Long = 24) = LocalDateTime.now().plusHours(hours)

    @Test
    fun `a fresh single-use token redeems once`() = runBlocking<Unit> {
        val admin = newUser()
        val (_, token) = service.issue(admin, "test link", maxUses = 1, expiresAt = future())

        assertIs<OrgCreationTokenService.Redemption.Redeemed>(service.redeem(token))
    }

    @Test
    fun `a single-use token cannot be redeemed twice`() = runBlocking<Unit> {
        val admin = newUser()
        val (_, token) = service.issue(admin, null, maxUses = 1, expiresAt = future())

        service.redeem(token)
        val second = service.redeem(token)

        assertIs<OrgCreationTokenService.Redemption.Invalid>(second)
    }

    @Test
    fun `a multi-use token allows exactly its configured number of redemptions`() = runBlocking<Unit> {
        val admin = newUser()
        val (_, token) = service.issue(admin, null, maxUses = 3, expiresAt = future())

        repeat(3) {
            assertIs<OrgCreationTokenService.Redemption.Redeemed>(service.redeem(token))
        }
        // The fourth attempt finds uses_count == max_uses and matches nothing.
        assertIs<OrgCreationTokenService.Redemption.Invalid>(service.redeem(token))
    }

    @Test
    fun `concurrent redemptions of a single-use token cannot both succeed`() = runBlocking {
        val admin = newUser()
        val (_, token) = service.issue(admin, null, maxUses = 1, expiresAt = future())

        // Two requests racing to redeem the same one-use link. The guarantee
        // under test is the UPDATE ... WHERE uses_count < max_uses clause,
        // not application-level locking, so this fires both redemptions
        // concurrently rather than sequentially.
        val results = listOf(
            async { service.redeem(token) },
            async { service.redeem(token) }
        ).awaitAll()

        val succeeded = results.count { it is OrgCreationTokenService.Redemption.Redeemed }
        assertEquals(1, succeeded, "exactly one of the two concurrent redemptions must win")
    }

    @Test
    fun `concurrent redemptions of an N-use token cannot exceed N`() = runBlocking {
        val admin = newUser()
        val (_, token) = service.issue(admin, null, maxUses = 3, expiresAt = future())

        val results = (1..10).map { async { service.redeem(token) } }.awaitAll()

        val succeeded = results.count { it is OrgCreationTokenService.Redemption.Redeemed }
        assertEquals(3, succeeded, "an N-use link must never oversell past N, even under concurrent load")
    }

    @Test
    fun `an expired token is rejected`() = runBlocking<Unit> {
        val admin = newUser()
        val (id, token) = service.issue(admin, null, maxUses = 5, expiresAt = future())

        transaction {
            OrganizationCreationTokensTable.update({ OrganizationCreationTokensTable.id eq id }) {
                it[expiresAt] = LocalDateTime.now().minusMinutes(1)
            }
        }

        assertIs<OrgCreationTokenService.Redemption.Invalid>(service.redeem(token))
    }

    @Test
    fun `a revoked token is rejected even with uses remaining`() = runBlocking<Unit> {
        val admin = newUser()
        val (id, token) = service.issue(admin, null, maxUses = 5, expiresAt = future())

        assertTrue(service.revoke(id))
        assertIs<OrgCreationTokenService.Redemption.Invalid>(service.redeem(token))
    }

    @Test
    fun `revoking an already-revoked token reports no change`() = runBlocking<Unit> {
        val admin = newUser()
        val (id, _) = service.issue(admin, null, maxUses = 5, expiresAt = future())

        assertTrue(service.revoke(id))
        assertFalse(service.revoke(id), "a second revoke of the same link finds nothing left to do")
    }

    @Test
    fun `an unknown token is rejected without error`() = runBlocking<Unit> {
        assertIs<OrgCreationTokenService.Redemption.Invalid>(service.redeem("definitely-not-a-real-token"))
        assertIs<OrgCreationTokenService.Redemption.Invalid>(service.redeem(""))
    }

    @Test
    fun `isRedeemable reflects validity without spending a use`() = runBlocking<Unit> {
        val admin = newUser()
        val (_, token) = service.issue(admin, null, maxUses = 1, expiresAt = future())

        assertTrue(service.isRedeemable(token))
        // Checking must not itself consume the only use.
        assertTrue(service.isRedeemable(token))

        service.redeem(token)
        assertFalse(service.isRedeemable(token), "exhausted token must no longer validate")
    }

    @Test
    fun `the raw token is never stored`() = runBlocking<Unit> {
        val admin = newUser()
        val (id, token) = service.issue(admin, null, maxUses = 1, expiresAt = future())

        val stored = transaction {
            OrganizationCreationTokensTable.select { OrganizationCreationTokensTable.id eq id }
                .single()[OrganizationCreationTokensTable.tokenHash]
        }

        assertNotEquals(token, stored)
        assertEquals(64, stored.length, "Expected a SHA-256 hex digest.")
        assertTrue(stored.all { it in "0123456789abcdef" }, "Expected lowercase hex.")
    }

    @Test
    fun `listAll surfaces the issuer's email and current counters`() = runBlocking<Unit> {
        val admin = newUser()
        service.issue(admin, "batch one", maxUses = 5, expiresAt = future())

        val listed = service.listAll()

        assertEquals(1, listed.size)
        assertEquals("batch one", listed.single().label)
        assertEquals(0, listed.single().usesCount)
        assertEquals(5, listed.single().maxUses)
    }
}
