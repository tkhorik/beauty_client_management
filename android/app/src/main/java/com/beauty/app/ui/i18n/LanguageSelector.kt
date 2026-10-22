package com.beauty.app.ui.i18n

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.beauty.app.R
import com.beauty.app.i18n.LanguagePreferenceManager
import com.beauty.app.AppContainer

/** Shared selector for account settings and authentication screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSelector(
    accountId: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onSelected: (String?) -> Unit = {}
) {
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
    if (compact) {
        CompactLanguageSelector(
            currentPreference = preference,
            names = names,
            expanded = expanded,
            onExpandedChange = { expanded = it },
            onSelect = { value ->
                manager.select(accountId, value)
                onSelected(accountId)
            },
            modifier = modifier
        )
        return
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = modifier) {
        OutlinedTextField(
            value = names.getValue(preference), onValueChange = {}, readOnly = true,
            label = { Text(stringResource(R.string.language)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().menuAnchor()
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

/** A menu button is the appropriate language control on a transient auth form. */
@Composable
private fun CompactLanguageSelector(
    currentPreference: String,
    names: Map<String, String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier
) {
    Box(modifier = modifier) {
        TextButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                stateDescription = names.getValue(currentPreference)
            }
        ) {
            Icon(
                imageVector = Icons.Filled.Language,
                contentDescription = null
            )
            Spacer(Modifier.width(6.dp))
            Text(text = if (currentPreference == "system") stringResource(R.string.language) else currentPreference.uppercase())
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            names.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onExpandedChange(false)
                        onSelect(value)
                    },
                    trailingIcon = if (value == currentPreference) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    }
                )
            }
        }
    }
}
