package com.beauty.routes

import com.beauty.auth.GlobalRole
import com.beauty.auth.MembershipService
import com.beauty.auth.RefreshTokenService
import com.beauty.auth.RefreshTokenService.RotationResult
import com.beauty.db.DatabaseFactory
import com.beauty.db.DatabaseFactory.dbQuery
import com.beauty.db.RefreshTokensTable
import com.beauty.db.UsersTable
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Database-backed regressions for privilege bootstrap and refresh-token compromise. */
class SessionSecurityTest {
    private val service = RefreshTokenService(30)

    @BeforeTest
    fun setUp() {
        DatabaseFactory.init(MapApplicationConfig(
            "app.environment" to "development",
            "app.uploadDir" to "build/test-uploads",
            "db.driver" to "org.h2.Driver",
            "db.url" to "jdbc:h2:mem:session-security-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "db.user" to "sa",
            "db.password" to "",
            "db.maxPoolSize" to "3",
            "db.allowH2Fallback" to "false"
        ))
    }

    private suspend fun newUser(verified: Boolean = false): String {
        val id = UUID.randomUUID().toString()
        dbQuery {
            UsersTable.insert {
                it[UsersTable.id] = id
                it[email] = "$id@example.test"
                it[passwordHash] = "not-a-real-hash"
                it[fullName] = "Test User"
                it[createdAt] = LocalDateTime.now()
                it[emailVerifiedAt] = if (verified) LocalDateTime.now() else null
            }
        }
        return id
    }

    @Test
    fun `bootstrap requires mailbox verification and remains promote only`(): Unit = runBlocking {
        val unverified = newUser()
        val verified = newUser(verified = true)
        val addresses = listOf("$unverified@example.test", "$verified@example.test", "missing@example.test")

        assertEquals(1, MembershipService.bootstrapSuperAdmins(addresses))
        assertEquals(GlobalRole.USER, MembershipService().accountStatus(unverified).globalRole)
        assertEquals(GlobalRole.SUPER_ADMIN, MembershipService().accountStatus(verified).globalRole)
        assertEquals(0, MembershipService.bootstrapSuperAdmins(addresses))
        assertEquals(0, MembershipService.bootstrapSuperAdmins(emptyList()))
        assertEquals(GlobalRole.SUPER_ADMIN, MembershipService().accountStatus(verified).globalRole)

        dbQuery {
            UsersTable.update({ UsersTable.id eq unverified }) { it[emailVerifiedAt] = LocalDateTime.now() }
        }
        assertEquals(1, MembershipService.bootstrapSuperAdmins(addresses))
        assertEquals(GlobalRole.SUPER_ADMIN, MembershipService().accountStatus(unverified).globalRole)
    }

    @Test
    fun `immediate spent token replay revokes attacker successor but not another device`(): Unit = runBlocking {
        val user = newUser()
        val stolen = service.issueNewFamily(user)
        val otherDevice = service.issueNewFamily(user)
        val attacker = assertIs<RotationResult.Rotated>(service.rotate(stolen))
        val descendant = assertIs<RotationResult.Rotated>(service.rotate(attacker.token))

        assertIs<RotationResult.Rejected>(service.rotate(stolen))
        // New service instances see durable revocation, without an in-memory cache.
        assertIs<RotationResult.Rejected>(RefreshTokenService(30).rotate(descendant.token))
        assertIs<RotationResult.Rotated>(service.rotate(otherDevice))
    }

    @Test
    fun `rotation after account wide revocation cannot create a successor`(): Unit = runBlocking {
        val user = newUser()
        val first = service.issueNewFamily(user)
        val second = service.issueNewFamily(user)
        service.revokeAllForUser(user)

        assertIs<RotationResult.Rejected>(service.rotate(first))
        assertIs<RotationResult.Rejected>(service.rotate(second))
        // A later password-backed login may intentionally establish a new session.
        assertIs<RotationResult.Rotated>(service.rotate(service.issueNewFamily(user)))
    }

    @Test
    fun `logout with a recently rotated token revokes the session successor`(): Unit = runBlocking {
        val token = service.issueNewFamily(newUser())
        val successor = assertIs<RotationResult.Rotated>(service.rotate(token))
        service.revoke(token)
        assertIs<RotationResult.Rejected>(service.rotate(successor.token))
    }

    @Test
    fun `unknown and expired tokens are rejected and bearer credentials are not stored`(): Unit = runBlocking {
        assertIs<RotationResult.Rejected>(service.rotate("unknown-token"))
        val user = newUser()
        val token = service.issueNewFamily(user)
        dbQuery {
            val row = RefreshTokensTable.select { RefreshTokensTable.userId eq user }.single()
            val stored = row[RefreshTokensTable.tokenHash]
            assertNotEquals(token, stored)
            assertEquals(64, stored.length)
            assertTrue(stored.all { it in "0123456789abcdef" })
            RefreshTokensTable.update({ RefreshTokensTable.userId eq user }) {
                it[expiresAt] = LocalDateTime.now().minusMinutes(1)
            }
        }
        assertIs<RotationResult.Rejected>(service.rotate(token))
    }
}
