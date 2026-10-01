package com.beauty.app.ui.org

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.data.api.OrganizationDto
import com.beauty.app.BuildConfig
import com.beauty.app.ui.theme.CardSurface
import com.beauty.app.ui.theme.RoseGoldPrimary
import com.beauty.app.ui.theme.TextMuted
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

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
    onLogout: () -> Unit,
    onOpenAdmin: (() -> Unit)? = null
) {
    val current = viewModel.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var creationLink by remember { mutableStateOf("") }
    var creationLinkError by remember { mutableStateOf<String?>(null) }
    var joinSlug by remember { mutableStateOf("") }
    var inviteEmail by remember { mutableStateOf("") }

    // Re-read the list whenever this screen is shown.
    //
    // Membership status changes on somebody *else's* device — an administrator
    // approves the request — so nothing on this one can observe it. The
    // ViewModel is hoisted to the NavHost and previously fetched only in its
    // `init`, which meant a user approved after the app started went on being
    // shown their old PENDING row for as long as the process lived, with no
    // way to ask for an update. Re-requesting only produced "you are already a
    // member", which is true and unhelpful.
    LaunchedEffect(Unit) { viewModel.refresh() }

    // And again on every resume, for the common case: the user is told they
    // have been approved, switches back to the app, and expects to be in.
    // Mirrors the directory's own resume refresh in `MainActivity`. Overlapping
    // triggers collapse inside the ViewModel.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The roster is only fetched once someone allowed to see it is actually
    // looking — a plain member's request would be refused with ADMIN_REQUIRED,
    // and firing it anyway would show them an error they did nothing to cause.
    //
    // Keyed on isSuperAdmin as well, because that arrives from a second request
    // that usually lands *after* the organization list: without it a super
    // admin who is a plain member here would have the effect evaluated once,
    // while the flag was still false, and never again.
    LaunchedEffect(current?.id, current?.role, viewModel.isSuperAdmin) {
        val orgId = current?.id
        if (orgId != null && viewModel.canManage(current)) viewModel.loadMembers(orgId)
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
                // Someone waiting on an approval is watching this screen while
                // it happens elsewhere. The automatic refreshes above cannot
                // help them without a resume or a navigation, so give them
                // something to press.
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !viewModel.loading) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = if (viewModel.loading) TextMuted else RoseGoldPrimary
                        )
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Creating an organization requires a creation link from an administrator. " +
                            "Paste the link here to continue securely in the web app.",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = creationLink,
                        onValueChange = {
                            creationLink = it
                            creationLinkError = null
                        },
                        label = { Text("Organization creation link") },
                        singleLine = true,
                        isError = creationLinkError != null,
                        supportingText = creationLinkError?.let { message -> { Text(message) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val safeLink = creationLinkForBrowser(creationLink)
                            if (safeLink == null) {
                                creationLinkError = "Paste a valid creation link for this Aura environment."
                            } else {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeLink)))
                            }
                        },
                        enabled = creationLink.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                    ) { Text("Open in browser") }
                }
            }

            // -- Manage (administrators and super admins) -------------------
            //
            // Hidden from plain members as a courtesy, not as the control: the
            // backend refuses every one of these calls with ADMIN_REQUIRED
            // regardless of what this screen chooses to draw.
            //
            // A super admin passes even where their membership role is
            // ORG_USER — see OrganizationViewModel.canManage. The membership
            // role itself is left alone: it is an honest statement about this
            // organization, and folding a system-wide flag into it is how
            // "admin of my salon" turns into "admin of every salon".
            if (current != null && viewModel.canManage(current)) {
                item { SectionTitle("Members of ${current.name}") }
                items(viewModel.members, key = { it.userId }) { member ->
                    MemberRow(
                        member = member,
                        onApprove = { viewModel.approve(current.id, member.userId) },
                onDecline = { viewModel.decline(current.id, member.userId) },
                        onRemove = { viewModel.remove(current.id, member.userId) },
                        onToggleRole = {
                            viewModel.changeRole(
                                current.id,
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
                            onClick = { viewModel.invite(current.id, inviteEmail, "ORG_USER"); inviteEmail = "" },
                            enabled = inviteEmail.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                        ) { Text("Send invitation") }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onLogout) { Text("Sign out", color = TextMuted) }
                    if (viewModel.isSuperAdmin && onOpenAdmin != null) {
                        TextButton(onClick = onOpenAdmin) { Text("Admin panel", color = RoseGoldPrimary) }
                    }
                }
            }
        }
    }
}

/**
 * Only opens an administrator-issued creation link for this deployment's web
 * origin. This deliberately rejects arbitrary deep links and lookalike hosts.
 */
internal fun creationLinkForBrowser(raw: String): String? {
    val candidate = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
    val expected = runCatching { Uri.parse(BuildConfig.API_BASE_URL) }.getOrNull() ?: return null
    if (candidate.scheme !in setOf("http", "https") || candidate.getQueryParameter("orgToken").isNullOrBlank()) return null

    val expectedPort = when {
        expected.host == "10.0.2.2" && (expected.port == -1 || expected.port == 8080) -> 5174
        expected.port != -1 -> expected.port
        expected.scheme == "https" -> 443
        else -> 80
    }
    val candidatePort = when {
        candidate.port != -1 -> candidate.port
        candidate.scheme == "https" -> 443
        else -> 80
    }
    return raw.trim().takeIf {
        candidate.scheme == expected.scheme &&
            candidate.host == expected.host &&
            candidatePort == expectedPort
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
