package com.beauty.app.ui.client

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.beauty.app.R
import com.beauty.app.ui.i18n.localizedMessage
import com.beauty.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditClientScreen(viewModel: EditClientViewModel, onBack: () -> Unit) {
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    val requestBack = {
        if (!viewModel.isSaving) {
            if (viewModel.isDirty) showDiscard = true else onBack()
        }
    }
    // Always intercept while this editor is visible, including during an in-flight save.
    BackHandler(onBack = requestBack)
    LaunchedEffect(viewModel.saveState) {
        if (viewModel.saveState is EditClientViewModel.SaveState.Success) onBack()
    }
    if (showDiscard && !viewModel.isSaving) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text(stringResource(R.string.client_editor_discard_title)) },
            text = { Text(stringResource(R.string.client_editor_discard_message)) },
            confirmButton = {
                TextButton(onClick = { showDiscard = false; onBack() }) {
                    Text(stringResource(R.string.client_editor_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }) {
                    Text(stringResource(R.string.client_editor_keep_editing))
                }
            }
        )
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (viewModel.isNewClient) R.string.new_client else R.string.edit_client), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = requestBack, enabled = !viewModel.isSaving) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper)
            )
        },
        bottomBar = {
            if (viewModel.isNewClient || viewModel.existingClientState is EditClientViewModel.ExistingClientState.Ready) {
                Surface(shadowElevation = 4.dp) {
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        (viewModel.saveState as? EditClientViewModel.SaveState.Error)?.let { error ->
                            Text(
                                localizedMessage(error.message),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                        Button(
                            onClick = viewModel::save,
                            enabled = viewModel.canEdit,
                            modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            if (viewModel.isSaving) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(when {
                                viewModel.isSaving -> R.string.saving
                                viewModel.isNewClient -> R.string.create_client
                                else -> R.string.save_changes
                            }))
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (viewModel.existingClientState) {
                EditClientViewModel.ExistingClientState.Loading -> CircularProgressIndicator()
                EditClientViewModel.ExistingClientState.Missing -> Text(
                    stringResource(R.string.ui2_this_client_is_no_longer_available_return_to_the_directory_and_re),
                    color = MaterialTheme.colorScheme.error
                )
                else -> Column(Modifier.widthIn(max = 680.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ClientForm(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClientForm(viewModel: EditClientViewModel) {
    var moreDetails by rememberSaveable { mutableStateOf(false) }
    val enabled = viewModel.canEdit
    AuraCard {
        OutlinedTextField(
            value = viewModel.name, onValueChange = viewModel::updateName,
            label = { Text(stringResource(R.string.full_name_2)) },
            enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )
        OutlinedTextField(
            value = viewModel.phone, onValueChange = viewModel::updatePhone,
            label = { Text(stringResource(R.string.phone_number)) },
            placeholder = { Text(stringResource(R.string.workspace_phone_example)) },
            enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next)
        )
        OutlinedTextField(
            value = viewModel.email, onValueChange = viewModel::updateEmail,
            label = { Text(stringResource(R.string.email)) },
            supportingText = { Text(stringResource(R.string.workspace_optional)) },
            enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )
    }
    AuraCard {
        TextButton(onClick = { moreDetails = !moreDetails }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.client_editor_more_details), Modifier.weight(1f))
            Icon(if (moreDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                stringResource(if (moreDetails) R.string.client_editor_collapse else R.string.client_editor_expand))
        }
        // Counts and labels remain visible even when optional fields are collapsed.
        Text(
            stringResource(R.string.client_editor_details_summary, viewModel.tags.size, viewModel.customFields.size),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (viewModel.tagInput.isNotEmpty() && !moreDetails) {
            Text(stringResource(R.string.client_editor_pending_tag, viewModel.tagInput), style = MaterialTheme.typography.bodySmall)
        }
        if (moreDetails) {
            AuraSectionLabel(stringResource(R.string.tags))
            OutlinedTextField(
                value = viewModel.tagInput, onValueChange = viewModel::updateTagInput,
                label = { Text(stringResource(R.string.add_tag)) },
                enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.addPendingTag() }),
                trailingIcon = {
                    TextButton(onClick = viewModel::addPendingTag, enabled = enabled && viewModel.tagInput.isNotBlank()) {
                        Text(stringResource(R.string.add_action))
                    }
                }
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                viewModel.tags.forEach { tag ->
                    InputChip(
                        selected = false, enabled = enabled, onClick = { viewModel.removeTag(tag) },
                        label = { Text(tag) },
                        trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.remove_tag), Modifier.size(18.dp)) },
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.custom_attributes), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = viewModel::addCustomField, enabled = enabled) { Text(stringResource(R.string.add)) }
            }
            viewModel.customFields.forEachIndexed { index, field ->
                key(field.id) {
                    AttributeTile(
                        name = field.key, value = field.value, enabled = enabled,
                        onNameChange = { viewModel.updateCustomField(index, it, field.value) },
                        onValueChange = { viewModel.updateCustomField(index, field.key, it) },
                        onRemove = { viewModel.removeCustomField(index) }
                    )
                }
            }
        }
    }
}
