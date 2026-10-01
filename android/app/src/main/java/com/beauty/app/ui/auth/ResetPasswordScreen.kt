package com.beauty.app.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.ui.theme.CardSurface
import com.beauty.app.ui.theme.RoseGoldPrimary
import com.beauty.app.ui.theme.TextLight
import com.beauty.app.ui.theme.TextMuted
import com.beauty.app.ui.tokenFromWebAppLink

/**
 * Sets a new password from a reset link the user pastes in — the in-app
 * counterpart of the web app's `/reset-password` page.
 *
 * The link is pasted rather than opened through a deep link; see
 * `tokenFromWebAppLink` for why. No session is issued on success, matching the
 * backend: the user signs in with the new password, which proves it works.
 */
@Composable
fun ResetPasswordScreen(
    viewModel: AuthViewModel,
    onNavigateBackToLogin: () -> Unit,
    onRequestNewLink: () -> Unit
) {
    var link by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val state = viewModel.resetPasswordState
    val loading = state is AuthViewModel.ResetPasswordState.Loading
    val fieldErrors = (state as? AuthViewModel.ResetPasswordState.Error)?.fieldErrors.orEmpty()

    DisposableEffect(Unit) {
        onDispose { viewModel.resetState() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
                if (state is AuthViewModel.ResetPasswordState.Done) {
                    Text(
                        text = "Password changed",
                        color = RoseGoldPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                    Text(
                        text = "You've been signed out on every device. Sign in with your new password.",
                        color = TextMuted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    PrimaryButton(text = "Sign in", loading = false, onClick = onNavigateBackToLogin)
                } else {
                    Text(
                        text = "Choose a new password",
                        color = RoseGoldPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                    Text(
                        text = "Copy the reset link from the email (long-press it, then Copy link) " +
                            "and paste it below.",
                        color = TextMuted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )

                    ResetField(
                        value = link,
                        onValueChange = { link = it },
                        label = "Reset link",
                        error = fieldErrors["link"],
                        enabled = !loading,
                        keyboardType = KeyboardType.Uri
                    )
                    ResetField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = "New password",
                        error = fieldErrors["newPassword"],
                        helper = "At least ${AuthValidation.PASSWORD_MIN_LENGTH} characters.",
                        enabled = !loading,
                        keyboardType = KeyboardType.Password,
                        hidden = !showPassword,
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showPassword) "Hide password" else "Show password",
                                    tint = TextMuted
                                )
                            }
                        }
                    )
                    ResetField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = "Confirm new password",
                        error = fieldErrors["confirmPassword"],
                        enabled = !loading,
                        keyboardType = KeyboardType.Password,
                        hidden = !showPassword
                    )

                    (state as? AuthViewModel.ResetPasswordState.Error)?.message?.let { message ->
                        Text(text = message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }

                    PrimaryButton(text = "Set new password", loading = loading) {
                        viewModel.resetPassword(
                            token = tokenFromWebAppLink(link, "token", "/reset-password"),
                            newPassword = newPassword,
                            confirmPassword = confirmPassword
                        )
                    }

                    if (fieldErrors.containsKey("link")) {
                        TextButton(onClick = onRequestNewLink, enabled = !loading) {
                            Text(text = "Request a new link", color = RoseGoldPrimary, fontSize = 13.sp)
                        }
                    }
                    TextButton(onClick = onNavigateBackToLogin, enabled = !loading) {
                        Text(text = "Back to sign in", color = RoseGoldPrimary, fontSize = 13.sp)
                    }
                }
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
    helper: String? = null,
    hidden: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextMuted) },
        isError = error != null,
        supportingText = (error ?: helper)?.let { text -> { Text(text) } },
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = trailingIcon,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = RoseGoldPrimary,
            unfocusedBorderColor = Color(0x33E5B899),
            focusedTextColor = TextLight,
            unfocusedTextColor = TextLight
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
            .height(50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
        } else {
            Text(text = text, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}
