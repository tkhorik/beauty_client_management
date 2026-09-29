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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.beauty.app.sync.SyncWorker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientDetailScreen(viewModel: ClientDetailViewModel, onBack: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showVisitForm by rememberSaveable { mutableStateOf(false) }
    var savedMessage by rememberSaveable { mutableStateOf(false) }
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
                HistoryRow("remote_${it.id}", it.visitDateTime, it.durationMinutes, it.procedureNotes, it.status, null)
            } + extras.map {
                HistoryRow("local_${it.id}", it.visitDateTime, it.durationMinutes, it.procedureNotes, it.status,
                    if (it.isPendingSync) {
                        if (it.syncError == null) "Waiting to upload" else "Upload pending — tap Refresh to retry"
                    } else "Saved on this device")
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
            onSave = { date, duration, notes, status ->
                viewModel.saveVisit(date, duration, notes, status) {
                    showVisitForm = false
                    savedMessage = true
                    SyncWorker.enqueue(context)
                }
            }
        )
    }
}

private data class HistoryRow(val key: String, val dateTime: String, val duration: Int, val notes: String, val status: String, val syncLabel: String?)

private fun formatVisitDate(value: String): String = runCatching {
    val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(value)
    SimpleDateFormat("EEE, d MMM yyyy • HH:mm", Locale.getDefault()).format(requireNotNull(parsed))
}.getOrDefault(value)

@Composable
private fun VisitForm(
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    val context = LocalContext.current
    var timestamp by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var duration by rememberSaveable { mutableStateOf("60") }
    var notes by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("COMPLETED") }
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
            }
        },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:00", Locale.US).format(Date(timestamp))
            onSave(date, duration, notes, status)
        }) { Text(if (saving) "Saving…" else "Save Visit") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } }
    )
}
