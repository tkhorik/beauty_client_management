package com.beauty.auth

import com.beauty.config.AppSettings
import java.time.LocalDateTime

/**
 * Decides whether an account is currently allowed to *write*.
 *
 * Split out of the route layer and out of [MembershipService] on purpose: this
 * is the only place in the codebase that knows how the grace window is
 * computed, so the rule can be unit-tested against a fixed clock without
 * standing up a database or an HTTP call. Route code asks a yes/no question and
 * never does date arithmetic of its own — arithmetic scattered across a dozen
 * handlers is arithmetic that drifts.
 *
 * The policy is deliberately *only* about writes. An unverified user keeps full
 * read access to everything their membership already entitles them to. Locking
 * a salon out of looking up a client's allergy history because a confirmation
 * mail landed in spam would cause more harm than the unconfirmed address does.
 */
class VerificationPolicy(
    private val enforcedFrom: LocalDateTime?,
    private val graceDays: Long
) {
    constructor(settings: AppSettings) : this(
        enforcedFrom = settings.verificationEnforcedFrom,
        graceDays = settings.verificationGraceDays
    )

    /** True when enforcement is switched on at all. */
    val isEnforced: Boolean get() = enforcedFrom != null

    /**
     * The moment [account] stops being able to write unless it verifies, or
     * null when enforcement is off or the account is already verified.
     *
     * `max(createdAt, enforcedFrom)` is the whole trick, and the reason a
     * rollout does not lock out the existing user base. Anchoring on
     * `createdAt` alone would mean every account older than [graceDays] — that
     * is, all of them — is already past its deadline the instant the feature is
     * switched on. Taking the later of the two dates gives an existing account
     * a full window measured from the day enforcement starts, while a new
     * signup still gets its window measured from registration.
     *
     * Exposed to clients so they can count down honestly ("3 days left")
     * instead of nagging with an unspecified threat.
     */
    fun deadlineFor(account: AccountState): LocalDateTime? {
        val from = enforcedFrom ?: return null
        if (account.emailVerified) return null
        return maxOf(account.createdAt, from).plusDays(graceDays)
    }

    /**
     * Whether [account] may perform writes right now.
     *
     * Super admins are exempt. The role is granted only by `SUPER_ADMIN_EMAILS`
     * in the VPS-only `.env` or by a manual `UPDATE`, so it is already gated on
     * something stronger than an email round trip — and an operator locked out
     * of administering the system by an unread confirmation mail has no way
     * back in, since the routes that could fix it are the ones being blocked.
     */
    fun canWrite(account: AccountState, now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (account.globalRole == GlobalRole.SUPER_ADMIN) return true
        val deadline = deadlineFor(account) ?: return true
        return now.isBefore(deadline)
    }
}
