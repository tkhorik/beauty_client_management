package com.beauty.auth

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the grace-window arithmetic.
 *
 * Separate from the route-level tests in
 * `routes/EmailVerificationEnforcementTest.kt` because the interesting cases
 * here are about *dates*, and driving them through HTTP would mean either
 * waiting a week or faking a clock across the whole application. Here the clock
 * is just an argument.
 *
 * The case that matters most is [`an existing account gets its window from the
 * enforcement date, not its creation date`] — it pins the one property that
 * stops a rollout locking out the entire user base.
 */
class VerificationPolicyTest {

    private val enforcedFrom = LocalDateTime.of(2026, 9, 1, 12, 0)

    private fun account(
        createdAt: LocalDateTime,
        verifiedAt: LocalDateTime? = null,
        role: GlobalRole = GlobalRole.USER
    ) = AccountStatus(
        globalRole = role,
        // Suspension is a separate gate with its own tests in AdminRoutesTest;
        // this policy is only ever asked about verification.
        suspendedAt = null,
        emailVerifiedAt = verifiedAt,
        createdAt = createdAt
    )

    @Test
    fun `enforcement off means everyone can always write`() {
        val policy = VerificationPolicy(enforcedFrom = null, graceDays = 0)
        val ancient = account(createdAt = LocalDateTime.of(2020, 1, 1, 0, 0))

        assertFalse(policy.isEnforced)
        assertNull(policy.deadlineFor(ancient), "no deadline should be advertised when enforcement is off")
        assertTrue(policy.canWrite(ancient, now = LocalDateTime.of(2030, 1, 1, 0, 0)))
    }

    @Test
    fun `a verified account has no deadline and is never restricted`() {
        val policy = VerificationPolicy(enforcedFrom, graceDays = 7)
        val verified = account(
            createdAt = LocalDateTime.of(2026, 1, 1, 0, 0),
            verifiedAt = LocalDateTime.of(2026, 1, 2, 0, 0)
        )

        assertNull(policy.deadlineFor(verified))
        assertTrue(policy.canWrite(verified, now = enforcedFrom.plusYears(1)))
    }

    @Test
    fun `an existing account gets its window from the enforcement date, not its creation date`() {
        // The regression this whole design exists to prevent: an account
        // created long before enforcement began must not be past its deadline
        // the moment the feature is switched on.
        val policy = VerificationPolicy(enforcedFrom, graceDays = 7)
        val oldAccount = account(createdAt = LocalDateTime.of(2024, 3, 15, 9, 0))

        assertEquals(enforcedFrom.plusDays(7), policy.deadlineFor(oldAccount))
        assertTrue(
            policy.canWrite(oldAccount, now = enforcedFrom.plusDays(1)),
            "a five-year-old account must still get a full grace window"
        )
        assertFalse(policy.canWrite(oldAccount, now = enforcedFrom.plusDays(8)))
    }

    @Test
    fun `a new signup gets its window from registration`() {
        val policy = VerificationPolicy(enforcedFrom, graceDays = 7)
        val newAccount = account(createdAt = enforcedFrom.plusDays(30))

        assertEquals(enforcedFrom.plusDays(37), policy.deadlineFor(newAccount))
        assertTrue(policy.canWrite(newAccount, now = enforcedFrom.plusDays(36)))
        assertFalse(policy.canWrite(newAccount, now = enforcedFrom.plusDays(38)))
    }

    @Test
    fun `zero grace days restricts immediately`() {
        val policy = VerificationPolicy(enforcedFrom, graceDays = 0)
        val account = account(createdAt = enforcedFrom.minusDays(1))

        assertFalse(policy.canWrite(account, now = enforcedFrom.plusSeconds(1)))
    }

    @Test
    fun `a super admin is exempt`() {
        // Otherwise an operator whose confirmation mail bounced is locked out
        // of the routes they would need to fix it.
        val policy = VerificationPolicy(enforcedFrom, graceDays = 0)
        val admin = account(createdAt = enforcedFrom.minusYears(1), role = GlobalRole.SUPER_ADMIN)

        assertTrue(policy.canWrite(admin, now = enforcedFrom.plusYears(1)))
    }
}
