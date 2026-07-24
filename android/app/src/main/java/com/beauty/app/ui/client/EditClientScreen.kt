package com.beauty.app.ui.client

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

@OptIn(ExperimentalMaterial3Api::class)
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
                        "Edit Client",
                        color = RoseGoldPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
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
            // Full Name
            OutlinedTextField(
                value = viewModel.name,
                onValueChange = { viewModel.updateName(it) },
                label = { Text("Full Name *", color = TextMuted) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = outlinedColors()
            )

            // Phone
            OutlinedTextField(
                value = viewModel.phone,
                onValueChange = { viewModel.updatePhone(it) },
                label = { Text("Phone Number *", color = TextMuted) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = outlinedColors()
            )

            // Email
            OutlinedTextField(
                value = viewModel.email,
                onValueChange = { viewModel.updateEmail(it) },
                label = { Text("Email (Optional)", color = TextMuted) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = outlinedColors()
            )

            // Tags section
            Text("Tags", color = RoseGoldPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    label = { Text("Add tag", color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = outlinedColors()
                )
                OutlinedButton(
                    onClick = { viewModel.addTag(tagInput.trim()); tagInput = "" },
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(RoseGoldPrimary)
                    )
                ) { Text("Add", color = RoseGoldPrimary) }
            }
            // Existing tags as chips
            if (viewModel.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                                        contentDescription = "Remove tag",
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
                Text("Custom Attributes", color = RoseGoldPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                OutlinedButton(
                    onClick = { viewModel.addCustomField() },
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(RoseGoldPrimary)
                    )
                ) { Text("+ Add", color = RoseGoldPrimary, fontSize = 12.sp) }
            }
            viewModel.customFields.forEachIndexed { index, (key, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { viewModel.updateCustomField(index, it, value) },
                        label = { Text("Attribute", color = TextMuted) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = outlinedColors()
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { viewModel.updateCustomField(index, key, it) },
                        label = { Text("Value", color = TextMuted) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = outlinedColors()
                    )
                    IconButton(onClick = { viewModel.removeCustomField(index) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color(0xFFf87171))
                    }
                }
            }

            // Error
            if (viewModel.saveState is EditClientViewModel.SaveState.Error) {
                Text(
                    text = (viewModel.saveState as EditClientViewModel.SaveState.Error).message,
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
                        text = "Save Changes",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun outlinedColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = RoseGoldPrimary,
    unfocusedBorderColor = Color(0x33E5B899),
    focusedTextColor = TextLight,
    unfocusedTextColor = TextLight
)
