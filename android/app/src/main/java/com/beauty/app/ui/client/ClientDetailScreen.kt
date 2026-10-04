package com.beauty.app.ui.client

import androidx.compose.ui.res.pluralStringResource
import com.beauty.app.ui.i18n.localizedMessage
import androidx.compose.ui.res.stringResource
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
    val waitingToUpload = stringResource(com.beauty.app.R.string.waiting_to_upload)
    val uploadPending = stringResource(com.beauty.app.R.string.upload_pending_tap_refresh_to_retry)
    val savedOnDevice = stringResource(com.beauty.app.R.string.saved_on_this_device)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(client?.name ?: stringResource(com.beauty.app.R.string.client_details)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(com.beauty.app.R.string.back)) } },
            actions = {
                IconButton(onClick = { viewModel.refresh(); SyncWorker.enqueue(context) }, enabled = !viewModel.loading) {
                    Icon(Icons.Default.Refresh, stringResource(com.beauty.app.R.string.refresh_visit_history))
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
                                    pluralStringResource(com.beauty.app.R.plurals.total_visits, client.totalVisits, client.totalVisits),
                                    color = RoseGoldPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(stringResource(com.beauty.app.R.string.phone_value, client.phone))
                            client.email?.takeIf { it.isNotBlank() }?.let { Text(stringResource(com.beauty.app.R.string.email_value, it)) }
                            val tags = remember(client.tagsJson) { decodeTags(client.tagsJson) }
                            if (tags.isNotEmpty()) FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) { tags.forEach { TagBadge(it) } }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onEdit) { Text(stringResource(com.beauty.app.R.string.edit_client)) }
                                Button(onClick = openVisit) { Text(stringResource(com.beauty.app.R.string.log_new_visit)) }
                            }
                            OutlinedButton(onClick = { confirmDelete = true }, enabled = !viewModel.deleting) {
                                Text(stringResource(com.beauty.app.R.string.delete_client), color = MaterialTheme.colorScheme.error)
                            }
                            viewModel.deleteError?.let { Text(localizedMessage(it), color = MaterialTheme.colorScheme.error) }
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
            } else if (!viewModel.loading) item { Text(stringResource(com.beauty.app.R.string.ui2_this_client_is_no_longer_available_return_to_the_directory_and_re)) }
            item { Text(stringResource(com.beauty.app.R.string.visit_history_procedure_timeline), style = MaterialTheme.typography.titleLarge) }
            if (savedMessage) item {
                Text(stringResource(com.beauty.app.R.string.ui2_visit_saved_on_this_device_it_will_upload_when_connected), color = MaterialTheme.colorScheme.primary)
            }
            viewModel.error?.let { message -> item {
                Text(localizedMessage(message), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { viewModel.refresh() }) { Text(stringResource(com.beauty.app.R.string.ui2_retry)) }
            } }
            val extras = localHistoryExtras(viewModel.visits, viewModel.localVisits, !viewModel.historyLoaded || viewModel.error != null)
            // Sort by appointment time, not insertion time: backdated visits belong in their actual place.
            val rows = viewModel.visits.map {
                HistoryRow("remote_${it.id}", it.visitDateTime, it.durationMinutes, it.procedureNotes, it.status, null, it.attachments)
            } + extras.map {
                HistoryRow("local_${it.id}", it.visitDateTime, it.durationMinutes, it.procedureNotes, it.status,
                    if (it.isPendingSync) {
                        if (it.syncError == null) waitingToUpload else uploadPending
                    } else savedOnDevice, emptyList())
            }
            if (rows.isEmpty() && !viewModel.loading && viewModel.error == null) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(stringResource(com.beauty.app.R.string.ui2_no_visit_logs_found_for_this_client_yet), color = TextMuted)
                        if (client != null) Button(onClick = openVisit) { Text(stringResource(com.beauty.app.R.string.log_first_visit)) }
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
                        Text(pluralStringResource(com.beauty.app.R.plurals.visit_duration, row.duration, row.duration), color = TextMuted, fontSize = 12.sp)
                        Text(row.notes)
                        row.syncLabel?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        if (row.attachments.isNotEmpty()) {
                            Text(pluralStringResource(com.beauty.app.R.plurals.attachments_count, row.attachments.size, row.attachments.size), color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                                Button(onClick = { compareAttachments = row.attachments }) { Text(stringResource(com.beauty.app.R.string.compare_before_after)) }
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
        title = { Text(stringResource(com.beauty.app.R.string.delete_client_2)) },
        text = { Text(stringResource(com.beauty.app.R.string.delete_client_confirmation, client?.name ?: stringResource(com.beauty.app.R.string.this_client))) },
        confirmButton = { TextButton(enabled = !viewModel.deleting, onClick = { viewModel.delete { confirmDelete = false; onDeleted() } }) { Text(stringResource(com.beauty.app.R.string.delete)) } },
        dismissButton = { TextButton(enabled = !viewModel.deleting, onClick = { confirmDelete = false }) { Text(stringResource(com.beauty.app.R.string.cancel)) } }
    )
    compareAttachments?.let { attachments ->
        PhotoCompareDialog(repository, viewModel.organizationId, attachments, onDismiss = { compareAttachments = null })
    }
}

/**
 * The web detail view's "Dynamic Custom Client Attributes" panel: read-only
 * until "Edit Attributes", then editable/removable rows plus an add row.
 *
 * Unchanged values keep their original JSON element, so a number or boolean
 * written elsewhere is not silently turned into a string by an unrelated edit.
 * A value deliberately changed in this string-based form becomes a string, as
 * do newly added attributes.
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
                Text(stringResource(com.beauty.app.R.string.custom_client_attributes), fontWeight = FontWeight.Bold, color = RoseGoldPrimary, modifier = Modifier.weight(1f))
                if (!editing) {
                    TextButton(onClick = onStartEditing) { Text(stringResource(com.beauty.app.R.string.edit_attributes)) }
                }
            }
            if (shown.isEmpty()) Text(stringResource(com.beauty.app.R.string.no_attributes_yet), color = TextMuted, fontSize = 13.sp)
            shown.forEachIndexed { index, (key, value) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0x33E5B899), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        if (editing) {
                            OutlinedTextField(
                                value = key,
                                onValueChange = { updatedKey -> draft[index] = updatedKey to value },
                                enabled = !saving,
                                singleLine = true,
                                label = { Text(stringResource(com.beauty.app.R.string.attribute)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = (value as? JsonPrimitive)?.content ?: value.toString(),
                                onValueChange = { updatedValue -> draft[index] = key to JsonPrimitive(updatedValue) },
                                enabled = !saving,
                                singleLine = true,
                                label = { Text(stringResource(com.beauty.app.R.string.value_label)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(key.uppercase(), color = TextMuted, fontSize = 11.sp)
                            Text((value as? JsonPrimitive)?.content ?: value.toString(), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (editing) IconButton(enabled = !saving, onClick = { draft.removeAll { it.first == key } }) {
                        Icon(Icons.Default.Delete, stringResource(com.beauty.app.R.string.remove_attribute, key), tint = Color(0xFFF87171))
                    }
                }
            }
            if (editing) {
                OutlinedTextField(value = newKey, onValueChange = { newKey = it }, enabled = !saving, singleLine = true,
                    label = { Text(stringResource(com.beauty.app.R.string.attribute_name_e_g_skin_tone)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = newValue, onValueChange = { newValue = it }, enabled = !saving, singleLine = true,
                    label = { Text(stringResource(com.beauty.app.R.string.value_e_g_warm_olive)) }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(enabled = !saving && newKey.isNotBlank(), onClick = {
                    val key = newKey.trim()
                    val index = draft.indexOfFirst { it.first == key }
                    val entry = key to JsonPrimitive(newValue.trim())
                    if (index >= 0) draft[index] = entry else draft.add(entry)
                    newKey = ""
                    newValue = ""
                }) { Text(stringResource(com.beauty.app.R.string.add_attribute)) }
                error?.let { Text(localizedMessage(it), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !saving, onClick = {
                        onSave(JsonObject(draft.filter { it.first.isNotBlank() }.toMap()))
                    }) {
                        Text(if (saving) stringResource(com.beauty.app.R.string.saving) else stringResource(com.beauty.app.R.string.save_changes))
                    }
                    OutlinedButton(enabled = !saving, onClick = onCancel) { Text(stringResource(com.beauty.app.R.string.cancel)) }
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
    var before by rememberSaveable { mutableStateOf<Uri?>(null) }
    var after by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pickingTag by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) when (pickingTag) { "BEFORE" -> before = uri; "AFTER" -> after = uri }
        pickingTag = null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(com.beauty.app.R.string.log_procedure_visit_entry)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(com.beauty.app.R.string.date_time), color = TextMuted, fontSize = 12.sp)
                Text(SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp)), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !saving, onClick = {
                        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
                        DatePickerDialog(context, { _, year, month, day ->
                            calendar.set(year, month, day)
                            timestamp = calendar.timeInMillis
                        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
                    }) { Text(stringResource(com.beauty.app.R.string.date)) }
                    OutlinedButton(enabled = !saving, onClick = {
                        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
                        TimePickerDialog(context, { _, hour, minute ->
                            calendar.set(Calendar.HOUR_OF_DAY, hour)
                            calendar.set(Calendar.MINUTE, minute)
                            timestamp = calendar.timeInMillis
                        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
                    }) { Text(stringResource(com.beauty.app.R.string.time)) }
                }
                OutlinedTextField(value = duration, onValueChange = { duration = it }, enabled = !saving,
                    label = { Text(stringResource(com.beauty.app.R.string.duration_mins)) }, singleLine = true,
                    supportingText = { Text(stringResource(com.beauty.app.R.string.in_15_minute_steps)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text(stringResource(com.beauty.app.R.string.visit_status), color = TextMuted, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("COMPLETED", "SCHEDULED", "CANCELLED").forEach { option ->
                        FilterChip(selected = status == option, onClick = { status = option }, enabled = !saving,
                            label = { Text(com.beauty.app.ui.i18n.statusLabel(option), fontSize = 12.sp) })
                    }
                }
                OutlinedTextField(value = notes, onValueChange = { notes = it }, enabled = !saving,
                    label = { Text(stringResource(com.beauty.app.R.string.procedure_details_formula_notes)) },
                    placeholder = { Text(stringResource(com.beauty.app.R.string.lash_mapping_dye_formula_ratios_laser_intensity_skin_tr)) },
                    minLines = 3)
                error?.let { Text(localizedMessage(it), color = MaterialTheme.colorScheme.error) }
                Text(stringResource(com.beauty.app.R.string.attach_procedure_media_compressed_before_upload), fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
        }) { Text(if (saving) stringResource(com.beauty.app.R.string.saving_visit_record) else stringResource(com.beauty.app.R.string.log_visit_record)) } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(com.beauty.app.R.string.cancel)) } }
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
        Text(stringResource(com.beauty.app.R.string.photo_before) .let { if (tag == "BEFORE") it else stringResource(com.beauty.app.R.string.photo_after) }, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (uri == null) {
            Column(
                Modifier.fillMaxWidth().height(96.dp).clickable(enabled = enabled, onClick = onPick),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.AddAPhoto, null, tint = color)
                Text(if (tag == "BEFORE") stringResource(com.beauty.app.R.string.upload_before_photo) else stringResource(com.beauty.app.R.string.upload_after_photo), fontSize = 11.sp, color = TextMuted)
            }
        } else {
            Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(8.dp))) {
                preview?.let { Image(it, if (tag == "BEFORE") stringResource(com.beauty.app.R.string.preview_before_photo) else stringResource(com.beauty.app.R.string.preview_after_photo), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Text(stringResource(com.beauty.app.R.string.selected), fontSize = 11.sp, color = TextMuted, modifier = Modifier.align(Alignment.Center))
                IconButton(
                    onClick = onRemove,
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp).clip(CircleShape)
                ) {
                    Surface(color = Color(0xB3000000), shape = CircleShape) {
                        Icon(Icons.Default.Close, if (tag == "BEFORE") stringResource(com.beauty.app.R.string.remove_before_photo) else stringResource(com.beauty.app.R.string.remove_after_photo), tint = Color.White, modifier = Modifier.padding(4.dp).size(14.dp))
                    }
                }
            }
        }
    }
}
