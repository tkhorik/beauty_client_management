package com.beauty.app.ui.org

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    onDecline: () -> Unit,
    onRemove: () -> Unit,
    onToggleRole: () -> Unit
) {
    var pendingAction by remember(member.userId, member.status) {
        mutableStateOf<MemberDestructiveAction?>(null)
    }

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
        // A join request is answered, not "removed": Decline sits next to
        // Approve, mirroring the web roster. Server-side both are the same
        // DELETE, so only the wording differs.
        if (member.status == "PENDING") {
            IconButton(onClick = onApprove) {
                Icon(Icons.Default.Check, contentDescription = "Approve", tint = RoseGoldPrimary)
            }
            IconButton(onClick = { pendingAction = MemberDestructiveAction.Decline }) {
                Icon(Icons.Default.Close, contentDescription = "Decline", tint = TextMuted)
            }
        } else {
            if (member.status == "ACTIVE") {
                TextButton(onClick = onToggleRole) {
                    Text(
                        if (member.role == "ORG_ADMIN") "Demote" else "Promote",
                        color = RoseGoldPrimary,
                        fontSize = 12.sp
                    )
                }
            }
            IconButton(
                onClick = {
                    pendingAction = if (member.status == "INVITED") {
                        MemberDestructiveAction.WithdrawInvitation
                    } else {
                        MemberDestructiveAction.Remove
                    }
                }
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = if (member.status == "INVITED") "Withdraw invitation" else "Remove",
                    tint = TextMuted
                )
            }
        }
    }

    pendingAction?.let { action ->
        val (title, message, confirmLabel) = when (action) {
            MemberDestructiveAction.Decline -> Triple(
                "Decline request?",
                "Decline ${member.fullName} (${member.email})'s request to join? They will not gain access to this organization's data.",
                "Decline"
            )
            MemberDestructiveAction.WithdrawInvitation -> Triple(
                "Withdraw invitation?",
                "Withdraw the invitation for ${member.fullName} (${member.email})? They will no longer be able to accept it.",
                "Withdraw"
            )
            MemberDestructiveAction.Remove -> Triple(
                "Remove member?",
                "Remove ${member.fullName} (${member.email}) from this organization? Their access will be revoked immediately. Clients and visits they entered will stay with the organization.",
                "Remove"
            )
        }
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        when (action) {
                            MemberDestructiveAction.Decline -> onDecline()
                            MemberDestructiveAction.WithdrawInvitation,
                            MemberDestructiveAction.Remove -> onRemove()
                        }
                    }
                ) { Text(confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) { Text("Cancel") }
            }
        )
    }
}

private enum class MemberDestructiveAction {
    Decline,
    WithdrawInvitation,
    Remove
}
