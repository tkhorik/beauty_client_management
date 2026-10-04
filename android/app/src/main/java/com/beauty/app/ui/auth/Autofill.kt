package com.beauty.app.ui.auth

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree

/** The kinds of auth input a password manager should recognise. */
enum class AutofillField {
    Email, Password, NewPassword, FullName;

    @OptIn(ExperimentalComposeUiApi::class)
    internal val types: List<AutofillType>
        get() = when (this) {
            // The account id is an email address, so offer both: some managers
            // store it as the username, others as the email.
            Email -> listOf(AutofillType.EmailAddress, AutofillType.Username)
            Password -> listOf(AutofillType.Password)
            NewPassword -> listOf(AutofillType.NewPassword)
            FullName -> listOf(AutofillType.PersonFullName)
        }
}

/**
 * Exposes a text field to the Android autofill framework so password managers
 * (1Password, Google Password Manager, …) can offer and fill credentials.
 *
 * Compose 1.6 has no built-in autofill for text fields, so this registers an
 * [AutofillNode] by hand. Once the Compose BOM reaches 1.8+, replace it with
 * `Modifier.semantics { contentType = ContentType.… }`.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.autofill(
    field: AutofillField,
    onFill: (String) -> Unit
): Modifier = composed {
    val autofill = LocalAutofill.current
    val autofillTree = LocalAutofillTree.current
    val currentOnFill by rememberUpdatedState(onFill)
    val node = remember { AutofillNode(autofillTypes = field.types, onFill = { currentOnFill(it) }) }

    DisposableEffect(node) {
        autofillTree += node
        onDispose { autofillTree.children.remove(node.id) }
    }

    this
        .onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { focus ->
            autofill?.run {
                if (focus.isFocused) requestAutofillForNode(node) else cancelAutofillForNode(node)
            }
        }
}
