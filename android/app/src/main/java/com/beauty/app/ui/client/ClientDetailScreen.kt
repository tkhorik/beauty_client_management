package com.beauty.app.ui.client

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.api.VisitAttachmentDto
import com.beauty.app.sync.SyncWorker
import com.beauty.app.ui.theme.EmeraldStatus
import com.beauty.app.ui.theme.RoseGoldPrimary
import com.beauty.app.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ClientDetailScreen(
    viewModel: ClientDetailViewModel,
    repository: BeautyRepository,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit = onBack,
    /** Opens the visit form straight away, as the web's "Log Visit" buttons do. */
    openVisitForm: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showVisitForm by rememberSaveable { mutableStateOf(openVisitForm) }
    var savedMessage by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var editingAttributes by rememberSaveable { mutableStateOf(false) }
    var compareAttachments by remember { mutableStateOf<List<VisitAttachmentDto>?>(null) }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val client = viewModel.client
    val openVisit = { viewModel.clearSaveError(); showVisitForm = true }
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(client.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                                Text(
                                    "${client.totalVisits} Total Visits",
                                    color = RoseGoldPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text("Phone: ${client.phone}")
                            client.email?.takeIf { it.isNotBlank() }?.let { Text("Email: $it") }
                            val tags = remember(client.tagsJson) { decodeTags(client.tagsJson) }
                            if (tags.isNotEmpty()) FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) { tags.forEach { TagBadge(it) } }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onEdit) { Text("Edit Client") }
                                Button(onClick = openVisit) { Text("Log New Visit") }
                            }
                            OutlinedButton(onClick = { confirmDelete = true }, enabled = !viewModel.deleting) {
                                Text("Delete Client", color = MaterialTheme.colorScheme.error)
                            }
                            viewModel.deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                item {
                    AttributesCard(
                        customFieldsJson = client.customFieldsJson,
                        editing = editingAttributes,
                        saving = viewModel.savingAttributes,
                        error = viewModel.attributesError,
                        onStartEditing = { viewModel.clearAttributesError(); editingAttributes = true },
                        onCancel = { viewModel.clearAttributesError(); editingAttributes = false },
                        onSave = { fields -> viewModel.saveAttributes(fields) { editingAttributes = false } }
                    )
                }
            } else if (!viewModel.loading) item { Text("This client is no longer available. Return to the directory and refresh.") }
            item { Text("Visit History & Procedure Timeline", style = MaterialTheme.typography.titleLarge) }
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
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("No visit logs found for this client yet.", color = TextMuted)
                        if (client != null) Button(onClick = openVisit) { Text("Log First Visit") }
                    }
                }
            }
            items(rows.sortedByDescending { it.dateTime }, key = { it.key }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(formatVisitDate(row.dateTime), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                            VisitStatusBadge(row.status)
                        }
                        Text("Duration: ${row.duration} minutes", color = TextMuted, fontSize = 12.sp)
                        Text(row.notes)
                        row.syncLabel?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        if (row.attachments.isNotEmpty()) {
                            Text("Attachments (${row.attachments.size} Photos/Files):", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                row.attachments.forEach { attachment ->
                                    AttachmentThumbnail(repository, viewModel.organizationId, attachment) {
                                        compareAttachments = listOf(attachment)
                                    }
                                }
                            }
                            if (hasBeforeAndAfter(row.attachments)) {
                                Button(onClick = { compareAttachments = row.attachments }) { Text("Compare Before/After") }
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
        text = { Text("Are you sure you want to delete ${client?.name ?: "this client"} and all visit logs? Pending offline visits or photos must upload first.") },
        confirmButton = { TextButton(enabled = !viewModel.deleting, onClick = { viewModel.delete { confirmDelete = false; onDeleted() } }) { Text("Delete") } },
        dismissButton = { TextButton(enabled = !viewModel.deleting, onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
    compareAttachments?.let { attachments ->
        PhotoCompareDialog(repository, viewModel.organizationId, attachments, onDismiss = { compareAttachments = null })
    }
}

/**
 * The web detail view's "Dynamic Custom Client Attributes" panel: read-only
 * until "Edit Attributes", then removable rows plus an add row.
 *
 * Unchanged values keep their original JSON element, so a number or boolean
 * written elsewhere is not silently turned into a string by an unrelated edit.
 * New values are strings, as on the web.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttributesCard(
    customFieldsJson: String,
    editing: Boolean,
    saving: Boolean,
    error: String?,
    onStartEditing: () -> Unit,
    onCancel: () -> Unit,
    onSave: (JsonObject) -> Unit
) {
    val original = remember(customFieldsJson) {
        runCatching { Json.parseToJsonElement(customFieldsJson).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    }
    // Draft state lives while editing and is re-seeded from the saved record each time editing starts.
    val draft = remember(customFieldsJson, editing) { mutableStateListOf<Pair<String, JsonElement>>().apply { addAll(original.entries.map { it.key to it.value }) } }
    var newKey by rememberSaveable(editing) { mutableStateOf("") }
    var newValue by rememberSaveable(editing) { mutableStateOf("") }
    val shown = if (editing) draft.toList() else original.entries.map { it.key to it.value }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Custom Client Attributes", fontWeight = FontWeight.Bold, color = RoseGoldPrimary, modifier = Modifier.weight(1f))
                if (!editing) {
                    TextButton(onClick = onStartEditing) { Text("Edit Attributes") }
                }
            }
            if (shown.isEmpty()) Text("No attributes yet.", color = TextMuted, fontSize = 13.sp)
            shown.forEach { (key, value) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0x33E5B899), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(key.uppercase(), color = TextMuted, fontSize = 11.sp)
                        Text((value as? JsonPrimitive)?.content ?: value.toString(), fontWeight = FontWeight.SemiBold)
                    }
                    if (editing) IconButton(enabled = !saving, onClick = { draft.removeAll { it.first == key } }) {
                        Icon(Icons.Default.Delete, "Remove $key", tint = Color(0xFFF87171))
                    }
                }
            }
            if (editing) {
                OutlinedTextField(value = newKey, onValueChange = { newKey = it }, enabled = !saving, singleLine = true,
                    label = { Text("Attribute name (e.g. Skin Tone)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = newValue, onValueChange = { newValue = it }, enabled = !saving, singleLine = true,
                    label = { Text("Value (e.g. Warm Olive)") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(enabled = !saving && newKey.isNotBlank(), onClick = {
                    val key = newKey.trim()
                    val index = draft.indexOfFirst { it.first == key }
                    val entry = key to JsonPrimitive(newValue.trim())
                    if (index >= 0) draft[index] = entry else draft.add(entry)
                    newKey = ""
                    newValue = ""
                }) { Text("+ Add Attribute") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !saving, onClick = { onSave(JsonObject(draft.toMap())) }) {
                        Text(if (saving) "Saving…" else "Save Changes")
                    }
                    OutlinedButton(enabled = !saving, onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}

private data class HistoryRow(val key: String, val dateTime: String, val duration: Int, val notes: String, val status: String, val syncLabel: String?, val attachments: List<VisitAttachmentDto> = emptyList())

private fun formatVisitDate(value: String): String = runCatching {
    val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(value)
    SimpleDateFormat("EEE, d MMM yyyy • HH:mm", Locale.getDefault()).format(requireNotNull(parsed))
}.getOrDefault(value)

@OptIn(ExperimentalLayoutApi::class)
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
        if (uri != null) when (pickingTag) { "BEFORE" -> before = uri; "AFTER" -> after = uri }
        pickingTag = null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Procedure Visit Entry") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Date & Time *", color = TextMuted, fontSize = 12.sp)
                Text(SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp)), fontWeight = FontWeight.SemiBold)
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
                    label = { Text("Duration (mins)") }, singleLine = true,
                    supportingText = { Text("In 15-minute steps") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("Visit Status", color = TextMuted, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("COMPLETED", "SCHEDULED", "CANCELLED").forEach { option ->
                        FilterChip(selected = status == option, onClick = { status = option }, enabled = !saving,
                            label = { Text(option.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp) })
                    }
                }
                OutlinedTextField(value = notes, onValueChange = { notes = it }, enabled = !saving,
                    label = { Text("Procedure details & formula notes *") },
                    placeholder = { Text("Lash mapping, dye formula ratios, laser intensity, skin treatment details…") },
                    minLines = 3)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Attach procedure media (compressed before upload)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhotoSlot("BEFORE", RoseGoldPrimary, before, !saving, Modifier.weight(1f),
                        onPick = { pickingTag = "BEFORE"; picker.launch("image/*") }, onRemove = { before = null })
                    PhotoSlot("AFTER", EmeraldStatus, after, !saving, Modifier.weight(1f),
                        onPick = { pickingTag = "AFTER"; picker.launch("image/*") }, onRemove = { after = null })
                }
            }
        },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:00", Locale.US).format(Date(timestamp))
            onSave(date, duration, notes, status, listOfNotNull(before?.let { "BEFORE" to it }, after?.let { "AFTER" to it }))
        }) { Text(if (saving) "Saving Visit Record…" else "Log Visit Record") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } }
    )
}

/** One of the web form's BEFORE/AFTER boxes: a picker until chosen, then a preview with a remove button. */
@Composable
private fun PhotoSlot(
    tag: String,
    color: Color,
    uri: Uri?,
    enabled: Boolean,
    modifier: Modifier,
    onPick: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    var preview by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        preview = uri?.let { selected ->
            withContext(Dispatchers.IO) {
                runCatching {
                    // A preview only needs a few hundred pixels; decoding the
                    // full camera image here would cost tens of megabytes.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(selected)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 300) sample *= 2
                    context.contentResolver.openInputStream(selected)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    }?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Column(
        modifier
            .border(1.dp, Color(0x33E5B899), RoundedCornerShape(12.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("$tag PHOTO", color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (uri == null) {
            Column(
                Modifier.fillMaxWidth().height(96.dp).clickable(enabled = enabled, onClick = onPick),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.AddAPhoto, null, tint = color)
                Text("Upload ${tag.lowercase().replaceFirstChar { it.uppercase() }} Photo", fontSize = 11.sp, color = TextMuted)
            }
        } else {
            Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(8.dp))) {
                preview?.let { Image(it, "$tag photo preview", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Text("Selected", fontSize = 11.sp, color = TextMuted, modifier = Modifier.align(Alignment.Center))
                IconButton(
                    onClick = onRemove,
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp).clip(CircleShape)
                ) {
                    Surface(color = Color(0xB3000000), shape = CircleShape) {
                        Icon(Icons.Default.Close, "Remove $tag photo", tint = Color.White, modifier = Modifier.padding(4.dp).size(14.dp))
                    }
                }
            }
        }
    }
}
