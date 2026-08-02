package com.beauty.app.ui.auth

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
 * app, so the new password is typed in a browser rather than here — see
 * `ForgotPasswordRequest` for why an in-app reset form and an Android deep link
 * were both left out.
 *
 * The confirmation is deliberately non-committal ("if an account exists"). The
 * backend refuses to reveal whether the address is registered, and a screen
 * that said "check your inbox" with any more confidence would leak exactly what
 * the backend withholds.
 */
@Composable
fun ForgotPasswordScreen(
    viewModel: AuthViewModel,
    onNavigateBackToLogin: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    val state = viewModel.forgotPasswordState

    // The AuthViewModel is shared across the auth screens, so state left behind
    // here must not follow the user back to the login form.
    DisposableEffect(Unit) {
        onDispose { viewModel.resetState() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
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
                if (state is AuthViewModel.ForgotPasswordState.Sent) {
                    SentConfirmation(onNavigateBackToLogin = onNavigateBackToLogin)
                } else {
                    Text(
                        text = "Reset your password",
                        color = RoseGoldPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                    Text(
                        text = "Enter the email address on your account and we'll send you a " +
                            "link to choose a new password.",
                        color = TextMuted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email", color = TextMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        isError = state is AuthViewModel.ForgotPasswordState.Error,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RoseGoldPrimary,
                            unfocusedBorderColor = Color(0x33E5B899),
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight
                        )
                    )

                    if (state is AuthViewModel.ForgotPasswordState.Error) {
                        Text(
                            text = state.message,
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
                                text = "Send reset link",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }

                    TextButton(
                        onClick = onNavigateBackToLogin,
                        enabled = state !is AuthViewModel.ForgotPasswordState.Loading
                    ) {
                        Text(
                            text = "Back to sign in",
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
private fun ColumnScope.SentConfirmation(onNavigateBackToLogin: () -> Unit) {
    Text(
        text = "Check your email",
        color = RoseGoldPrimary,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp
    )
    Text(
        text = "If an account exists for that address, a reset link is on its way. " +
            "Open it on this device or any browser to choose a new password. " +
            "The link works once and expires within the hour.",
        color = TextMuted,
        fontSize = 14.sp,
        textAlign = TextAlign.Center
    )
    Text(
        text = "Nothing arrived? Check your spam folder before requesting another " +
            "link — each new link cancels the previous one.",
        color = TextMuted,
        fontSize = 13.sp,
        textAlign = TextAlign.Center
    )

    Button(
        onClick = onNavigateBackToLogin,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
    ) {
        Text(
            text = "Back to sign in",
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }
}
