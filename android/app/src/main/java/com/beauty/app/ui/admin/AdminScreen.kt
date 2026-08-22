package com.beauty.app.ui.admin

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

private enum class AdminTab(val label: String) {
    USERS("Users"),
    ORGANIZATIONS("Organizations"),
    LINKS("Links")
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
fun AdminScreen(viewModel: AdminViewModel, onDone: () -> Unit) {
    var tab by remember { mutableStateOf(AdminTab.USERS) }
    val managingOrg = viewModel.managingOrg

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        managingOrg?.name ?: "Admin panel",
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
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextMuted)
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
                            text = { Text(entry.label, fontSize = 13.sp) }
                        )
                    }
                }
            }

            viewModel.error?.let { Banner(it, isError = true) }
            viewModel.notice?.let { Banner(it, isError = false) }

            if (viewModel.initialLoading) {
                Text("Loading…", color = TextMuted, modifier = Modifier.padding(16.dp))
                return@Column
            }

            when {
                managingOrg != null -> MembersTab(viewModel, managingOrg)
                tab == AdminTab.USERS -> UsersTab(viewModel)
                tab == AdminTab.ORGANIZATIONS -> OrganizationsTab(viewModel)
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
            message,
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
                    if (user.isSuperAdmin) append("  · SUPER ADMIN")
                    if (user.isSuspended) append("  · SUSPENDED")
                },
                fontSize = 14.sp,
                fontWeight = if (user.isSuperAdmin) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                buildString {
                    append(user.email)
                    append(" · ")
                    append(user.organizationCount)
                    append(if (user.organizationCount == 1) " organization" else " organizations")
                    if (!user.emailVerified) append(" · unverified")
                },
                color = TextMuted,
                fontSize = 12.sp
            )
        }
        // The server refuses a self-suspend with a 409 anyway; not drawing the
        // button is how the operator finds that out without pressing it.
        if (isSelf) {
            Text("You", color = TextMuted, fontSize = 12.sp)
        } else {
            TextButton(onClick = onToggleSuspended) {
                Text(
                    if (user.isSuspended) "Unsuspend" else "Suspend",
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
private fun OrganizationsTab(viewModel: AdminViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(viewModel.organizations, key = { it.id }) { org ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(org.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${org.slug} · created by ${org.createdByEmail ?: "unknown"} · " +
                            "${org.memberCount} member${if (org.memberCount == 1) "" else "s"}",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
                // Reaches organizations this account has never joined, which
                // the organization switcher cannot: that list is built from
                // memberships, while the backend's permission is not.
                TextButton(onClick = { viewModel.manageMembers(org) }) {
                    Text("Members", color = RoseGoldPrimary, fontSize = 12.sp)
                }
            }
        }
        if (viewModel.organizations.isEmpty()) {
            item { Text("No organizations yet.", color = TextMuted, fontSize = 13.sp) }
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
                "Pending and invited members are listed here too. Removing someone revokes " +
                    "their access immediately; the organization keeps their clients and visits.",
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
    var label by remember { mutableStateOf("") }
    var maxUses by remember { mutableStateOf("1") }
    var expiresInHours by remember { mutableStateOf("168") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // The freshly issued token first, and impossible to miss: this is the
        // only moment it exists in readable form anywhere.
        viewModel.freshToken?.let { token ->
            item {
                Surface(color = CardSurface, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("New link — copy it now", color = RoseGoldPrimary, fontSize = 13.sp)
                        Text(token, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { clipboard.setText(AnnotatedString(token)) },
                                colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                            ) { Text("Copy") }
                            TextButton(onClick = { viewModel.dismissFreshToken() }) {
                                Text("Dismiss", color = TextMuted)
                            }
                        }
                    }
                }
            }
        }

        items(viewModel.links, key = { it.id }) { link -> LinkRow(link, viewModel) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Issue a link", color = RoseGoldPrimary, fontSize = 13.sp)
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = maxUses,
                        onValueChange = { maxUses = it.filter(Char::isDigit) },
                        label = { Text("Max uses") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = expiresInHours,
                        onValueChange = { expiresInHours = it.filter(Char::isDigit) },
                        label = { Text("Expires in (h)") },
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
                ) { Text("Issue link") }
            }
        }
    }
}

@Composable
private fun LinkRow(link: OrganizationCreationTokenDto, viewModel: AdminViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(link.label ?: "Untitled link", fontSize = 14.sp)
            Text(
                buildString {
                    append("${link.usesCount}/${link.maxUses} used · expires ${link.expiresAt.take(10)}")
                    if (link.isRevoked) append(" · revoked")
                    else if (link.isExhausted) append(" · exhausted")
                },
                color = TextMuted,
                fontSize = 12.sp
            )
        }
        if (!link.isRevoked) {
            IconButton(onClick = { viewModel.revokeLink(link.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "Revoke link", tint = TextMuted)
            }
        }
    }
}
