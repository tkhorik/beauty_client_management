package com.beauty.app.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree

/**
 * Registers a Compose text field with Android's Autofill Framework.
 *
 * Compose's text fields otherwise expose no credential type, which makes
 * password managers fall back to unreliable label heuristics. The framework
 * forwards these hints to the active service (such as 1Password or Google
 * Password Manager) and anchors its suggestion to the focused field.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.credentialAutofill(
    types: List<AutofillType>,
    onFill: (String) -> Unit
): Modifier {
    val autofill = LocalAutofill.current
    val autofillTree = LocalAutofillTree.current
    val currentOnFill by rememberUpdatedState(onFill)
    val node = remember(types) {
        AutofillNode(autofillTypes = types) { value -> currentOnFill(value) }
    }

    SideEffect { autofillTree += node }

    return this
        .onGloballyPositioned { coordinates -> node.boundingBox = coordinates.boundsInWindow() }
        .onFocusChanged { state ->
            if (state.isFocused) {
                autofill?.requestAutofillForNode(node)
            } else {
                autofill?.cancelAutofillForNode(node)
            }
        }
}
