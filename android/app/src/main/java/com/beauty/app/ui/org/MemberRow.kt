package com.beauty.app.ui.org

import androidx.compose.ui.res.stringResource
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
                "${member.email} · ${com.beauty.app.ui.i18n.roleLabel(member.role)} · ${com.beauty.app.ui.i18n.statusLabel(member.status)}",
                color = TextMuted,
                fontSize = 12.sp
            )
        }
        // A join request is answered, not "removed": Decline sits next to
        // Approve, mirroring the web roster. Server-side both are the same
        // DELETE, so only the wording differs.
        if (member.status == "PENDING") {
            IconButton(onClick = onApprove) {
                Icon(Icons.Default.Check, contentDescription = stringResource(com.beauty.app.R.string.approve), tint = RoseGoldPrimary)
            }
            IconButton(onClick = { pendingAction = MemberDestructiveAction.Decline }) {
                Icon(Icons.Default.Close, contentDescription = stringResource(com.beauty.app.R.string.decline), tint = TextMuted)
            }
        } else {
            if (member.status == "ACTIVE") {
                TextButton(onClick = onToggleRole) {
                    Text(
                        if (member.role == "ORG_ADMIN") stringResource(com.beauty.app.R.string.demote) else stringResource(com.beauty.app.R.string.promote),
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
                    contentDescription = if (member.status == "INVITED") stringResource(com.beauty.app.R.string.withdraw_invitation) else stringResource(com.beauty.app.R.string.remove),
                    tint = TextMuted
                )
            }
        }
    }

    pendingAction?.let { action ->
        val (title, message, confirmLabel) = when (action) {
            MemberDestructiveAction.Decline -> Triple(
                stringResource(com.beauty.app.R.string.decline_request),
                stringResource(com.beauty.app.R.string.decline_member_confirmation, member.fullName, member.email),
                stringResource(com.beauty.app.R.string.decline)
            )
            MemberDestructiveAction.WithdrawInvitation -> Triple(
                stringResource(com.beauty.app.R.string.withdraw_invitation_2),
                stringResource(com.beauty.app.R.string.withdraw_member_confirmation, member.fullName, member.email),
                stringResource(com.beauty.app.R.string.withdraw)
            )
            MemberDestructiveAction.Remove -> Triple(
                stringResource(com.beauty.app.R.string.remove_member),
                stringResource(com.beauty.app.R.string.remove_member_confirmation, member.fullName, member.email),
                stringResource(com.beauty.app.R.string.remove)
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
                TextButton(onClick = { pendingAction = null }) { Text(stringResource(com.beauty.app.R.string.cancel)) }
            }
        )
    }
}

private enum class MemberDestructiveAction {
    Decline,
    WithdrawInvitation,
    Remove
}
