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
 * Two cases carry the design, and they pull in opposite directions:
 *  - [`a new signup is restricted immediately, with no grace window`] is the
 *    requirement — verification is mandatory, not eventually mandatory.
 *  - [`an existing account gets its window from the enforcement date, not its
 *    creation date`] is the safety property that stops the switch-on from
 *    locking out the entire user base at once.
 *
 * A change that makes one of them pass by breaking the other has missed the
 * point of the split.
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
    fun `enforcement off means everyone always has access`() {
        val policy = VerificationPolicy(enforcedFrom = null, graceDays = 0)
        val ancient = account(createdAt = LocalDateTime.of(2020, 1, 1, 0, 0))

        assertFalse(policy.isEnforced)
        assertNull(policy.deadlineFor(ancient), "no deadline should be advertised when enforcement is off")
        assertTrue(policy.canAccess(ancient, now = LocalDateTime.of(2030, 1, 1, 0, 0)))
    }

    @Test
    fun `a verified account has no deadline and is never restricted`() {
        val policy = VerificationPolicy(enforcedFrom, graceDays = 7)
        val verified = account(
            createdAt = LocalDateTime.of(2026, 1, 1, 0, 0),
            verifiedAt = LocalDateTime.of(2026, 1, 2, 0, 0)
        )

        assertNull(policy.deadlineFor(verified))
        assertTrue(policy.canAccess(verified, now = enforcedFrom.plusYears(1)))
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
            policy.canAccess(oldAccount, now = enforcedFrom.plusDays(1)),
            "a five-year-old account must still get a full grace window"
        )
        assertFalse(policy.canAccess(oldAccount, now = enforcedFrom.plusDays(8)))
    }

    @Test
    fun `a new signup is restricted immediately, with no grace window`() {
        // Verification is mandatory for anyone who registers under the rule.
        // The grace window is a migration aid for accounts that predate it,
        // not an allowance every new user inherits.
        val policy = VerificationPolicy(enforcedFrom, graceDays = 7)
        val registeredAt = enforcedFrom.plusDays(30)
        val newAccount = account(createdAt = registeredAt)

        assertEquals(registeredAt, policy.deadlineFor(newAccount))
        assertFalse(
            policy.canAccess(newAccount, now = registeredAt.plusSeconds(1)),
            "a brand-new unverified account must be restricted on its very first request"
        )
        assertFalse(policy.canAccess(newAccount, now = registeredAt.plusDays(3)))
    }

    @Test
    fun `an account created in the same instant enforcement begins counts as new`() {
        // The boundary between "legacy account, give it a window" and "new
        // signup, no window" is an inclusive one on the new-signup side. An
        // account registered at exactly the switch-on moment was created under
        // the new rule.
        val policy = VerificationPolicy(enforcedFrom, graceDays = 7)
        val borderline = account(createdAt = enforcedFrom)

        assertEquals(enforcedFrom, policy.deadlineFor(borderline))
        assertFalse(policy.canAccess(borderline, now = enforcedFrom.plusSeconds(1)))
    }

    @Test
    fun `a legacy account registered one second before the cutover still gets its window`() {
        // The mirror of the case above, and the one that would hurt: an
        // off-by-one on the comparison would restrict accounts that predate
        // enforcement without warning.
        val policy = VerificationPolicy(enforcedFrom, graceDays = 7)
        val legacy = account(createdAt = enforcedFrom.minusSeconds(1))

        assertEquals(enforcedFrom.plusDays(7), policy.deadlineFor(legacy))
        assertTrue(policy.canAccess(legacy, now = enforcedFrom.plusDays(6)))
    }

    @Test
    fun `zero grace days restricts existing accounts immediately too`() {
        val policy = VerificationPolicy(enforcedFrom, graceDays = 0)
        val account = account(createdAt = enforcedFrom.minusDays(1))

        assertFalse(policy.canAccess(account, now = enforcedFrom.plusSeconds(1)))
    }

    @Test
    fun `a super admin is exempt`() {
        // Otherwise an operator whose confirmation mail bounced is locked out
        // of the routes they would need to fix it.
        val policy = VerificationPolicy(enforcedFrom, graceDays = 0)
        val admin = account(createdAt = enforcedFrom.minusYears(1), role = GlobalRole.SUPER_ADMIN)

        assertTrue(policy.canAccess(admin, now = enforcedFrom.plusYears(1)))
    }

    @Test
    fun `a super admin who registers after the cutover is exempt as well`() {
        // The exemption is about the role, not about when the account was
        // made. A bootstrapped super admin created after enforcement begins
        // would otherwise be restricted from its first request, with no route
        // left that could lift the restriction.
        val policy = VerificationPolicy(enforcedFrom, graceDays = 7)
        val admin = account(createdAt = enforcedFrom.plusDays(10), role = GlobalRole.SUPER_ADMIN)

        assertTrue(policy.canAccess(admin, now = enforcedFrom.plusDays(11)))
    }
}
