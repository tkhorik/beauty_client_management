package com.beauty.app.ui.verification

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unit tests for the client-side classification that decides between the
 * banner and the wall.
 *
 * Plain JVM tests with no Compose involved, because the decision is a pure
 * function of two fields and the interesting cases are all about parsing and
 * boundaries. Driving them through the UI would need an instrumented run for no
 * additional coverage.
 *
 * The backend is the authority on whether an account is actually restricted —
 * these tests pin which *explanation* the app shows, not what it is allowed to
 * do. Every case where the two could disagree resolves the same way: the
 * server refuses, independently of anything here.
 */
class VerificationStateTest {

    private fun isoOffsetByDays(days: Long): String {
        val millis = System.currentTimeMillis() + days * 86_400_000L
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(millis))
    }

    @Test
    fun `a verified account has nothing to show`() {
        assertEquals(
            VerificationStanding.VERIFIED,
            standingFor(emailVerified = true, deadline = null)
        )
        assertEquals(
            VerificationStanding.VERIFIED,
            standingFor(emailVerified = true, deadline = isoOffsetByDays(3))
        )
    }

    @Test
    fun `no deadline means enforcement is off, so the app stays quiet`() {
        // The client must not invent a restriction the server does not apply.
        // An older backend, or a deployment with the kill switch unset, sends
        // no deadline at all.
        assertEquals(
            VerificationStanding.VERIFIED,
            standingFor(emailVerified = false, deadline = null)
        )
    }

    @Test
    fun `a future deadline is a warning, not a wall`() {
        assertEquals(
            VerificationStanding.WARNING,
            standingFor(emailVerified = false, deadline = isoOffsetByDays(3))
        )
    }

    @Test
    fun `a deadline in the past is a wall`() {
        // The state a new signup is in from its very first request: the server
        // sets the deadline to the registration time, which is already behind
        // us by the time the profile is read.
        assertEquals(
            VerificationStanding.RESTRICTED,
            standingFor(emailVerified = false, deadline = isoOffsetByDays(-1))
        )
    }

    @Test
    fun `an unparseable deadline is treated as a wall`() {
        // Reading a value we cannot parse as "plenty of time" would show a
        // countdown over an app the server is already refusing. Failing to the
        // strict side keeps the explanation honest.
        assertEquals(
            VerificationStanding.RESTRICTED,
            standingFor(emailVerified = false, deadline = "not-a-timestamp")
        )
        assertEquals(0L, daysUntil("2026-13-45T99:99:99"))
    }

    @Test
    fun `fractional seconds in the timestamp are tolerated`() {
        // Ktor's LocalDateTime serialisation includes them whenever the value
        // has them, so a parser that choked would wall users at random.
        val withFraction = isoOffsetByDays(5) + ".123456"
        assertEquals(
            VerificationStanding.WARNING,
            standingFor(emailVerified = false, deadline = withFraction)
        )
    }

    @Test
    fun `the countdown rounds up so the last day is never advertised as zero`() {
        // "0 days left" over a still-working app reads as a bug, and would also
        // be misclassified as RESTRICTED. A part of a day counts as a day.
        val threeHoursAway = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .format(Date(System.currentTimeMillis() + 3 * 3_600_000L))

        assertEquals(1L, daysUntil(threeHoursAway))
        assertEquals(
            VerificationStanding.WARNING,
            standingFor(emailVerified = false, deadline = threeHoursAway)
        )
    }
}
