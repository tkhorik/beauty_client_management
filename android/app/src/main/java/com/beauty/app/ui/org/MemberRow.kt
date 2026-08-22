package com.beauty.app.ui.org

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.data.api.MemberDto
import com.beauty.app.ui.theme.RoseGoldPrimary
import com.beauty.app.ui.theme.TextMuted

/**
 * One row of an organization's roster.
 *
 * Lives in its own file, and is `internal` rather than `private`, because two
 * screens draw the same roster: [OrganizationScreen] for the organization the
 * user is working in, and the admin panel for any organization at all. Pure
 * UI — it holds no ViewModel and makes no decision about who is allowed to
 * press these buttons; the caller supplies the actions and the server refuses
 * the ones it should.
 */
@Composable
internal fun MemberRow(
    member: MemberDto,
    onApprove: () -> Unit,
    onRemove: () -> Unit,
    onToggleRole: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(member.fullName, fontSize = 14.sp)
            Text(
                "${member.email} · ${member.role.removePrefix("ORG_").lowercase()} · ${member.status.lowercase()}",
                color = TextMuted,
                fontSize = 12.sp
            )
        }
        if (member.status == "PENDING") {
            IconButton(onClick = onApprove) {
                Icon(Icons.Default.Check, contentDescription = "Approve", tint = RoseGoldPrimary)
            }
        } else if (member.status == "ACTIVE") {
            TextButton(onClick = onToggleRole) {
                Text(
                    if (member.role == "ORG_ADMIN") "Demote" else "Promote",
                    color = RoseGoldPrimary,
                    fontSize = 12.sp
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = TextMuted)
        }
    }
}
