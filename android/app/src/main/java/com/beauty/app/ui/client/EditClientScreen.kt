package com.beauty.app.ui.client

import com.beauty.app.ui.i18n.localizedMessage
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.ui.theme.RoseGoldPrimary
import com.beauty.app.ui.theme.TextLight
import com.beauty.app.ui.theme.TextMuted

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EditClientScreen(
    viewModel: EditClientViewModel,
    onBack: () -> Unit
) {
    var tagInput by remember { mutableStateOf("") }

    // Navigate back on success
    LaunchedEffect(viewModel.saveState) {
        if (viewModel.saveState is EditClientViewModel.SaveState.Success) {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (viewModel.isNewClient) stringResource(com.beauty.app.R.string.new_client) else stringResource(com.beauty.app.R.string.edit_client),
                        color = RoseGoldPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(com.beauty.app.R.string.back),
                            tint = TextLight
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F0E13)
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (viewModel.existingClientState) {
                EditClientViewModel.ExistingClientState.Loading ->
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = RoseGoldPrimary)
                    }
                EditClientViewModel.ExistingClientState.Missing ->
                    Text(
                        stringResource(com.beauty.app.R.string.ui2_this_client_is_no_longer_available_return_to_the_directory_and_re),
                        color = MaterialTheme.colorScheme.error
                    )
                // Branches rather than early `return@Column`s: returning out of an
                // inline composable lambda skips closing its group and crashes the
                // slot table.
                else -> ClientForm(viewModel, tagInput, onTagInputChange = { tagInput = it })
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.ClientForm(
    viewModel: EditClientViewModel,
    tagInput: String,
    onTagInputChange: (String) -> Unit
) {
        // Full Name
        OutlinedTextField(
            value = viewModel.name,
            onValueChange = { viewModel.updateName(it) },
            label = { Text(stringResource(com.beauty.app.R.string.full_name_2), color = TextMuted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = outlinedColors()
        )

        // Phone
        OutlinedTextField(
            value = viewModel.phone,
            onValueChange = { viewModel.updatePhone(it) },
            label = { Text(stringResource(com.beauty.app.R.string.phone_number), color = TextMuted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = outlinedColors()
        )

        // Email
        OutlinedTextField(
            value = viewModel.email,
            onValueChange = { viewModel.updateEmail(it) },
            label = { Text(stringResource(com.beauty.app.R.string.email_optional), color = TextMuted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = outlinedColors()
        )

        // Tags section
        Text(stringResource(com.beauty.app.R.string.tags), color = RoseGoldPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = tagInput,
                onValueChange = onTagInputChange,
                label = { Text(stringResource(com.beauty.app.R.string.add_tag), color = TextMuted) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = outlinedColors()
            )
            OutlinedButton(
                onClick = { viewModel.addTag(tagInput.trim()); onTagInputChange("") },
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(RoseGoldPrimary)
                )
            ) { Text(stringResource(com.beauty.app.R.string.add_action), color = RoseGoldPrimary) }
        }
        // Existing tags as chips
        if (viewModel.tags.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 4,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                viewModel.tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(tag, color = TextLight, fontSize = 12.sp) },
                        trailingIcon = {
                            IconButton(
                                onClick = { viewModel.removeTag(tag) },
                                modifier = Modifier.size(16.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(com.beauty.app.R.string.remove_tag),
                                    tint = TextMuted,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = InputChipDefaults.inputChipColors(
                            containerColor = Color(0x22E5B899)
                        )
                    )
                }
            }
        }

        // Custom fields section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(com.beauty.app.R.string.custom_attributes), color = RoseGoldPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            OutlinedButton(
                onClick = { viewModel.addCustomField() },
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(RoseGoldPrimary)
                )
            ) { Text(stringResource(com.beauty.app.R.string.add), color = RoseGoldPrimary, fontSize = 12.sp) }
        }
        viewModel.customFields.forEachIndexed { index, field ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = field.key,
                    onValueChange = { viewModel.updateCustomField(index, it, field.value) },
                    label = { Text(stringResource(com.beauty.app.R.string.attribute), color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = outlinedColors()
                )
                OutlinedTextField(
                    value = field.value,
                    onValueChange = { viewModel.updateCustomField(index, field.key, it) },
                    label = { Text(stringResource(com.beauty.app.R.string.value_label), color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = outlinedColors()
                )
                IconButton(onClick = { viewModel.removeCustomField(index) }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(com.beauty.app.R.string.remove), tint = Color(0xFFf87171))
                }
            }
        }

        // Error
        if (viewModel.saveState is EditClientViewModel.SaveState.Error) {
            Text(
                text = localizedMessage((viewModel.saveState as EditClientViewModel.SaveState.Error).message),
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Save button
        Button(
            onClick = { viewModel.save() },
            enabled = viewModel.saveState !is EditClientViewModel.SaveState.Loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
        ) {
            if (viewModel.saveState is EditClientViewModel.SaveState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.Black,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = if (viewModel.isNewClient) stringResource(com.beauty.app.R.string.create_client) else stringResource(com.beauty.app.R.string.save_changes),
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
}

@Composable
private fun outlinedColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = RoseGoldPrimary,
    unfocusedBorderColor = Color(0x33E5B899),
    focusedTextColor = TextLight,
    unfocusedTextColor = TextLight
)
