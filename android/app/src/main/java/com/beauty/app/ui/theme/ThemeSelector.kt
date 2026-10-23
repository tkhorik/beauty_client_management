package com.beauty.app.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.beauty.app.AppContainer
import com.beauty.app.R

/** Device appearance selector; unlike language it is never associated with an account. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSelector(modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    val store = remember { AppContainer.themeStore(context) }
    var preference by remember { mutableStateOf(store.get()) }
    var expanded by remember { mutableStateOf(false) }
    val labels = mapOf(
        ThemePreference.SYSTEM to stringResource(R.string.theme_system),
        ThemePreference.LIGHT to stringResource(R.string.theme_light),
        ThemePreference.DARK to stringResource(R.string.theme_dark)
    )
    val appearanceLabel = stringResource(R.string.appearance)
    val select = { value: ThemePreference ->
        store.set(value)
        preference = value
        applyThemePreference(context, value)
    }
    if (compact) {
        Box(modifier) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = appearanceLabel; stateDescription = labels.getValue(preference) }
            ) {
                Icon(Icons.Filled.DarkMode, contentDescription = null)
                Text(labels.getValue(preference))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ThemePreference.entries.forEach { value ->
                    DropdownMenuItem(
                        text = { Text(labels.getValue(value)) },
                        onClick = { expanded = false; select(value) },
                        trailingIcon = if (value == preference) {{ Icon(Icons.Filled.Check, null) }} else null
                    )
                }
            }
        }
    } else {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = modifier) {
            OutlinedTextField(
                value = labels.getValue(preference), onValueChange = {}, readOnly = true,
                label = { Text(stringResource(R.string.appearance)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ThemePreference.entries.forEach { value ->
                    DropdownMenuItem(text = { Text(labels.getValue(value)) }, onClick = { expanded = false; select(value) })
                }
            }
        }
    }
}
