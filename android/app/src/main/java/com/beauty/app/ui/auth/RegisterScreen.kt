package com.beauty.app.ui.auth

import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.ui.theme.*

@Composable
fun RegisterScreen(
    viewModel: AuthViewModel,
    onRegisterSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit,
    /**
     * False when the user arrived with an organization-creation link: they
     * are about to create their own organization, and asking to join someone
     * else's on the same form would only confuse that.
     */
    offerOrganizationField: Boolean = true
) {
    var fullName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by viewModel::registerPassword
    var confirmPassword by viewModel::registerConfirmPassword
    var organizationSlug by viewModel::registerOrganizationSlug
    var showPassword by remember { mutableStateOf(false) }

    val state = viewModel.registerState
    val errorState = state as? AuthViewModel.RegisterState.Error
    val fieldErrors = errorState?.fieldErrors ?: emptyMap()
    val isLoading = state is AuthViewModel.RegisterState.Loading

    LaunchedEffect(state) {
        if (state is AuthViewModel.RegisterState.Success) {
            onRegisterSuccess()
        }
    }

    // Stale errors from a previous attempt on the other screen would otherwise
    // greet the user the moment this one opens.
    DisposableEffect(Unit) {
        onDispose { viewModel.resetState() }
    }

    AuraAuthLayout(
        title = stringResource(com.beauty.app.R.string.create_your_account),
        subtitle = stringResource(com.beauty.app.R.string.redesign_register_intro)
    ) {
        AuthTextField(
            value = fullName,
            onValueChange = { fullName = it },
            label = stringResource(com.beauty.app.R.string.full_name),
            error = fieldErrors["fullName"],
            keyboardType = KeyboardType.Text,
            autofillField = AutofillField.FullName
        )

        AuthTextField(
            value = email,
            onValueChange = { email = it },
            label = stringResource(com.beauty.app.R.string.email),
            error = fieldErrors["email"],
            keyboardType = KeyboardType.Email,
            autofillField = AutofillField.Email
        )

        AuthTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(com.beauty.app.R.string.password),
            error = fieldErrors["password"],
            // Guidance shown up front, so the rule is not discovered
            // only by having the form rejected.
            helper = stringResource(com.beauty.app.R.string.password_minimum, AuthValidation.PASSWORD_MIN_LENGTH),
            keyboardType = KeyboardType.Password,
            autofillField = AutofillField.NewPassword,
            isPassword = true,
            showPassword = showPassword,
            onToggleShowPassword = { showPassword = !showPassword }
        )

        AuthTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = stringResource(com.beauty.app.R.string.confirm_password),
            error = fieldErrors["confirmPassword"],
            keyboardType = KeyboardType.Password,
            autofillField = AutofillField.NewPassword,
            isPassword = true,
            showPassword = showPassword,
            onToggleShowPassword = { showPassword = !showPassword }
        )

        if (offerOrganizationField) {
            AuthTextField(
                value = organizationSlug,
                onValueChange = { organizationSlug = it },
                label = stringResource(com.beauty.app.R.string.register_organization_handle),
                error = fieldErrors["organizationSlug"],
                helper = stringResource(com.beauty.app.R.string.register_organization_handle_hint),
                keyboardType = KeyboardType.Ascii
            )
        }

        // Failures that belong to no single field.
        errorState?.message?.let { message ->
            Text(
                text = localizedMessage(message),
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp
            )
        }

        Button(
            onClick = { viewModel.register(email, password, confirmPassword, fullName, includeOrganization = offerOrganizationField) },
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = stringResource(com.beauty.app.R.string.create_account),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        TextButton(onClick = onNavigateToLogin, enabled = !isLoading) {
            Text(
                text = stringResource(com.beauty.app.R.string.already_have_an_account_sign_in),
                color = Forest,
                fontSize = 13.sp
            )
        }
    }
}

/**
 * One field definition shared by every auth input, so error rendering, colours
 * and the password toggle cannot drift apart between four near-identical
 * copies of `OutlinedTextField`.
 */
@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String? = null,
    helper: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    autofillField: AutofillField? = null,
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onToggleShowPassword: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, color = Muted) },
            isError = error != null,
            visualTransformation = if (isPassword && !showPassword) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            trailingIcon = if (isPassword && onToggleShowPassword != null) {
                {
                    IconButton(onClick = onToggleShowPassword) {
                        Icon(
                            imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showPassword) stringResource(com.beauty.app.R.string.hide_password) else stringResource(com.beauty.app.R.string.show_password),
                            tint = Muted
                        )
                    }
                }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            singleLine = true,
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

        when {
            error != null -> Text(
                text = localizedMessage(error),
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
            helper != null -> Text(
                text = helper,
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}
