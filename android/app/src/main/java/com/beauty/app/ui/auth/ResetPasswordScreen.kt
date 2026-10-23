package com.beauty.app.ui.auth

import com.beauty.app.ui.i18n.localizedMessage
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.ui.theme.*
import com.beauty.app.ui.tokenFromWebAppLink

/** Sets a password from an App Link token, with manual pasting as a fallback. */
@Composable
fun ResetPasswordScreen(
    viewModel: AuthViewModel,
    onNavigateBackToLogin: () -> Unit,
    onRequestNewLink: () -> Unit,
    initialToken: String? = null,
    onTokenUsed: () -> Unit = {}
) {
    var link by viewModel::resetLink
    var newPassword by viewModel::resetNewPassword
    var confirmPassword by viewModel::resetConfirmPassword
    var showPassword by remember { mutableStateOf(false) }
    val state = viewModel.resetPasswordState
    val loading = state is AuthViewModel.ResetPasswordState.Loading
    val fieldErrors = (state as? AuthViewModel.ResetPasswordState.Error)?.fieldErrors.orEmpty()

    LaunchedEffect(state) {
        if (state is AuthViewModel.ResetPasswordState.Done) onTokenUsed()
    }

    AuraAuthLayout(
        title = stringResource(if (state is AuthViewModel.ResetPasswordState.Done) com.beauty.app.R.string.password_changed else com.beauty.app.R.string.choose_a_new_password)
    ) {
        if (state is AuthViewModel.ResetPasswordState.Done) {
            Text(
                text = stringResource(com.beauty.app.R.string.ui2_you_ve_been_signed_out_on_every_device_sign_in_with_your_new_pass),
                color = Muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Start
            )
            PrimaryButton(text = stringResource(com.beauty.app.R.string.sign_in_2), loading = false, onClick = onNavigateBackToLogin)
        } else {
            if (initialToken == null) {
                Text(
                    text = stringResource(com.beauty.app.R.string.reset_link_help),
                    color = Muted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Start
                )

                ResetField(
                    value = link,
                    onValueChange = { link = it },
                    label = stringResource(com.beauty.app.R.string.reset_link),
                    error = fieldErrors["link"],
                    enabled = !loading,
                    keyboardType = KeyboardType.Uri
                )
            } else {
                Text(stringResource(com.beauty.app.R.string.reset_link_received_choose_your_new_password), color = Muted)
                fieldErrors["link"]?.let { Text(localizedMessage(it), color = MaterialTheme.colorScheme.error) }
            }
            ResetField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = stringResource(com.beauty.app.R.string.new_password),
                error = fieldErrors["newPassword"],
                helper = stringResource(com.beauty.app.R.string.password_minimum, AuthValidation.PASSWORD_MIN_LENGTH),
                enabled = !loading,
                keyboardType = KeyboardType.Password,
                autofillField = AutofillField.NewPassword,
                hidden = !showPassword,
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showPassword) stringResource(com.beauty.app.R.string.hide_password) else stringResource(com.beauty.app.R.string.show_password),
                            tint = Muted
                        )
                    }
                }
            )
            ResetField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = stringResource(com.beauty.app.R.string.confirm_new_password),
                error = fieldErrors["confirmPassword"],
                enabled = !loading,
                keyboardType = KeyboardType.Password,
                autofillField = AutofillField.NewPassword,
                hidden = !showPassword
            )

            (state as? AuthViewModel.ResetPasswordState.Error)?.message?.let { message ->
                Text(text = localizedMessage(message), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            PrimaryButton(text = stringResource(com.beauty.app.R.string.set_new_password), loading = loading) {
                viewModel.resetPassword(
                    token = initialToken ?: tokenFromWebAppLink(link, "token", "/reset-password"),
                    newPassword = newPassword,
                    confirmPassword = confirmPassword
                )
            }

            if (fieldErrors.containsKey("link")) {
                TextButton(onClick = onRequestNewLink, enabled = !loading) {
                    Text(text = stringResource(com.beauty.app.R.string.request_a_new_link), color = Forest, fontSize = 13.sp)
                }
            }
            TextButton(onClick = onNavigateBackToLogin, enabled = !loading) {
                Text(text = stringResource(com.beauty.app.R.string.back_to_sign_in), color = Forest, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ResetField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    enabled: Boolean,
    keyboardType: KeyboardType,
    autofillField: AutofillField? = null,
    helper: String? = null,
    hidden: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = Muted) },
        isError = error != null,
        supportingText = (error ?: helper)?.let { text -> { Text(if (error != null) localizedMessage(text) else text) } },
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = trailingIcon,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                autofillField?.let { Modifier.autofill(it, onFill = onValueChange) } ?: Modifier
            ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Forest,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedTextColor = Forest,
            unfocusedTextColor = Forest
        )
    )
}

@Composable
private fun PrimaryButton(text: String, loading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !loading,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
        } else {
            Text(text = text, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}
