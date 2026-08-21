package com.beauty.auth

import com.beauty.config.AppSettings
import java.time.LocalDateTime

/**
 * Decides whether an account is currently allowed to *use the application at
 * all*.
 *
 * Split out of the route layer and out of [MembershipService] on purpose: this
 * is the only place in the codebase that knows how the grace window is
 * computed, so the rule can be unit-tested against a fixed clock without
 * standing up a database or an HTTP call. Route code asks a yes/no question and
 * never does date arithmetic of its own — arithmetic scattered across a dozen
 * handlers is arithmetic that drifts.
 *
 * ## What "restricted" means, and why it changed
 *
 * This policy used to gate *writes* only: an unverified account kept full read
 * access on the reasoning that locking a salon out of a client's allergy
 * history is worse than tolerating an unconfirmed address. That reasoning was
 * sound for a soft rollout across an existing user base, and it is why the
 * grace window below still exists. It is **not** the rule any more: a
 * restricted account is refused every organization-scoped request, read or
 * write, and its client sends it to a screen whose only offer is to confirm
 * the address. An address nobody has proved they control should not be able to
 * page through another person's medical history either, and "you can look but
 * not touch" is a state neither client could explain to a user without
 * describing the implementation.
 *
 * The escape hatches are not exceptions carved into this class; they exist
 * because the routes that provide them — resend the mail, re-read your own
 * profile, change your password, sign out — resolve the caller with
 * `call.userId()` and never consult this policy at all. See `plugins/OrgAccess`.
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
     * The moment [account] loses access unless it verifies, or null when
     * enforcement is off or the account is already verified.
     *
     * Two cases, and the split between them is the whole design:
     *
     *  - **Registered at or after [enforcedFrom]** — the deadline *is*
     *    `createdAt`, already in the past by the time the first request lands.
     *    Verification is mandatory for a new signup with no window at all,
     *    which is the point: the account was created under a flow that told it,
     *    on screen and by mail, to confirm the address before continuing.
     *    Granting a week of unrestricted access first would mean the rule only
     *    applies to users who ignore it long enough to forget it exists.
     *
     *  - **Registered before [enforcedFrom]** — `enforcedFrom + graceDays`.
     *    This is load-bearing and must not be simplified into the case above.
     *    Anchoring an existing account on its own `createdAt` would put every
     *    account older than [graceDays] — that is, all of them — past its
     *    deadline the instant the feature is switched on, locking out the
     *    entire user base in one restart with no warning. Measuring from the
     *    switch-on date instead gives everyone who predates the rule a full
     *    window, during which the clients show a counting-down banner rather
     *    than a wall.
     *
     * Exposed to clients so they can count down honestly ("3 days left")
     * instead of nagging with an unspecified threat.
     */
    fun deadlineFor(account: AccountStatus): LocalDateTime? {
        val from = enforcedFrom ?: return null
        if (account.emailVerified) return null
        // `!isBefore` rather than `isAfter`: an account created in the same
        // instant enforcement begins is a new signup, not a legacy one.
        if (!account.createdAt.isBefore(from)) return account.createdAt
        return from.plusDays(graceDays)
    }

    /**
     * Whether [account] may use the application right now.
     *
     * Super admins are exempt. The role is granted only by `SUPER_ADMIN_EMAILS`
     * in the VPS-only `.env` or by a manual `UPDATE`, so it is already gated on
     * something stronger than an email round trip — and an operator locked out
     * of administering the system by an unread confirmation mail has no way
     * back in, since the routes that could fix it are the ones being blocked.
     */
    fun canAccess(account: AccountStatus, now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (account.globalRole == GlobalRole.SUPER_ADMIN) return true
        val deadline = deadlineFor(account) ?: return true
        return now.isBefore(deadline)
    }
}
