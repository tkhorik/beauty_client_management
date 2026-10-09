package com.beauty.app.ui.org

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import com.beauty.app.BuildConfig
import com.beauty.app.data.api.AuditEventDto
import com.beauty.app.data.api.OrganizationDto
import com.beauty.app.ui.i18n.localizedIsoDateTime
import com.beauty.app.ui.i18n.parseServerDateTime
import com.beauty.app.ui.tokenFromWebAppLink
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
    val listState = rememberLazyListState()
    LaunchedEffect(viewModel.creationLinkStatus, viewModel.loading) {
        if (viewModel.creationLinkStatus != OrganizationViewModel.CreationLinkStatus.NONE) {
            val createIndex = (if (viewModel.error != null) 1 else 0) +
                (if (viewModel.notice != null) 1 else 0) + (if (viewModel.loading) 1 else 0) +
                (if (viewModel.activeOrganizations.isNotEmpty()) 1 + viewModel.activeOrganizations.size else 0) +
                viewModel.organizations.count { !it.isActive }.let { if (it > 0) it + 1 else 0 } + 2
            listState.scrollToItem(createIndex)
        }
    }
    val current = viewModel.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var creationLink by viewModel::creationLinkDraft
    var creationLinkError by remember { mutableStateOf<String?>(null) }
    var newOrgName by rememberSaveable { mutableStateOf("") }
    var newOrgSlug by rememberSaveable { mutableStateOf("") }
    var joinSlug by viewModel::joinDraft
    var inviteEmail by rememberSaveable { mutableStateOf("") }

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

    viewModel.inviteOffer?.let { offer -> InviteOfferDialog(offer, onJoin = viewModel::acceptInvite, onDismiss = viewModel::dismissInvite) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(com.beauty.app.R.string.organizations), color = RoseGoldPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onDone != null) {
                        IconButton(onClick = onDone) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(com.beauty.app.R.string.back), tint = TextMuted)
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
                            contentDescription = stringResource(com.beauty.app.R.string.refresh),
                            tint = if (viewModel.loading) TextMuted else RoseGoldPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardSurface)
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
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
                item { Text(stringResource(com.beauty.app.R.string.loading), color = TextMuted) }
            }

            // -- Pick -------------------------------------------------------
            if (viewModel.activeOrganizations.isNotEmpty()) {
                item { SectionTitle(stringResource(com.beauty.app.R.string.your_organizations)) }
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
            val waiting = viewModel.organizations.filter { it.isAwaitingApproval || it.status == "SUSPENDED" }
            if (waiting.isNotEmpty()) {
                item { SectionTitle(stringResource(com.beauty.app.R.string.waiting_for_approval)) }
                items(waiting, key = { it.id }) { org ->
                    Text(
                        "${org.name} — ${com.beauty.app.ui.i18n.statusLabel(org.status)}",
                        color = TextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            // Told, rather than left to watch the request vanish, and told
            // when they may ask again.
            val declined = viewModel.organizations.filter { it.isDeclined }
            items(declined, key = { "declined-${it.id}" }) { org ->
                Banner(declinedMessage(org), isError = true, localize = false)
            }

            // -- Join -------------------------------------------------------
            item { SectionTitle(stringResource(com.beauty.app.R.string.join_an_organization)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = joinSlug,
                        onValueChange = { joinSlug = it },
                        label = { Text(stringResource(com.beauty.app.R.string.organization_handle)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { viewModel.requestToJoin(joinSlug); joinSlug = "" },
                        enabled = joinSlug.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                    ) { Text(stringResource(com.beauty.app.R.string.request_access)) }
                }
            }

            // -- Create -----------------------------------------------------
            //
            // Gated on an administrator-issued creation link, like the web
            // onboarding. Verified links open this form; manual paste remains a fallback.
            item { SectionTitle(stringResource(com.beauty.app.R.string.create_an_organization)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (viewModel.creationLinkStatus) {
                        OrganizationViewModel.CreationLinkStatus.NONE,
                        OrganizationViewModel.CreationLinkStatus.INVALID -> {
                            Text(
                                stringResource(com.beauty.app.R.string.creation_link_help),
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                            val invalid = viewModel.creationLinkStatus ==
                                OrganizationViewModel.CreationLinkStatus.INVALID
                            val message = creationLinkError ?: if (invalid) {
                                stringResource(com.beauty.app.R.string.creation_link_invalid)
                            } else null
                            OutlinedTextField(
                                value = creationLink,
                                onValueChange = {
                                    creationLink = it
                                    creationLinkError = null
                                },
                                label = { Text(stringResource(com.beauty.app.R.string.organization_creation_link)) },
                                singleLine = true,
                                isError = message != null,
                                supportingText = message?.let { text -> { Text(if (creationLinkError != null) com.beauty.app.ui.i18n.localizedMessage(text) else text) } },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    val token = tokenFromWebAppLink(creationLink, "orgToken")
                                    if (token == null) {
                                        creationLinkError = "INVALID_CREATE_LINK"
                                    } else {
                                        viewModel.checkCreationToken(token)
                                    }
                                },
                                enabled = creationLink.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                            ) { Text(stringResource(com.beauty.app.R.string.continue_action)) }
                        }
                        OrganizationViewModel.CreationLinkStatus.CHECKING ->
                            Text(stringResource(com.beauty.app.R.string.checking_your_link), color = TextMuted, fontSize = 13.sp)
                        OrganizationViewModel.CreationLinkStatus.VALID -> {
                            val fieldErrors = viewModel.createFieldErrors
                            OutlinedTextField(
                                value = newOrgName,
                                onValueChange = { newOrgName = it },
                                label = { Text(stringResource(com.beauty.app.R.string.name)) },
                                placeholder = { Text(stringResource(com.beauty.app.R.string.organization_example)) },
                                singleLine = true,
                                enabled = !viewModel.creating,
                                isError = fieldErrors["name"] != null,
                                supportingText = fieldErrors["name"]?.let { text -> { Text(com.beauty.app.ui.i18n.localizedMessage(text)) } },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = newOrgSlug,
                                onValueChange = { newOrgSlug = it },
                                label = { Text(stringResource(com.beauty.app.R.string.handle_optional)) },
                                placeholder = { Text("aura-downtown") },
                                singleLine = true,
                                enabled = !viewModel.creating,
                                isError = fieldErrors["slug"] != null,
                                supportingText = {
                                    Text(
                                        fieldErrors["slug"]?.let { com.beauty.app.ui.i18n.localizedMessage(it) } ?: stringResource(com.beauty.app.R.string.handle_help)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        viewModel.createOrganization(newOrgName, newOrgSlug) {
                                            creationLink = ""
                                            newOrgName = ""
                                            newOrgSlug = ""
                                        }
                                    },
                                    enabled = newOrgName.isNotBlank() && !viewModel.creating,
                                    colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                                ) { Text(if (viewModel.creating) stringResource(com.beauty.app.R.string.creating) else stringResource(com.beauty.app.R.string.create_organization)) }
                                TextButton(
                                    onClick = { viewModel.clearCreationLink() },
                                    enabled = !viewModel.creating
                                ) { Text(stringResource(com.beauty.app.R.string.cancel), color = TextMuted) }
                            }
                        }
                    }
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
                item { SectionTitle(stringResource(com.beauty.app.R.string.members_of, current.name)) }
                item { JoinLinkCard(current.slug) }
                items(viewModel.members, key = { it.userId }) { member ->
                    MemberRow(
                        member = member,
                        onApprove = { viewModel.approve(current.id, member.userId) },
                onDecline = { viewModel.decline(current.id, member.userId) },
                        onRemove = { viewModel.remove(current.id, member.userId) },
                        onRevoke = if (member.userId != viewModel.currentUserId) ({ viewModel.revoke(current.id, member.userId) }) else null,
                        onRestore = { viewModel.restore(current.id, member.userId) },
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
                        stringResource(com.beauty.app.R.string.members_removal_help),
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = inviteEmail,
                            onValueChange = { inviteEmail = it },
                            label = { Text(stringResource(com.beauty.app.R.string.invite_by_email)) },
                            supportingText = { Text(stringResource(com.beauty.app.R.string.they_need_an_account_already)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { viewModel.invite(current.id, inviteEmail, "ORG_USER"); inviteEmail = "" },
                            enabled = inviteEmail.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                        ) { Text(stringResource(com.beauty.app.R.string.send_invitation)) }
                    }
                }

                // -- Invite links --------------------------------------------
                item { SectionTitle(stringResource(com.beauty.app.R.string.invite_links_title)) }
                item {
                    InviteLinksCard(
                        issuedUrl = viewModel.issuedInviteUrl,
                        links = viewModel.inviteLinks,
                        onGenerate = { viewModel.issueInviteLink(current.id) },
                        onRevoke = { viewModel.revokeInviteLink(current.id, it) }
                    )
                }

                // -- Activity ------------------------------------------------
                item { SectionTitle(stringResource(com.beauty.app.R.string.org_activity)) }
                if (viewModel.auditEvents.isEmpty()) {
                    item {
                        OutlinedButton(
                            onClick = { viewModel.loadAudit(current.id) },
                            enabled = !viewModel.auditLoading
                        ) { Text(stringResource(com.beauty.app.R.string.org_activity_show)) }
                    }
                } else {
                    items(viewModel.auditEvents, key = { "audit-${it.id}" }) { event -> AuditRow(event) }
                    if (viewModel.auditHasMore) {
                        item {
                            TextButton(
                                onClick = { viewModel.loadAudit(current.id, more = true) },
                                enabled = !viewModel.auditLoading
                            ) { Text(stringResource(com.beauty.app.R.string.org_activity_load_more), color = RoseGoldPrimary) }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onLogout) { Text(stringResource(com.beauty.app.R.string.sign_out), color = TextMuted) }
                    if (viewModel.isSuperAdmin && onOpenAdmin != null) {
                        TextButton(onClick = onOpenAdmin) { Text(stringResource(com.beauty.app.R.string.admin_panel), color = RoseGoldPrimary) }
                    }
                }
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
private fun Banner(message: String, isError: Boolean, localize: Boolean = true) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else CardSurface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            if (localize) com.beauty.app.ui.i18n.localizedMessage(message) else message,
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
                if (org.isAdmin) "${org.slug} · ${com.beauty.app.ui.i18n.roleLabel(org.role)}" else org.slug,
                color = TextMuted,
                fontSize = 12.sp
            )
        }
        val pending = org.pendingRequestCount ?: 0
        if (pending > 0) {
            val description = stringResource(com.beauty.app.R.string.org_pending_requests, pending)
            Badge(
                containerColor = RoseGoldPrimary,
                modifier = Modifier.clearAndSetSemantics { contentDescription = description }
            ) {
                Text(if (pending > 99) "99+" else pending.toString(), color = Color.Black)
            }
        }
    }
}

/** "Declined — you may ask again after …", with the date in the device's locale. */
@Composable
private fun declinedMessage(org: OrganizationDto): String {
    val retryAfter = org.retryAfter?.let(::parseServerDateTime)
    return if (retryAfter == null || retryAfter.time <= System.currentTimeMillis()) {
        stringResource(com.beauty.app.R.string.org_declined_can_retry, org.name)
    } else {
        stringResource(com.beauty.app.R.string.org_declined_until, org.name, localizedIsoDateTime(org.retryAfter))
    }
}

/**
 * The handle as a shareable link. Signing up through it files an access
 * request for an admin to approve; the link by itself grants nothing.
 */
@Composable
private fun JoinLinkCard(slug: String) {
    val clipboard = LocalClipboardManager.current
    val link = remember(slug) { "${BuildConfig.APP_WEB_BASE_URL.trimEnd('/')}/?join=${Uri.encode(slug)}" }
    var copied by remember(slug) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(link, color = TextMuted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(link)); copied = true }) {
                Text(stringResource(com.beauty.app.R.string.org_copy_join_link))
            }
            if (copied) Text(stringResource(com.beauty.app.R.string.org_join_link_copied), color = RoseGoldPrimary, fontSize = 12.sp)
        }
        Text(stringResource(com.beauty.app.R.string.org_join_link_hint), color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun AuditRow(event: AuditEventDto) {
    val unknown = stringResource(com.beauty.app.R.string.org_activity_unknown_user)
    val actor = event.actorName ?: unknown
    val target = event.targetName ?: unknown
    val role = event.detail?.let { com.beauty.app.ui.i18n.roleLabel(it) } ?: ""
    val text = when (event.action) {
        "ORG_CREATED" -> stringResource(com.beauty.app.R.string.audit_org_created, actor)
        "JOIN_REQUESTED" -> stringResource(com.beauty.app.R.string.audit_join_requested, actor)
        "APPROVED" -> stringResource(com.beauty.app.R.string.audit_approved, actor, target)
        "DECLINED" -> stringResource(com.beauty.app.R.string.audit_declined, actor, target)
        "INVITED" -> stringResource(com.beauty.app.R.string.audit_invited, actor, target)
        "INVITATION_ACCEPTED" -> stringResource(com.beauty.app.R.string.audit_invitation_accepted, actor)
        "ROLE_CHANGED" -> stringResource(com.beauty.app.R.string.audit_role_changed, actor, target, role)
        "REMOVED" -> stringResource(com.beauty.app.R.string.audit_removed, actor, target)
        "REVOKED" -> stringResource(com.beauty.app.R.string.audit_revoked, actor, target)
        "RESTORED" -> stringResource(com.beauty.app.R.string.audit_restored, actor, target)
        "INVITE_LINK_CREATED" -> stringResource(com.beauty.app.R.string.audit_invite_link_created, actor)
        "INVITE_LINK_REVOKED" -> stringResource(com.beauty.app.R.string.audit_invite_link_revoked, actor)
        "INVITE_LINK_ACCEPTED" -> stringResource(com.beauty.app.R.string.audit_invite_link_accepted, actor)
        else -> event.action
    }
    val time = localizedIsoDateTime(event.createdAt)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text, fontSize = 13.sp)
        Text(time, color = TextMuted, fontSize = 12.sp)
    }
}

/**
 * "Join this organization?" for an admin's invite link. Opening the link
 * joins nothing; only [onJoin] does, and then with no approval to wait for.
 */
@Composable
private fun InviteOfferDialog(
    offer: OrganizationViewModel.InviteOffer,
    onJoin: () -> Unit,
    onDismiss: () -> Unit
) {
    val status = offer.status
    val name = offer.organizationName.orEmpty()
    AlertDialog(
        onDismissRequest = { if (status != OrganizationViewModel.InviteStatus.JOINING) onDismiss() },
        title = {
            Text(
                if (status == OrganizationViewModel.InviteStatus.READY || status == OrganizationViewModel.InviteStatus.JOINING)
                    stringResource(com.beauty.app.R.string.invite_accept_title, name)
                else stringResource(com.beauty.app.R.string.invite_accept_heading)
            )
        },
        text = {
            Text(
                when (status) {
                    OrganizationViewModel.InviteStatus.CHECKING -> stringResource(com.beauty.app.R.string.invite_accept_checking)
                    OrganizationViewModel.InviteStatus.INVALID -> stringResource(com.beauty.app.R.string.error_invite_link_invalid)
                    else -> stringResource(com.beauty.app.R.string.invite_accept_body, name)
                }
            )
        },
        confirmButton = {
            if (status == OrganizationViewModel.InviteStatus.INVALID) {
                TextButton(onClick = onDismiss) { Text(stringResource(com.beauty.app.R.string.invite_accept_close)) }
            } else {
                Button(
                    onClick = onJoin,
                    enabled = status == OrganizationViewModel.InviteStatus.READY,
                    colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                ) { Text(stringResource(com.beauty.app.R.string.invite_accept_join)) }
            }
        },
        dismissButton = {
            if (status != OrganizationViewModel.InviteStatus.INVALID) {
                TextButton(onClick = onDismiss, enabled = status != OrganizationViewModel.InviteStatus.JOINING) {
                    Text(stringResource(com.beauty.app.R.string.invite_accept_not_now), color = TextMuted)
                }
            }
        }
    )
}

/** Generates single-use invite links and lists the unused ones for revoking. */
@Composable
private fun InviteLinksCard(
    issuedUrl: String?,
    links: List<com.beauty.app.data.api.InviteLinkDto>,
    onGenerate: () -> Unit,
    onRevoke: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var copied by remember(issuedUrl) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(com.beauty.app.R.string.invite_links_hint), color = TextMuted, fontSize = 12.sp)
        Button(onClick = onGenerate, colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)) {
            Text(stringResource(com.beauty.app.R.string.invite_links_generate))
        }
        if (issuedUrl != null) {
            Text(issuedUrl, color = TextMuted, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(issuedUrl)); copied = true }) {
                    Text(stringResource(com.beauty.app.R.string.org_copy_join_link))
                }
                val shareTitle = stringResource(com.beauty.app.R.string.invite_links_share)
                OutlinedButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, issuedUrl)
                    context.startActivity(android.content.Intent.createChooser(send, shareTitle))
                }) { Text(shareTitle) }
                if (copied) Text(stringResource(com.beauty.app.R.string.org_join_link_copied), color = RoseGoldPrimary, fontSize = 12.sp)
            }
            Text(stringResource(com.beauty.app.R.string.invite_links_shown_once), color = TextMuted, fontSize = 12.sp)
        }
        links.forEach { link ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(com.beauty.app.R.string.invite_links_expires, link.createdByName, localizedIsoDateTime(link.expiresAt)),
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onRevoke(link.id) }) {
                    Text(stringResource(com.beauty.app.R.string.invite_links_revoke), color = RoseGoldPrimary)
                }
            }
        }
    }
}
