package com.beauty.app.ui.client

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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.pullrefresh.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.local.ClientEntity
import com.beauty.app.sync.SyncWorker
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
    onOrganizations: () -> Unit,
    onAdmin: (() -> Unit)?,
    onLogout: () -> Unit
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
    val pull = rememberPullRefreshState(viewModel.refreshing, refresh)
    Scaffold(topBar = {
        TopAppBar(title = {
            Column {
                Text("Aura Beauty Log", style = MaterialTheme.typography.titleLarge)
                Text(organizationName, style = MaterialTheme.typography.labelMedium)
            }
        }, actions = {
            IconButton(onClick = refresh, enabled = !viewModel.refreshing) { Icon(Icons.Default.Refresh, "Refresh clients") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Account and organization menu") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Organizations & members") }, onClick = { menu = false; onOrganizations() })
                    DropdownMenuItem(text = { Text("Account settings") }, onClick = { menu = false; onSettings() })
                    if (onAdmin != null) DropdownMenuItem(text = { Text("Admin panel") }, onClick = { menu = false; onAdmin() })
                    DropdownMenuItem(text = { Text("Sign out") }, onClick = { menu = false; onLogout() })
                }
            }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).pullRefresh(pull)) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                item { VerificationBanner(repository = repository) }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onNewClient, enabled = !viewModel.blocked, modifier = Modifier.weight(1f)) { Text("New Client") }
                        OutlinedButton(onClick = { chooseClient = true }, enabled = !viewModel.blocked, modifier = Modifier.weight(1f)) { Text("Log Visit") }
                    }
                }
                item {
                    OutlinedTextField(value = viewModel.query, onValueChange = viewModel::updateQuery,
                        label = { Text("Search name, phone, email or tag") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
                }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("", "VIP", "Sensitive Skin", "Lash Extensions", "Hair Coloring", "Skin Treatment").forEach { tag ->
                            FilterChip(selected = viewModel.tag == tag, onClick = { viewModel.updateTag(tag) }, label = { Text(tag.ifEmpty { "All" }) })
                        }
                    }
                }
                if (viewModel.query.isNotBlank() || viewModel.tag.isNotEmpty()) item {
                    TextButton(onClick = viewModel::clearFilters) { Text("Clear filters") }
                }
                if (viewModel.searching || viewModel.refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    Text("Client Directory · ${viewModel.filtered.size}", style = MaterialTheme.typography.titleMedium)
                    Text(viewModel.message ?: if (viewModel.lastRefresh == 0L) "Loading saved clients…" else "Synced with web", style = MaterialTheme.typography.bodySmall)
                    if (viewModel.message != null) TextButton(onClick = refresh) { Text("Retry") }
                }
                if (viewModel.filtered.isEmpty() && !viewModel.refreshing && !viewModel.searching && !viewModel.blocked) item {
                    Text(if (viewModel.query.isNotBlank() || viewModel.tag.isNotEmpty()) "No clients match these filters."
                        else if (viewModel.message != null) "No cached clients are available. Connect and refresh."
                        else "No client profiles yet. Create your first client to get started.")
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
    if (chooseClient) AlertDialog(onDismissRequest = { chooseClient = false }, title = { Text("Choose a client") }, text = {
        if (viewModel.clients.isEmpty()) Text("Create a client before logging a visit.") else LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(viewModel.clients, key = { it.id }) { client ->
                TextButton(onClick = { chooseClient = false; onLogVisit(client.id) }, modifier = Modifier.fillMaxWidth()) { Text("${client.name} · ${client.phone}") }
            }
        }
    }, confirmButton = {
        if (viewModel.clients.isEmpty()) TextButton(onClick = { chooseClient = false; onNewClient() }) { Text("New Client") }
        else TextButton(onClick = { chooseClient = false }) { Text("Cancel") }
    })
}

/** Mirrors `ClientCard.tsx`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClientCard(client: ClientEntity, logVisitEnabled: Boolean, onClick: () -> Unit, onLogVisit: () -> Unit) {
    val tags = remember(client.tagsJson) { decodeTags(client.tagsJson) }
    val attributes = remember(client.customFieldsJson) { attributeSummary(client.customFieldsJson) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(client.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(client.phone, color = TextMuted, fontSize = 13.sp)
                }
                Surface(color = Color(0x1AE5B899), shape = RoundedCornerShape(12.dp)) {
                    Text(
                        visitsLabel(client.totalVisits),
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
                    Text("CUSTOM SPECS & ATTRIBUTES", color = RoseGoldPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    attributes.forEach { (key, value) ->
                        Row {
                            Text("$key:", color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(
                                value,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = Color(0x0DFFFFFF))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Updated ${formatUpdatedDate(client.updatedAt)}", color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onLogVisit, enabled = logVisitEnabled, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Log Visit ›", fontSize = 12.sp)
                }
            }
        }
    }
}
