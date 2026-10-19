package com.beauty.app.ui.client

import androidx.compose.ui.res.pluralStringResource
import com.beauty.app.ui.i18n.localizedMessage
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
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
                Text(stringResource(com.beauty.app.R.string.aura_beauty_log), style = MaterialTheme.typography.titleLarge)
                Text(organizationName, style = MaterialTheme.typography.labelMedium)
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
                                Text(if (pendingRequestCount > 99) "99+" else pendingRequestCount.toString(), color = Color.Black)
                            }
                        }) else null,
                        onClick = { menu = false; onOrganizations() }
                    )
                    DropdownMenuItem(text = { Text(stringResource(com.beauty.app.R.string.account_settings)) }, onClick = { menu = false; onSettings() })
                    if (onAdmin != null) DropdownMenuItem(text = { Text(stringResource(com.beauty.app.R.string.admin_panel)) }, onClick = { menu = false; onAdmin() })
                    DropdownMenuItem(
                        text = { Text(stringResource(com.beauty.app.R.string.about)) },
                        trailingIcon = if (hasActiveUpdate) ({ Badge(containerColor = RoseGoldPrimary) { Text(stringResource(com.beauty.app.R.string.new_badge), color = Color.Black) } }) else null,
                        onClick = { menu = false; onAbout() }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(com.beauty.app.R.string.sign_out)) }, onClick = { menu = false; onLogout() })
                }
            }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).pullRefresh(pull)) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
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
                    // Equal halves left "Записать посещение" wrapping onto two lines
                    // in a taller pill. The longer label sizes its own button and
                    // "New client" takes the rest, so both stay one line high.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onNewClient,
                            enabled = !viewModel.blocked,
                            contentPadding = HeaderButtonPadding,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) { Text(stringResource(com.beauty.app.R.string.new_client), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        OutlinedButton(
                            onClick = { chooseClient = true },
                            enabled = !viewModel.blocked,
                            contentPadding = HeaderButtonPadding,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(stringResource(com.beauty.app.R.string.log_visit), maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
                        Modifier.bleed(16.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
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
                    Text(stringResource(com.beauty.app.R.string.client_directory_count, viewModel.filtered.size), style = MaterialTheme.typography.titleMedium)
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
    // Card(onClick) rather than Modifier.clickable, so the ripple is clipped
    // to the rounded shape instead of flashing a rectangle.
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, Color(0x14FFFFFF))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(client.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Phone, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                        Text(client.phone, color = TextMuted, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Surface(color = Color(0x1AE5B899), shape = RoundedCornerShape(12.dp)) {
                    Text(
                        pluralStringResource(com.beauty.app.R.plurals.visits_count, client.totalVisits, client.totalVisits),
                        color = RoseGoldPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            if (tags.isNotEmpty()) FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) { tags.forEach { TagBadge(it) } }
            if (attributes.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0x33E5B899), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.Info, null, tint = RoseGoldPrimary, modifier = Modifier.size(12.dp))
                        Text(stringResource(com.beauty.app.R.string.custom_specs_attributes), color = RoseGoldPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                    }
                    // Value right-aligned against the edge, as on the web card.
                    attributes.forEach { (key, value) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("$key:", color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(
                                value,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = Color(0x0DFFFFFF))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(com.beauty.app.R.string.updated_date, formatUpdatedDate(client.updatedAt)), color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onLogVisit, enabled = logVisitEnabled, contentPadding = PaddingValues(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                    Text(stringResource(com.beauty.app.R.string.log_visit), fontSize = 12.sp, maxLines = 1)
                    Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private val HeaderButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

/**
 * Widens a horizontally scrolling row by [horizontal] on each side, under the
 * list's own content padding, so chips scroll out to the screen edge instead
 * of being cut off 16dp short of it.
 */
private fun Modifier.bleed(horizontal: Dp) = layout { measurable, constraints ->
    val extra = horizontal.roundToPx()
    val width = constraints.maxWidth + extra * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}
