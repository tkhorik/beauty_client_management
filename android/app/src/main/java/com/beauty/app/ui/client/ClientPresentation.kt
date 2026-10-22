package com.beauty.app.ui.client

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.data.api.VisitAttachmentDto
import com.beauty.app.ui.theme.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.text.DateFormat
import java.util.Date

// Display rules shared by the directory card and the client detail screen.
// Pure functions where possible so they can be unit-tested without Compose;
// each mirrors a specific piece of the web client, named in its comment.

fun decodeTags(tagsJson: String): List<String> =
    runCatching { Json.decodeFromString<List<String>>(tagsJson) }.getOrDefault(emptyList())

/**
 * Tags in use with how many clients carry each, most common first. Spellings
 * differing only in case count as one tag, as the server's `/clients/tags`.
 */
fun tagCounts(tagLists: List<List<String>>): List<Pair<String, Int>> =
    tagLists
        .flatMap { tags -> tags.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase) }
        .groupBy(String::lowercase)
        .map { (_, spellings) -> spellings.first() to spellings.size }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })

/** Key/value pairs for display, decoding JSON strings rather than quoting them. */
fun decodeAttributes(customFieldsJson: String): List<Pair<String, String>> =
    runCatching { Json.parseToJsonElement(customFieldsJson).jsonObject }.getOrNull()
        ?.map { (key, value) -> key to ((value as? JsonPrimitive)?.content ?: value.toString()) }
        .orEmpty()

/** `ClientCard.tsx` previews the first three attributes. */
fun attributeSummary(customFieldsJson: String, limit: Int = 3): List<Pair<String, String>> =
    decodeAttributes(customFieldsJson).take(limit)

enum class TagTone { VIP, SENSITIVE, DEFAULT }

/** Same matching as `ClientCard.tsx`: exact "vip", or anything containing "sensitive". */
fun tagTone(tag: String): TagTone = when {
    tag.equals("vip", ignoreCase = true) -> TagTone.VIP
    tag.contains("sensitive", ignoreCase = true) -> TagTone.SENSITIVE
    else -> TagTone.DEFAULT
}

/** The web offers "Compare Before/After" only when a visit has both. */
fun hasBeforeAndAfter(attachments: List<VisitAttachmentDto>): Boolean =
    attachments.any { it.tag == "BEFORE" } && attachments.any { it.tag == "AFTER" }

fun visitsLabel(count: Int): String = if (count == 1) "1 Visit" else "$count Visits"

fun formatUpdatedDate(epochMillis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))

@Composable
fun TagBadge(tag: String) {
    val (background, foreground) = when (tagTone(tag)) {
        TagTone.VIP -> Peach.copy(alpha = 0.45f) to Forest
        TagTone.SENSITIVE -> Color(0xFFF8E4DD) to Danger
        TagTone.DEFAULT -> Sage to Forest
    }
    Surface(
        color = background,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.border(1.dp, foreground.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
    ) {
        Text(
            tag,
            color = foreground,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun VisitStatusBadge(status: String) {
    val color = when (status) {
        "COMPLETED" -> Success
        "SCHEDULED" -> Forest
        else -> Danger
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
    ) {
        Text(
            com.beauty.app.ui.i18n.statusLabel(status),
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
