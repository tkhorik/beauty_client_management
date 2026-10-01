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
import com.beauty.app.ui.theme.RoseGoldPrimary
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
        TagTone.VIP -> Color(0x26A855F7) to Color(0xFFC084FC)
        TagTone.SENSITIVE -> Color(0x26EF4444) to Color(0xFFFCA5A5)
        TagTone.DEFAULT -> Color(0x1AE5B899) to RoseGoldPrimary
    }
    Surface(
        color = background,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.border(1.dp, foreground.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
    ) {
        Text(
            tag,
            color = foreground,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun VisitStatusBadge(status: String) {
    val color = when (status) {
        "COMPLETED" -> Color(0xFF2DD4BF)
        "SCHEDULED" -> Color(0xFF60A5FA)
        else -> Color(0xFFF87171)
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
    ) {
        Text(
            status,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
