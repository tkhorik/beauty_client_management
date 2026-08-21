package com.beauty.app.ui.verification

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Where an account stands with respect to email verification.
 *
 * Shared by [VerificationBanner] and [VerificationWallScreen] so the two
 * cannot disagree about the same profile. They render mutually exclusive
 * states, and a client that showed a "3 days left" banner over a screen the
 * server is refusing outright would be worse than showing nothing.
 */
enum class VerificationStanding {
    /** Confirmed, or a deployment with enforcement switched off. Nothing to show. */
    VERIFIED,

    /** Unconfirmed but still inside a grace window — a banner over a working app. */
    WARNING,

    /** The server is refusing this account. The wall replaces the app. */
    RESTRICTED
}

/**
 * Classifies a profile.
 *
 * A null [deadline] means the backend advertised none, which happens when
 * enforcement is off. That is treated as [VerificationStanding.VERIFIED] on
 * purpose: nagging about a restriction the server does not apply would be the
 * client inventing policy. The address is still worth confirming — password
 * reset depends on it — but the Settings screen is where that belongs.
 *
 * The client never *decides* anything here. The server refuses or does not,
 * and this only picks which explanation to render; a device clock that is days
 * out costs at most a wrong countdown.
 */
fun standingFor(emailVerified: Boolean, deadline: String?): VerificationStanding {
    if (emailVerified) return VerificationStanding.VERIFIED
    if (deadline == null) return VerificationStanding.VERIFIED
    return if (daysUntil(deadline) <= 0L) {
        VerificationStanding.RESTRICTED
    } else {
        VerificationStanding.WARNING
    }
}

/**
 * Whole days between now and [deadline], floored at zero.
 *
 * Parsed with [SimpleDateFormat] rather than `java.time`, which is not
 * available on this module's `minSdk` of 24 without core library desugaring —
 * `LocalDateTime.parse` compiles cleanly and then throws `NoClassDefFoundError`
 * on an API 24 device, which is the worst possible way to find out.
 *
 * The server sends a local ISO timestamp (`2026-09-01T12:00:00`), optionally
 * with fractional seconds, so the first 19 characters are taken and the rest
 * discarded: the difference is measured in days here and sub-second precision
 * could not matter.
 *
 * An unparseable value yields 0 — "restricted". The only way to reach this
 * function is for the server to have sent a deadline it also intends to
 * enforce, so reading a value we cannot parse as "plenty of time" would promise
 * the user days they do not have, and then strand them behind a wall the app
 * never warned about.
 */
fun daysUntil(deadline: String): Long {
    val parsed = try {
        // Non-lenient on purpose. SimpleDateFormat's default happily rolls
        // "month 13" into the following January and "hour 99" four days
        // forward, so a corrupted value would parse into a date comfortably in
        // the future and hand the user a countdown instead of the wall the
        // server is actually applying. Strict parsing sends it to the
        // catch below, where it lands on the safe side.
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { isLenient = false }
            .parse(deadline.take(19))
    } catch (_: ParseException) {
        null
    } ?: return 0L

    val remaining = parsed.time - System.currentTimeMillis()
    return if (remaining <= 0L) 0L else remaining / 86_400_000L + 1L
}
