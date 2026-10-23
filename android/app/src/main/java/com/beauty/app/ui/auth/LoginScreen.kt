package com.beauty.app.ui.auth

import androidx.compose.runtime.saveable.rememberSaveable
import com.beauty.app.ui.i18n.localizedMessage
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.ui.theme.*

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun LoginScreen(
    viewModel: AuthViewModel,
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToForgotPassword: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by viewModel::loginPassword
    var showPassword by remember { mutableStateOf(false) }
    val passwordFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val isLoading = viewModel.loginState is AuthViewModel.LoginState.Loading
    val signIn = { viewModel.login(email, password) }

    // Navigate on success
    LaunchedEffect(viewModel.loginState) {
        if (viewModel.loginState is AuthViewModel.LoginState.Success) {
            onLoginSuccess()
        }
    }

    // The AuthViewModel is shared with RegisterScreen, so an error left behind
    // by the other screen must not appear here.
    DisposableEffect(Unit) {
        onDispose { viewModel.resetState() }
    }

    AuraAuthLayout(
        title = stringResource(com.beauty.app.R.string.redesign_login_title),
        subtitle = stringResource(com.beauty.app.R.string.redesign_login_intro)
    ) {
        Text(stringResource(com.beauty.app.R.string.sign_in), style = MaterialTheme.typography.titleLarge, color = Forest)

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(com.beauty.app.R.string.email), color = Muted) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                autoCorrect = false,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { passwordFocusRequester.requestFocus() }
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .autofill(AutofillField.Email) { email = it },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Forest,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedTextColor = Forest,
                unfocusedTextColor = Forest
            )
        )

        // Password field
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(com.beauty.app.R.string.password), color = Muted) },
            visualTransformation = if (showPassword) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showPassword) stringResource(com.beauty.app.R.string.hide_password) else stringResource(com.beauty.app.R.string.show_password),
                        tint = Muted
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    if (!isLoading) signIn()
                }
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(passwordFocusRequester)
                .autofill(AutofillField.Password) { password = it },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Forest,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedTextColor = Forest,
                unfocusedTextColor = Forest
            )
        )

        // Error message
        if (viewModel.loginState is AuthViewModel.LoginState.Error) {
            Text(
                text = localizedMessage((viewModel.loginState as AuthViewModel.LoginState.Error).message),
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp
            )
        }

        // Submit button
        Button(
            onClick = signIn,
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            if (viewModel.loginState is AuthViewModel.LoginState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = stringResource(com.beauty.app.R.string.sign_in),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        TextButton(
            onClick = onNavigateToForgotPassword,
            enabled = !isLoading
        ) {
            Text(
                text = stringResource(com.beauty.app.R.string.forgot_password),
                color = Forest,
                fontSize = 13.sp
            )
        }

        TextButton(
            onClick = onNavigateToRegister,
            enabled = !isLoading
        ) {
            Text(
                text = stringResource(com.beauty.app.R.string.first_time_here_create_an_account),
                color = Forest,
                fontSize = 13.sp
            )
        }
    }
}
