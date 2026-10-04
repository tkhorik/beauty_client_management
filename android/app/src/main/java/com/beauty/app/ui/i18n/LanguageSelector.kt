package com.beauty.app.ui.i18n

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.beauty.app.R
import com.beauty.app.i18n.LanguagePreferenceManager
import com.beauty.app.AppContainer

/** Shared selector for account settings and authentication screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSelector(accountId: String?, modifier: Modifier = Modifier, onSelected: (String?) -> Unit = {}) {
    val context = LocalContext.current
    val manager = remember { AppContainer.languageManager(context) }
    var expanded by remember { mutableStateOf(false) }
    manager.changeCounter
    val preference = manager.current(accountId).preference
    val names = mapOf(
        "system" to stringResource(R.string.language_system),
        "en" to stringResource(R.string.language_english),
        "ru" to stringResource(R.string.language_russian)
    )
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = modifier) {
        OutlinedTextField(
            value = names.getValue(preference), onValueChange = {}, readOnly = true,
            label = { Text(stringResource(R.string.language)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            names.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    expanded = false; manager.select(accountId, value); onSelected(accountId)
                })
            }
        }
    }
}
