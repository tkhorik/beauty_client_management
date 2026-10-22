package com.beauty.app.ui.auth

import androidx.compose.runtime.saveable.rememberSaveable
import com.beauty.app.ui.i18n.localizedMessage
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.ui.theme.*

/**
 * Requests a password-reset link.
 *
 * This screen only *starts* the flow. The link it triggers points at the web
 * app; the user can finish there or paste the link into [ResetPasswordScreen].
 * There is no Android deep link — see `ForgotPasswordRequest`.
 *
 * The confirmation is deliberately non-committal ("if an account exists"). The
 * backend refuses to reveal whether the address is registered, and a screen
 * that said "check your inbox" with any more confidence would leak exactly what
 * the backend withholds.
 */
@Composable
fun ForgotPasswordScreen(
    viewModel: AuthViewModel,
    onNavigateBackToLogin: () -> Unit,
    onEnterResetLink: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    val state = viewModel.forgotPasswordState

    // The AuthViewModel is shared across the auth screens, so state left behind
    // here must not follow the user back to the login form.
    DisposableEffect(Unit) {
        onDispose { viewModel.resetState() }
    }

    AuraAuthLayout(
        title = stringResource(if (state is AuthViewModel.ForgotPasswordState.Sent) com.beauty.app.R.string.check_your_email else com.beauty.app.R.string.reset_your_password),
        subtitle = if (state is AuthViewModel.ForgotPasswordState.Sent) null else stringResource(com.beauty.app.R.string.forgot_password_help)
    ) {
        // Branching rather than returning early: an early return out of
        // an inline composable lambda leaves the composer's group
        // structure to the compiler to unwind, and there is no reason
        // to rely on that when an if/else reads the same.
        if (state is AuthViewModel.ForgotPasswordState.Sent) {
            SentConfirmation(
                onNavigateBackToLogin = onNavigateBackToLogin,
                onEnterResetLink = onEnterResetLink
            )
        } else {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(com.beauty.app.R.string.email), color = Muted) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                isError = state is AuthViewModel.ForgotPasswordState.Error,
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

            if (state is AuthViewModel.ForgotPasswordState.Error) {
                Text(
                    text = localizedMessage(state.message),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
            }

            Button(
                onClick = { viewModel.forgotPassword(email) },
                enabled = state !is AuthViewModel.ForgotPasswordState.Loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Forest)
            ) {
                if (state is AuthViewModel.ForgotPasswordState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = WhiteCard,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = stringResource(com.beauty.app.R.string.send_reset_link),
                        color = WhiteCard,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }

            TextButton(
                onClick = onEnterResetLink,
                enabled = state !is AuthViewModel.ForgotPasswordState.Loading
            ) {
                Text(
                    text = stringResource(com.beauty.app.R.string.already_have_a_reset_link),
                    color = Forest,
                    fontSize = 13.sp
                )
            }

            TextButton(
                onClick = onNavigateBackToLogin,
                enabled = state !is AuthViewModel.ForgotPasswordState.Loading
            ) {
                Text(
                    text = stringResource(com.beauty.app.R.string.back_to_sign_in),
                    color = Forest,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/**
 * Shown once the request has been accepted.
 *
 * Every word here is hedged on purpose. The backend will not say whether the
 * address has an account, so neither can this: "if an account exists" is the
 * strongest claim that can honestly be made, and a friendlier "we've sent you
 * an email" would be both a lie and an enumeration oracle.
 */
@Composable
private fun ColumnScope.SentConfirmation(onNavigateBackToLogin: () -> Unit, onEnterResetLink: () -> Unit) {
    Text(
        text = stringResource(com.beauty.app.R.string.forgot_password_sent),
        color = Muted,
        fontSize = 14.sp,
        textAlign = TextAlign.Start
    )
    Text(
        text = stringResource(com.beauty.app.R.string.forgot_password_spam),
        color = Muted,
        fontSize = 13.sp,
        textAlign = TextAlign.Start
    )

    Button(
        onClick = onEnterResetLink,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Forest)
    ) {
        Text(
            text = stringResource(com.beauty.app.R.string.paste_reset_link),
            color = WhiteCard,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }

    TextButton(onClick = onNavigateBackToLogin) {
        Text(text = stringResource(com.beauty.app.R.string.back_to_sign_in), color = Forest, fontSize = 13.sp)
    }
}
