package com.beauty.app.ui.auth

import androidx.compose.runtime.saveable.rememberSaveable
import com.beauty.app.ui.i18n.localizedMessage
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.ui.theme.CardSurface
import com.beauty.app.ui.theme.RoseGoldPrimary
import com.beauty.app.ui.theme.TextLight
import com.beauty.app.ui.theme.TextMuted

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Branching rather than returning early: an early return out of
                // an inline composable lambda leaves the composer's group
                // structure to the compiler to unwind, and there is no reason
                // to rely on that when an if/else reads the same.
                com.beauty.app.ui.i18n.LanguageSelector(accountId = null, modifier = Modifier.fillMaxWidth())
                if (state is AuthViewModel.ForgotPasswordState.Sent) {
                    SentConfirmation(
                        onNavigateBackToLogin = onNavigateBackToLogin,
                        onEnterResetLink = onEnterResetLink
                    )
                } else {
                    Text(
                        text = stringResource(com.beauty.app.R.string.reset_your_password),
                        color = RoseGoldPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                    Text(
                        text = stringResource(com.beauty.app.R.string.forgot_password_help),
                        color = TextMuted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(com.beauty.app.R.string.email), color = TextMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        isError = state is AuthViewModel.ForgotPasswordState.Error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .autofill(AutofillField.Email) { email = it },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RoseGoldPrimary,
                            unfocusedBorderColor = Color(0x33E5B899),
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight
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
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                    ) {
                        if (state is AuthViewModel.ForgotPasswordState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = stringResource(com.beauty.app.R.string.send_reset_link),
                                color = Color.Black,
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
                            color = RoseGoldPrimary,
                            fontSize = 13.sp
                        )
                    }

                    TextButton(
                        onClick = onNavigateBackToLogin,
                        enabled = state !is AuthViewModel.ForgotPasswordState.Loading
                    ) {
                        Text(
                            text = stringResource(com.beauty.app.R.string.back_to_sign_in),
                            color = RoseGoldPrimary,
                            fontSize = 13.sp
                        )
                    }
                }
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
        text = stringResource(com.beauty.app.R.string.check_your_email),
        color = RoseGoldPrimary,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp
    )
    Text(
        text = stringResource(com.beauty.app.R.string.forgot_password_sent),
        color = TextMuted,
        fontSize = 14.sp,
        textAlign = TextAlign.Center
    )
    Text(
        text = stringResource(com.beauty.app.R.string.forgot_password_spam),
        color = TextMuted,
        fontSize = 13.sp,
        textAlign = TextAlign.Center
    )

    Button(
        onClick = onEnterResetLink,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
    ) {
        Text(
            text = stringResource(com.beauty.app.R.string.paste_reset_link),
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }

    TextButton(onClick = onNavigateBackToLogin) {
        Text(text = stringResource(com.beauty.app.R.string.back_to_sign_in), color = RoseGoldPrimary, fontSize = 13.sp)
    }
}
