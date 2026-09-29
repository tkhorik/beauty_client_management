package com.beauty.app.ui.client

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import com.beauty.app.data.api.VisitAttachmentDto
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.beauty.app.sync.SyncWorker
import com.beauty.app.data.BeautyRepository
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientDetailScreen(viewModel: ClientDetailViewModel, repository: BeautyRepository, onBack: () -> Unit, onEdit: () -> Unit, onDeleted: () -> Unit = onBack) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showVisitForm by rememberSaveable { mutableStateOf(false) }
    var savedMessage by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var compareAttachments by remember { mutableStateOf<List<VisitAttachmentDto>?>(null) }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val client = viewModel.client
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(client?.name ?: "Client details") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { viewModel.refresh(); SyncWorker.enqueue(context) }, enabled = !viewModel.loading) {
                    Icon(Icons.Default.Refresh, "Refresh visit history")
                }
            }
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (viewModel.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (client != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(client.name, style = MaterialTheme.typography.headlineSmall)
                            Text(client.phone)
                            client.email?.takeIf { it.isNotBlank() }?.let { Text(it) }
                            val tags = remember(client.tagsJson) {
                                runCatching { Json.decodeFromString<List<String>>(client.tagsJson) }.getOrDefault(emptyList())
                            }
                            if (tags.isNotEmpty()) Text(tags.joinToString(" • "))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onEdit) { Text("Edit Client") }
                                Button(onClick = { viewModel.clearSaveError(); showVisitForm = true }) { Text("Log Visit") }
                            }
                            OutlinedButton(onClick = { confirmDelete = true }, enabled = !viewModel.deleting) {
                                Text("Delete Client", color = MaterialTheme.colorScheme.error)
                            }
                            viewModel.deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                val fields = runCatching { Json.parseToJsonElement(client.customFieldsJson).jsonObject }.getOrNull()
                if (!fields.isNullOrEmpty()) item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Client attributes", fontWeight = FontWeight.Bold)
                            fields.forEach { (key, value) -> Text("$key: ${(value as? JsonPrimitive)?.content ?: value}") }
                        }
                    }
                }
            } else if (!viewModel.loading) item { Text("This client is no longer available. Return to the directory and refresh.") }
            item { Text("Visit History", style = MaterialTheme.typography.titleLarge) }
            if (savedMessage) item {
                Text("Visit saved on this device. It will upload when connected.", color = MaterialTheme.colorScheme.primary)
            }
            viewModel.error?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { viewModel.refresh() }) { Text("Retry") }
            } }
            val extras = localHistoryExtras(viewModel.visits, viewModel.localVisits, !viewModel.historyLoaded || viewModel.error != null)
            // Sort by appointment time, not insertion time: backdated visits belong in their actual place.
            val rows = viewModel.visits.map {
                HistoryRow("remote_${it.id}", it.visitDateTime, it.durationMinutes, it.procedureNotes, it.status, null, it.attachments)
            } + extras.map {
                HistoryRow("local_${it.id}", it.visitDateTime, it.durationMinutes, it.procedureNotes, it.status,
                    if (it.isPendingSync) {
                        if (it.syncError == null) "Waiting to upload" else "Upload pending — tap Refresh to retry"
                    } else "Saved on this device", emptyList())
            }
            if (rows.isEmpty() && !viewModel.loading && viewModel.error == null) item {
                Text("No visits logged for this client yet.")
            }
            items(rows.sortedByDescending { it.dateTime }, key = { it.key }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(formatVisitDate(row.dateTime), fontWeight = FontWeight.Bold)
                        Text("${row.status.lowercase().replaceFirstChar { it.uppercase() }} • ${row.duration} minutes")
                        Text(row.notes)
                        row.syncLabel?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        if (row.attachments.isNotEmpty()) {
                            Text("Photos: " + row.attachments.joinToString(" · ") { it.tag }, color = MaterialTheme.colorScheme.primary)
                            TextButton(onClick = { compareAttachments = row.attachments }) {
                                Text(if (row.attachments.size >= 2) "View / compare photos" else "View photo")
                            }
                        }
                    }
                }
            }
        }
    }
    if (showVisitForm) {
        VisitForm(
            saving = viewModel.saving,
            error = viewModel.saveError,
            onDismiss = { if (!viewModel.saving) showVisitForm = false },
            onSave = { date, duration, notes, status, photos ->
                viewModel.saveVisitWithPhotos(date, duration, notes, status, {
                    showVisitForm = false
                    savedMessage = true
                    SyncWorker.enqueue(context)
                }, context, photos)
            }
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { if (!viewModel.deleting) confirmDelete = false },
        title = { Text("Delete client?") },
        text = { Text("This removes the client and its server history. Pending offline visits or photos must upload first.") },
        confirmButton = { TextButton(enabled = !viewModel.deleting, onClick = { viewModel.delete { confirmDelete = false; onDeleted() } }) { Text("Delete") } },
        dismissButton = { TextButton(enabled = !viewModel.deleting, onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
    compareAttachments?.let { attachments ->
        PhotoCompareDialog(repository, viewModel.organizationId, attachments, onDismiss = { compareAttachments = null })
    }
}

@Composable
private fun PhotoCompareDialog(repository: BeautyRepository, organizationId: String, attachments: List<VisitAttachmentDto>, onDismiss: () -> Unit) {
    val before = attachments.firstOrNull { it.tag == "BEFORE" } ?: attachments.first()
    val after = attachments.firstOrNull { it.tag == "AFTER" } ?: attachments.getOrNull(1) ?: attachments.first()
    var beforeBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var afterBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var split by remember { mutableFloatStateOf(0.5f) }
    var sideBySide by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(before.id, after.id) {
        beforeBitmap = runCatching { repository.downloadAttachment(organizationId, before.id).let { BitmapFactory.decodeByteArray(it, 0, it.size).asImageBitmap() } }.getOrNull()
        afterBitmap = runCatching { repository.downloadAttachment(organizationId, after.id).let { BitmapFactory.decodeByteArray(it, 0, it.size).asImageBitmap() } }.getOrNull()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Before & after") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !sideBySide, onClick = { sideBySide = false }, label = { Text("Slider") })
                FilterChip(selected = sideBySide, onClick = { sideBySide = true }, label = { Text("Side by side") })
            }
            if (beforeBitmap != null && afterBitmap != null) {
                if (sideBySide) Row(Modifier.fillMaxWidth().height(220.dp)) {
                    androidx.compose.foundation.Image(beforeBitmap!!, null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                    androidx.compose.foundation.Image(afterBitmap!!, null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxWidth().height(220.dp).clipToBounds()) {
                        androidx.compose.foundation.Image(afterBitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxHeight().fillMaxWidth(split).background(Color.Transparent).clipToBounds()) {
                            androidx.compose.foundation.Image(beforeBitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                    Slider(value = split, onValueChange = { split = it })
                }
            } else Text("Loading photos…")
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

private data class HistoryRow(val key: String, val dateTime: String, val duration: Int, val notes: String, val status: String, val syncLabel: String?, val attachments: List<VisitAttachmentDto> = emptyList())

private fun formatVisitDate(value: String): String = runCatching {
    val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(value)
    SimpleDateFormat("EEE, d MMM yyyy • HH:mm", Locale.getDefault()).format(requireNotNull(parsed))
}.getOrDefault(value)

@Composable
private fun VisitForm(
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, List<Pair<String, Uri>>) -> Unit
) {
    val context = LocalContext.current
    var timestamp by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var duration by rememberSaveable { mutableStateOf("60") }
    var notes by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("COMPLETED") }
    var before by remember { mutableStateOf<Uri?>(null) }
    var after by remember { mutableStateOf<Uri?>(null) }
    var pickingTag by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        when (pickingTag) { "BEFORE" -> before = uri; "AFTER" -> after = uri }
        pickingTag = null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log New Visit") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp)))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !saving, onClick = {
                        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
                        DatePickerDialog(context, { _, year, month, day ->
                            calendar.set(year, month, day)
                            timestamp = calendar.timeInMillis
                        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
                    }) { Text("Date") }
                    OutlinedButton(enabled = !saving, onClick = {
                        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
                        TimePickerDialog(context, { _, hour, minute ->
                            calendar.set(Calendar.HOUR_OF_DAY, hour)
                            calendar.set(Calendar.MINUTE, minute)
                            timestamp = calendar.timeInMillis
                        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
                    }) { Text("Time") }
                }
                OutlinedTextField(value = duration, onValueChange = { duration = it }, enabled = !saving,
                    label = { Text("Duration (minutes)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = notes, onValueChange = { notes = it }, enabled = !saving,
                    label = { Text("Procedure notes") }, minLines = 3)
                Text("Status")
                listOf("COMPLETED", "SCHEDULED", "CANCELLED").forEach { option ->
                    FilterChip(selected = status == option, onClick = { status = option }, enabled = !saving,
                        label = { Text(option.lowercase().replaceFirstChar { it.uppercase() }) })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Before & after photos (optional)", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !saving, onClick = { pickingTag = "BEFORE"; picker.launch("image/*") }) {
                        Icon(Icons.Default.AddAPhoto, null); Spacer(Modifier.width(4.dp)); Text(if (before == null) "Before" else "Before ✓")
                    }
                    OutlinedButton(enabled = !saving, onClick = { pickingTag = "AFTER"; picker.launch("image/*") }) {
                        Icon(Icons.Default.AddAPhoto, null); Spacer(Modifier.width(4.dp)); Text(if (after == null) "After" else "After ✓")
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:00", Locale.US).format(Date(timestamp))
            onSave(date, duration, notes, status, listOfNotNull(before?.let { "BEFORE" to it }, after?.let { "AFTER" to it }))
        }) { Text(if (saving) "Saving…" else "Save Visit") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } }
    )
}
