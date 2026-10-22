package com.beauty.app.ui.client

import androidx.compose.ui.res.pluralStringResource
import com.beauty.app.ui.i18n.localizedMessage
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import com.beauty.app.ui.theme.*
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.pullrefresh.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.local.ClientEntity
import com.beauty.app.sync.SyncWorker
import com.beauty.app.ui.theme.CardSurface
import com.beauty.app.ui.theme.RoseGoldPrimary
import com.beauty.app.ui.theme.TextMuted
import com.beauty.app.ui.verification.VerificationBanner

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, ExperimentalLayoutApi::class)
@Composable
fun ClientDirectoryScreen(
    viewModel: ClientDirectoryViewModel,
    repository: BeautyRepository,
    organizationName: String,
    onClientTap: (String) -> Unit,
    onNewClient: () -> Unit,
    onLogVisit: (String) -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onOrganizations: () -> Unit,
    /** Join requests awaiting this admin's approval in the active organization. */
    pendingRequestCount: Int = 0,
    onAdmin: (() -> Unit)?,
    onLogout: () -> Unit,
    updateManager: com.beauty.app.updater.UpdateManager? = null,
    onOpenUpdateDialog: () -> Unit = {}
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var menu by remember { mutableStateOf(false) }
    var chooseClient by remember { mutableStateOf(false) }
    val refresh = { viewModel.refresh(); SyncWorker.enqueue(context) }
    LaunchedEffect(viewModel) { refresh() }
    DisposableEffect(owner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val updateState by (updateManager?.state ?: kotlinx.coroutines.flow.MutableStateFlow(com.beauty.app.updater.UpdateState.Idle)).collectAsState()
    val hasActiveUpdate = updateState is com.beauty.app.updater.UpdateState.Available ||
        updateState is com.beauty.app.updater.UpdateState.Downloading ||
        updateState is com.beauty.app.updater.UpdateState.ReadyToInstall
    val pull = rememberPullRefreshState(viewModel.refreshing, refresh)

    Scaffold(topBar = {
        TopAppBar(title = {
            Column {
                Text(stringResource(com.beauty.app.R.string.aura_beauty_log), style = MaterialTheme.typography.titleMedium)
                Text(organizationName, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }, actions = {
            IconButton(onClick = refresh, enabled = !viewModel.refreshing) { Icon(Icons.Default.Refresh, stringResource(com.beauty.app.R.string.refresh_clients)) }
            Box {
                IconButton(onClick = { menu = true }) {
                    // The dot points at "About" once an update is waiting, so the
                    // menu is discoverable even after the banner scrolls away.
                    BadgedBox(badge = { if (hasActiveUpdate || pendingRequestCount > 0) Badge(containerColor = RoseGoldPrimary) }) {
                        Icon(Icons.Default.MoreVert, if (hasActiveUpdate) stringResource(com.beauty.app.R.string.more_options_update_available) else stringResource(com.beauty.app.R.string.more_options))
                    }
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(com.beauty.app.R.string.organizations_members)) },
                        trailingIcon = if (pendingRequestCount > 0) ({
                            Badge(containerColor = RoseGoldPrimary) {
                                Text(if (pendingRequestCount > 99) "99+" else pendingRequestCount.toString(), color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }) else null,
                        onClick = { menu = false; onOrganizations() }
                    )
                    DropdownMenuItem(text = { Text(stringResource(com.beauty.app.R.string.account_settings)) }, onClick = { menu = false; onSettings() })
                    if (onAdmin != null) DropdownMenuItem(text = { Text(stringResource(com.beauty.app.R.string.admin_panel)) }, onClick = { menu = false; onAdmin() })
                    DropdownMenuItem(
                        text = { Text(stringResource(com.beauty.app.R.string.about)) },
                        trailingIcon = if (hasActiveUpdate) ({ Badge(containerColor = RoseGoldPrimary) { Text(stringResource(com.beauty.app.R.string.new_badge), color = MaterialTheme.colorScheme.onPrimary) } }) else null,
                        onClick = { menu = false; onAbout() }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(com.beauty.app.R.string.sign_out)) }, onClick = { menu = false; onLogout() })
                }
            }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).pullRefresh(pull)) {
            LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.widthIn(max = 760.dp).fillMaxSize().align(Alignment.TopCenter)) {
                item { VerificationBanner(repository = repository) }
                if (hasActiveUpdate) {
                    item {
                        com.beauty.app.ui.updater.UpdateBanner(
                            state = updateState,
                            onTap = onOpenUpdateDialog
                        )
                    }
                }
                item {
                    AuraPageHeader(
                        title = stringResource(com.beauty.app.R.string.workspace_clients),
                        subtitle = stringResource(com.beauty.app.R.string.workspace_clients_subtitle)
                    )
                    Spacer(Modifier.height(20.dp))
                    val fontScale = LocalDensity.current.fontScale
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth >= 340.dp && fontScale <= 1.15f) {
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onNewClient, enabled = !viewModel.blocked,
                                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 52.dp)) {
                                    Text(stringResource(com.beauty.app.R.string.new_client))
                                }
                                OutlinedButton(onClick = { chooseClient = true }, enabled = !viewModel.blocked,
                                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 52.dp)) {
                                    Text(stringResource(com.beauty.app.R.string.log_visit))
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onNewClient, enabled = !viewModel.blocked,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                    Text(stringResource(com.beauty.app.R.string.new_client))
                                }
                                OutlinedButton(onClick = { chooseClient = true }, enabled = !viewModel.blocked,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text(stringResource(com.beauty.app.R.string.log_visit))
                                }
                            }
                        }
                    }
                }
                item {
                    // A placeholder, not a floating label: the label is long enough
                    // to wrap onto a second line inside the field.
                    OutlinedTextField(
                        value = viewModel.query,
                        onValueChange = viewModel::updateQuery,
                        placeholder = { Text(stringResource(com.beauty.app.R.string.search_name_phone_email_or_tag), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = if (viewModel.query.isNotEmpty()) ({
                            IconButton(onClick = { viewModel.updateQuery("") }) { Icon(Icons.Default.Close, stringResource(com.beauty.app.R.string.clear_filters)) }
                        }) else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Row(
                        Modifier.bleed(20.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(selected = viewModel.tag.isEmpty(), onClick = { viewModel.updateTag("") }, label = { Text(stringResource(com.beauty.app.R.string.all)) })
                        viewModel.tagChips.forEach { (tag, count) ->
                            FilterChip(
                                selected = viewModel.tag.equals(tag, ignoreCase = true),
                                onClick = { viewModel.updateTag(tag) },
                                label = {
                                    Text(tag)
                                    if (count > 0) Text(" $count", color = LocalContentColor.current.copy(alpha = 0.6f))
                                }
                            )
                        }
                    }
                }
                if (viewModel.query.isNotBlank() || viewModel.tag.isNotEmpty()) item {
                    TextButton(onClick = viewModel::clearFilters) { Text(stringResource(com.beauty.app.R.string.clear_filters)) }
                }
                if (viewModel.searching || viewModel.refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    Text(stringResource(com.beauty.app.R.string.client_directory_count, viewModel.filtered.size), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        viewModel.message?.let { localizedMessage(it) } ?: if (viewModel.lastRefresh == 0L) stringResource(com.beauty.app.R.string.loading_saved_clients) else stringResource(com.beauty.app.R.string.synced_with_web),
                        color = if (viewModel.message != null) MaterialTheme.colorScheme.error else TextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (viewModel.message != null) TextButton(onClick = refresh) { Text(stringResource(com.beauty.app.R.string.ui2_retry)) }
                }
                if (viewModel.filtered.isEmpty() && !viewModel.refreshing && !viewModel.searching && !viewModel.blocked) item {
                    Text(if (viewModel.query.isNotBlank() || viewModel.tag.isNotEmpty()) stringResource(com.beauty.app.R.string.no_clients_match_these_filters)
                        else if (viewModel.message != null) stringResource(com.beauty.app.R.string.no_cached_clients_are_available_connect_and_refresh)
                        else stringResource(com.beauty.app.R.string.no_client_profiles_yet_create_your_first_client_to_get_))
                }
                items(viewModel.filtered, key = { it.id }) { client ->
                    ClientCard(
                        client = client,
                        logVisitEnabled = !viewModel.blocked,
                        onClick = { onClientTap(client.id) },
                        onLogVisit = { onLogVisit(client.id) }
                    )
                }
            }
            PullRefreshIndicator(viewModel.refreshing, pull, Modifier.align(Alignment.TopCenter))
        }
    }
    // The header's "Log Visit" needs a client first, as the web form's client
    // dropdown does; choosing one opens that client's visit form directly.
    if (chooseClient) AlertDialog(onDismissRequest = { chooseClient = false }, title = { Text(stringResource(com.beauty.app.R.string.choose_a_client)) }, text = {
        if (viewModel.clients.isEmpty()) Text(stringResource(com.beauty.app.R.string.create_a_client_before_logging_a_visit)) else LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(viewModel.clients, key = { it.id }) { client ->
                ListItem(
                    headlineContent = { Text(client.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(client.phone, color = TextMuted) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = TextMuted) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { chooseClient = false; onLogVisit(client.id) }
                )
            }
        }
    }, confirmButton = {
        if (viewModel.clients.isEmpty()) TextButton(onClick = { chooseClient = false; onNewClient() }) { Text(stringResource(com.beauty.app.R.string.new_client)) }
        else TextButton(onClick = { chooseClient = false }) { Text(stringResource(com.beauty.app.R.string.cancel)) }
    })
}

/** Mirrors `ClientCard.tsx`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClientCard(client: ClientEntity, logVisitEnabled: Boolean, onClick: () -> Unit, onLogVisit: () -> Unit) {
    val tags = remember(client.tagsJson) { decodeTags(client.tagsJson) }
    val attributes = remember(client.customFieldsJson) { attributeSummary(client.customFieldsJson) }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = WhiteCard),
        border = BorderStroke(1.dp, Border)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).background(Sage, CircleShape), contentAlignment = Alignment.Center) {
                    Text(remember(client.name) { initials(client.name) },
                        color = Forest, style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(client.name, style = MaterialTheme.typography.titleLarge)
                    Text(client.phone, color = Muted, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.Default.ChevronRight, null, tint = Muted, modifier = Modifier.size(20.dp))
            }
            if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { TagBadge(it) }
            }
            if (attributes.isNotEmpty()) {
                Surface(color = Paper, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(stringResource(com.beauty.app.R.string.workspace_client_details), color = Muted, style = MaterialTheme.typography.labelMedium)
                        attributes.forEach { (key, value) ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(key, color = Muted, style = MaterialTheme.typography.bodySmall)
                                Text(value, color = Forest, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = Border)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(pluralStringResource(com.beauty.app.R.plurals.visits_count, client.totalVisits, client.totalVisits),
                        style = MaterialTheme.typography.labelLarge, color = Forest)
                    Text(stringResource(com.beauty.app.R.string.updated_date, formatUpdatedDate(client.updatedAt)),
                        color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
                OutlinedButton(onClick = onLogVisit, enabled = logVisitEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(com.beauty.app.R.string.log_visit))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * Widens a horizontally scrolling row by [horizontal] on each side, under the
 * list's own content padding, so chips scroll out to the screen edge instead
 * of being cut off short of it.
 */
private fun Modifier.bleed(horizontal: Dp) = layout { measurable, constraints ->
    val extra = horizontal.roundToPx()
    val width = constraints.maxWidth + extra * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}
