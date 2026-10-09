package com.beauty.app.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.beauty.app.R

@Composable
fun statusLabel(status: String): String = stringResource(when (status) {
    "COMPLETED" -> R.string.status_completed
    "SCHEDULED" -> R.string.status_scheduled
    "CANCELLED" -> R.string.status_cancelled
    "ACTIVE" -> R.string.status_active
    "PENDING" -> R.string.status_pending
    "INVITED" -> R.string.status_invited
    "SUSPENDED" -> R.string.status_suspended
    "DECLINED" -> R.string.status_declined
    else -> R.string.status_unknown
})

@Composable
fun roleLabel(role: String): String = stringResource(when (role) {
    "SUPER_ADMIN" -> R.string.role_super_admin
    "ORG_ADMIN" -> R.string.role_admin
    else -> R.string.role_member
})

/** Labels of predefined filter options only; user-entered tags remain untouched. */
@Composable
fun tagLabel(tag: String): String = when (tag) {
    "" -> stringResource(R.string.all)
    "VIP" -> stringResource(R.string.vip)
    "Sensitive Skin" -> stringResource(R.string.sensitive_skin)
    "Lash Extensions" -> stringResource(R.string.lash_extensions)
    "Hair Coloring" -> stringResource(R.string.hair_coloring)
    "Skin Treatment" -> stringResource(R.string.skin_treatment)
    else -> tag
}

@Composable
fun verificationMessage(code: String): String = stringResource(when (code) {
    "RESOURCE:ui2_still_unconfirmed_open_the_link_then_try_again" -> R.string.ui2_still_unconfirmed_open_the_link_then_try_again
    "RESOURCE:ui2_new_link_sent_it_can_take_a_minute_to_arrive" -> R.string.ui2_new_link_sent_it_can_take_a_minute_to_arrive
    "RESOURCE:ui2_couldn_t_send_the_link_check_your_connection" -> R.string.ui2_couldn_t_send_the_link_check_your_connection
    "RESOURCE:ui2_still_unconfirmed_try_the_link_again" -> R.string.ui2_still_unconfirmed_try_the_link_again
    "RESOURCE:ui2_link_sent_check_your_inbox_and_spam_folder" -> R.string.ui2_link_sent_check_your_inbox_and_spam_folder
    else -> R.string.error_action_failed
})

fun localizedIsoDate(value: String): String = runCatching {
    val parsed = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(value.take(10))
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(requireNotNull(parsed))
}.getOrDefault(value.take(10))

/**
 * A server-side local timestamp (`2026-10-09T14:03:00[.ffffff]`) as a
 * [java.util.Date], or null if it cannot be read. `SimpleDateFormat`, not
 * `java.time`: see [com.beauty.app.ui.verification.daysUntil] for why
 * `java.time` is off limits at this module's `minSdk`.
 */
fun parseServerDateTime(value: String): java.util.Date? = runCatching {
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        .apply { isLenient = false }
        .parse(value.take(19))
}.getOrNull()

fun localizedIsoDateTime(value: String): String =
    parseServerDateTime(value)?.let {
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(it)
    } ?: value
