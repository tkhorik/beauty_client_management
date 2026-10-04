package com.beauty.app.ui.admin

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.data.api.AdminOrganizationDto
import com.beauty.app.data.api.AdminUserDto
import com.beauty.app.data.api.OrganizationCreationTokenDto
import com.beauty.app.ui.org.MemberRow
import com.beauty.app.ui.theme.CardSurface
import com.beauty.app.ui.theme.RoseGoldPrimary
import com.beauty.app.ui.theme.TextMuted

private enum class AdminTab(val label: Int) {
    USERS(com.beauty.app.R.string.users),
    ORGANIZATIONS(com.beauty.app.R.string.organizations),
    LINKS(com.beauty.app.R.string.links)
}

/**
 * The Android equivalent of the web admin panel.
 *
 * Reached from a shield action that is only drawn for a `SUPER_ADMIN` — a
 * convenience, not the control: every endpoint behind this screen is gated by
 * `requireSuperAdmin()` on the server, so an ordinary account that somehow got
 * here would see three empty tabs and an error, not anyone else's data.
 *
 * The three tabs deliberately mirror the web panel rather than inventing a
 * mobile-specific arrangement. An operator moving between the two should not
 * have to relearn where suspension lives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    viewModel: AdminViewModel,
    onDone: () -> Unit,
    onOpenOrganization: (AdminOrganizationDto) -> Unit
) {
    var tab by remember { mutableStateOf(AdminTab.USERS) }
    val managingOrg = viewModel.managingOrg

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        managingOrg?.name ?: stringResource(com.beauty.app.R.string.admin_panel),
                        color = RoseGoldPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    // Back means "out of this roster" while one is open, and
                    // "out of the panel" otherwise — the roster is a state of
                    // this screen rather than a destination of its own, so
                    // leaving it should not leave the panel.
                    IconButton(onClick = { if (managingOrg != null) viewModel.stopManagingMembers() else onDone() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(com.beauty.app.R.string.back), tint = TextMuted)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardSurface)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            if (managingOrg == null) {
                TabRow(selectedTabIndex = tab.ordinal, containerColor = CardSurface) {
                    AdminTab.entries.forEach { entry ->
                        Tab(
                            selected = tab == entry,
                            onClick = { tab = entry; viewModel.clearMessages() },
                            text = { Text(stringResource(entry.label), fontSize = 13.sp) }
                        )
                    }
                }
            }

            viewModel.error?.let { Banner(it, isError = true) }
            viewModel.notice?.let { Banner(it, isError = false) }

            // A branch, not an early `return@Column`: returning out of an inline
            // composable lambda skips closing its group and crashes the slot table.
            when {
                viewModel.initialLoading ->
                    Text(stringResource(com.beauty.app.R.string.loading), color = TextMuted, modifier = Modifier.padding(16.dp))
                managingOrg != null -> MembersTab(viewModel, managingOrg)
                tab == AdminTab.USERS -> UsersTab(viewModel)
                tab == AdminTab.ORGANIZATIONS -> OrganizationsTab(viewModel, onOpenOrganization)
                else -> LinksTab(viewModel)
            }
        }
    }
}

@Composable
private fun Banner(message: String, isError: Boolean) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else CardSurface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            com.beauty.app.ui.i18n.localizedMessage(message),
            modifier = Modifier.padding(12.dp),
            fontSize = 13.sp,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else TextMuted
        )
    }
}

// ---------------------------------------------------------------------------
// Users
// ---------------------------------------------------------------------------

@Composable
private fun UsersTab(viewModel: AdminViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(viewModel.users, key = { it.id }) { user ->
            UserRow(
                user = user,
                isSelf = user.id == viewModel.selfId,
                onToggleSuspended = { viewModel.setSuspended(user, !user.isSuspended) }
            )
        }
    }
}

@Composable
private fun UserRow(user: AdminUserDto, isSelf: Boolean, onToggleSuspended: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                buildString {
                    append(user.fullName)
                    if (user.isSuperAdmin) append("  · " + stringResource(com.beauty.app.R.string.role_super_admin))
                    if (user.isSuspended) append("  · " + stringResource(com.beauty.app.R.string.status_suspended))
                },
                fontSize = 14.sp,
                fontWeight = if (user.isSuperAdmin) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                buildString {
                    append(user.email)
                    append(" · ")
                    append(pluralStringResource(com.beauty.app.R.plurals.organizations_count, user.organizationCount, user.organizationCount))
                    if (!user.emailVerified) append(" · " + stringResource(com.beauty.app.R.string.unverified))
                },
                color = TextMuted,
                fontSize = 12.sp
            )
        }
        // The server refuses a self-suspend with a 409 anyway; not drawing the
        // button is how the operator finds that out without pressing it.
        if (isSelf) {
            Text(stringResource(com.beauty.app.R.string.you), color = TextMuted, fontSize = 12.sp)
        } else {
            TextButton(onClick = onToggleSuspended) {
                Text(
                    if (user.isSuspended) stringResource(com.beauty.app.R.string.unsuspend) else stringResource(com.beauty.app.R.string.suspend),
                    color = if (user.isSuspended) RoseGoldPrimary else MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Organizations
// ---------------------------------------------------------------------------

@Composable
private fun OrganizationsTab(
    viewModel: AdminViewModel,
    onOpenOrganization: (AdminOrganizationDto) -> Unit
) {
    var confirming by remember { mutableStateOf<AdminOrganizationDto?>(null) }
    var confirmationSlug by remember { mutableStateOf("") }

    confirming?.let { org ->
        AlertDialog(
            onDismissRequest = { confirming = null; confirmationSlug = "" },
            title = { Text(stringResource(com.beauty.app.R.string.archive_organization)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(com.beauty.app.R.string.archive_organization_help))
                    OutlinedTextField(
                        value = confirmationSlug,
                        onValueChange = { confirmationSlug = it },
                        label = { Text(stringResource(com.beauty.app.R.string.type_handle_to_confirm, org.slug)) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = confirmationSlug == org.slug,
                    onClick = {
                        viewModel.archiveOrganization(org, confirmationSlug)
                        confirming = null
                        confirmationSlug = ""
                    }
                ) { Text(stringResource(com.beauty.app.R.string.archive)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null; confirmationSlug = "" }) {
                    Text(stringResource(com.beauty.app.R.string.cancel))
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(viewModel.organizations, key = { it.id }) { org ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(org.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(com.beauty.app.R.string.organization_created_by, org.slug, org.createdByEmail ?: stringResource(com.beauty.app.R.string.unknown)) +
                            " · " + pluralStringResource(com.beauty.app.R.plurals.members_count, org.memberCount, org.memberCount) +
                            if (org.isArchived) " · " + stringResource(com.beauty.app.R.string.archived) else "",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
                // SUPER_ADMIN receives every active organization from the
                // organization list and can reopen any one repeatedly.
                TextButton(enabled = !org.isArchived, onClick = { onOpenOrganization(org) }) {
                    Text(stringResource(com.beauty.app.R.string.open_organization), color = RoseGoldPrimary, fontSize = 12.sp)
                }
                TextButton(enabled = !org.isArchived, onClick = { viewModel.manageMembers(org) }) {
                    Text(stringResource(com.beauty.app.R.string.members), color = RoseGoldPrimary, fontSize = 12.sp)
                }
                TextButton(
                    enabled = !org.isArchived,
                    onClick = { confirming = org; confirmationSlug = "" }
                ) {
                    Text(stringResource(com.beauty.app.R.string.archive), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }
        if (viewModel.organizations.isEmpty()) {
            item { Text(stringResource(com.beauty.app.R.string.no_organizations_yet), color = TextMuted, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun MembersTab(viewModel: AdminViewModel, org: AdminOrganizationDto) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(viewModel.members, key = { it.userId }) { member ->
            MemberRow(
                member = member,
                onApprove = { viewModel.approve(org.id, member.userId) },
                onDecline = { viewModel.decline(org.id, member.userId) },
                onRemove = { viewModel.remove(org.id, member.userId) },
                onToggleRole = {
                    viewModel.changeRole(
                        org.id,
                        member.userId,
                        if (member.role == "ORG_ADMIN") "ORG_USER" else "ORG_ADMIN"
                    )
                }
            )
        }
        item {
            Text(
                stringResource(com.beauty.app.R.string.admin_members_help),
                color = TextMuted,
                fontSize = 12.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Creation links
// ---------------------------------------------------------------------------

@Composable
private fun LinksTab(viewModel: AdminViewModel) {
    val clipboard = LocalClipboardManager.current
    var label by rememberSaveable { mutableStateOf("") }
    var maxUses by rememberSaveable { mutableStateOf("1") }
    var expiresInHours by rememberSaveable { mutableStateOf("168") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // The freshly issued token first, and impossible to miss: this is the
        // only moment it exists in readable form anywhere.
        viewModel.freshLink?.let { link ->
            item {
                Surface(color = CardSurface, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(stringResource(com.beauty.app.R.string.new_link_copy_it_now), color = RoseGoldPrimary, fontSize = 13.sp)
                        Text(link, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { clipboard.setText(AnnotatedString(link)) },
                                colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                            ) { Text(stringResource(com.beauty.app.R.string.copy)) }
                            TextButton(onClick = { viewModel.dismissFreshToken() }) {
                                Text(stringResource(com.beauty.app.R.string.dismiss), color = TextMuted)
                            }
                        }
                    }
                }
            }
        }

        items(viewModel.links, key = { it.id }) { link -> LinkRow(link, viewModel) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(com.beauty.app.R.string.issue_a_link), color = RoseGoldPrimary, fontSize = 13.sp)
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(com.beauty.app.R.string.label_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = maxUses,
                        onValueChange = { maxUses = it.filter(Char::isDigit) },
                        label = { Text(stringResource(com.beauty.app.R.string.max_uses)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = expiresInHours,
                        onValueChange = { expiresInHours = it.filter(Char::isDigit) },
                        label = { Text(stringResource(com.beauty.app.R.string.expires_in_h)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                // Both bounds are required by the server — a link with no cap
                // and no expiry is a standing backdoor — so the button stays
                // disabled until both parse, rather than sending a request
                // that is certain to come back 400.
                val uses = maxUses.toIntOrNull()
                val hours = expiresInHours.toLongOrNull()
                Button(
                    onClick = { viewModel.issueLink(label, uses ?: 1, hours ?: 168L) },
                    enabled = uses != null && uses >= 1 && hours != null && hours >= 1,
                    colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                ) { Text(stringResource(com.beauty.app.R.string.issue_link)) }
            }
        }
    }
}

@Composable
private fun LinkRow(link: OrganizationCreationTokenDto, viewModel: AdminViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(link.label ?: stringResource(com.beauty.app.R.string.untitled_link), fontSize = 14.sp)
            Text(
                buildString {
                    append(stringResource(com.beauty.app.R.string.link_usage, link.usesCount, link.maxUses, com.beauty.app.ui.i18n.localizedIsoDate(link.expiresAt)))
                    if (link.isRevoked) append(" · " + stringResource(com.beauty.app.R.string.revoked))
                    else if (link.isExhausted) append(" · " + stringResource(com.beauty.app.R.string.exhausted))
                },
                color = TextMuted,
                fontSize = 12.sp
            )
        }
        if (!link.isRevoked) {
            IconButton(onClick = { viewModel.revokeLink(link.id) }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(com.beauty.app.R.string.revoke_link), tint = TextMuted)
            }
        }
    }
}
