package com.beauty.app.ui.org

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.data.api.MemberDto
import com.beauty.app.data.api.OrganizationDto
import com.beauty.app.ui.theme.CardSurface
import com.beauty.app.ui.theme.RoseGoldPrimary
import com.beauty.app.ui.theme.TextMuted

/**
 * Organization picker, onboarding, and membership management in one screen.
 *
 * One screen rather than three because the states are a progression, not
 * separate destinations: a user with no organization creates or joins one, a
 * user with several picks between them, and an administrator manages the one
 * they picked. Splitting them would mean navigating between screens to answer
 * a single question — "which salon am I working in?"
 *
 * @param onDone called when the user has an organization selected and wants to
 *   get on with their work. Absent when this screen *is* the app's current
 *   state because no organization exists yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizationScreen(
    viewModel: OrganizationViewModel,
    onDone: (() -> Unit)?,
    onLogout: () -> Unit
) {
    val current = viewModel.current
    var showCreate by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var slug by remember { mutableStateOf("") }
    var joinSlug by remember { mutableStateOf("") }
    var inviteEmail by remember { mutableStateOf("") }

    // The roster is only fetched once an administrator is actually looking at
    // it — a plain member's request would be refused with ADMIN_REQUIRED, and
    // firing it anyway would show them an error they did nothing to cause.
    LaunchedEffect(current?.id, current?.role) {
        if (current?.isAdmin == true) viewModel.loadMembers()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Organizations", color = RoseGoldPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onDone != null) {
                        IconButton(onClick = onDone) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextMuted)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardSurface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            viewModel.error?.let { message ->
                item { Banner(message, isError = true) }
            }
            viewModel.notice?.let { message ->
                item { Banner(message, isError = false) }
            }

            if (viewModel.loading) {
                item { Text("Loading…", color = TextMuted) }
            }

            // -- Pick -------------------------------------------------------
            if (viewModel.activeOrganizations.isNotEmpty()) {
                item { SectionTitle("Your organizations") }
                items(viewModel.activeOrganizations, key = { it.id }) { org ->
                    OrganizationRow(
                        org = org,
                        selected = org.id == viewModel.activeOrgId,
                        onSelect = { viewModel.select(org.id) }
                    )
                }
            }

            // Requests and invitations that grant nothing yet. Listed so a user
            // who has already asked does not ask again and hit the unique
            // constraint with an error they cannot interpret.
            val waiting = viewModel.organizations.filterNot { it.isActive }
            if (waiting.isNotEmpty()) {
                item { SectionTitle("Waiting for approval") }
                items(waiting, key = { it.id }) { org ->
                    Text(
                        "${org.name} — ${org.status.lowercase()}",
                        color = TextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            // -- Join -------------------------------------------------------
            item { SectionTitle("Join an organization") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = joinSlug,
                        onValueChange = { joinSlug = it },
                        label = { Text("Organization handle") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { viewModel.requestToJoin(joinSlug); joinSlug = "" },
                        enabled = joinSlug.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                    ) { Text("Request access") }
                }
            }

            // -- Create -----------------------------------------------------
            item { SectionTitle("Create an organization") }
            item {
                if (!showCreate) {
                    TextButton(onClick = { showCreate = true }) {
                        Text("Create a new one", color = RoseGoldPrimary)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = slug,
                            onValueChange = { slug = it },
                            label = { Text("Handle (optional)") },
                            supportingText = {
                                Text("What colleagues type to request access. Lowercase, numbers, hyphens.")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                viewModel.createOrganization(name, slug)
                                name = ""; slug = ""; showCreate = false
                            },
                            enabled = name.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                        ) { Text("Create") }
                    }
                }
            }

            // -- Manage (administrators only) -------------------------------
            //
            // Hidden from plain members as a courtesy, not as the control: the
            // backend refuses every one of these calls with ADMIN_REQUIRED
            // regardless of what this screen chooses to draw.
            if (current?.isAdmin == true) {
                item { SectionTitle("Members of ${current.name}") }
                items(viewModel.members, key = { it.userId }) { member ->
                    MemberRow(
                        member = member,
                        onApprove = { viewModel.approve(member.userId) },
                        onRemove = { viewModel.remove(member.userId) },
                        onToggleRole = {
                            viewModel.changeRole(
                                member.userId,
                                if (member.role == "ORG_ADMIN") "ORG_USER" else "ORG_ADMIN"
                            )
                        }
                    )
                }
                item {
                    Text(
                        "Removing someone revokes their access immediately. Clients and visits " +
                            "they entered stay with the organization.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = inviteEmail,
                            onValueChange = { inviteEmail = it },
                            label = { Text("Invite by email") },
                            supportingText = { Text("They need an account already.") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { viewModel.invite(inviteEmail, "ORG_USER"); inviteEmail = "" },
                            enabled = inviteEmail.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                        ) { Text("Send invitation") }
                    }
                }
            }

            item {
                TextButton(onClick = onLogout) { Text("Sign out", color = TextMuted) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = RoseGoldPrimary,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier.padding(top = 12.dp)
    )
}

@Composable
private fun Banner(message: String, isError: Boolean) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else CardSurface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            message,
            modifier = Modifier.padding(12.dp),
            fontSize = 13.sp,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else TextMuted
        )
    }
}

@Composable
private fun OrganizationRow(org: OrganizationDto, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(org.name, fontWeight = FontWeight.SemiBold)
            Text(
                if (org.isAdmin) "${org.slug} · administrator" else org.slug,
                color = TextMuted,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun MemberRow(
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
