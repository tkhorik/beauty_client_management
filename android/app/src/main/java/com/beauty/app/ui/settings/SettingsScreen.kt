package com.beauty.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.ui.auth.AuthValidation
import com.beauty.app.ui.theme.CardSurface
import com.beauty.app.ui.theme.EmeraldStatus
import com.beauty.app.ui.theme.RoseGoldPrimary
import com.beauty.app.ui.theme.TextLight
import com.beauty.app.ui.theme.TextMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmNewPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    val profileState = viewModel.profileState
    val profileError = profileState as? SettingsViewModel.ProfileState.Error
    val profileFieldErrors = profileError?.fieldErrors ?: emptyMap()
    val isProfileLoading = profileState is SettingsViewModel.ProfileState.Loading

    val passwordState = viewModel.passwordState
    val passwordError = passwordState as? SettingsViewModel.PasswordState.Error
    val passwordFieldErrors = passwordError?.fieldErrors ?: emptyMap()
    val isPasswordLoading = passwordState is SettingsViewModel.PasswordState.Loading

    // A successful change clears the form — leaving the old password sitting
    // in the fields would be an easy way to accidentally submit it again.
    LaunchedEffect(passwordState) {
        if (passwordState is SettingsViewModel.PasswordState.Success) {
            currentPassword = ""
            newPassword = ""
            confirmNewPassword = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Account Settings", color = RoseGoldPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextLight)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardSurface)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // Profile card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Profile", color = RoseGoldPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    SettingsTextField(
                        value = viewModel.email,
                        onValueChange = {},
                        label = "Email",
                        enabled = false,
                        helper = "Your sign-in identifier — can't be changed here yet."
                    )

                    SettingsTextField(
                        value = viewModel.fullName,
                        onValueChange = {
                            viewModel.updateFullName(it)
                            viewModel.resetProfileState()
                        },
                        label = "Full Name",
                        error = profileFieldErrors["fullName"]
                    )

                    profileError?.message?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                    if (profileState is SettingsViewModel.ProfileState.Success) {
                        Text("Profile updated.", color = EmeraldStatus, fontSize = 13.sp)
                    }

                    Button(
                        onClick = viewModel::saveProfile,
                        enabled = !isProfileLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                    ) {
                        if (isProfileLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                        } else {
                            Text("Save Name", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Password card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Change Password", color = RoseGoldPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    SettingsTextField(
                        value = currentPassword,
                        onValueChange = { currentPassword = it; viewModel.resetPasswordState() },
                        label = "Current Password",
                        error = passwordFieldErrors["currentPassword"],
                        isPassword = true,
                        showPassword = showPassword,
                        onToggleShowPassword = { showPassword = !showPassword }
                    )

                    SettingsTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it; viewModel.resetPasswordState() },
                        label = "New Password",
                        error = passwordFieldErrors["newPassword"],
                        helper = if (passwordFieldErrors["newPassword"] == null) {
                            "At least ${AuthValidation.PASSWORD_MIN_LENGTH} characters."
                        } else {
                            null
                        },
                        isPassword = true,
                        showPassword = showPassword,
                        onToggleShowPassword = { showPassword = !showPassword }
                    )

                    SettingsTextField(
                        value = confirmNewPassword,
                        onValueChange = { confirmNewPassword = it; viewModel.resetPasswordState() },
                        label = "Confirm New Password",
                        error = passwordFieldErrors["confirmNewPassword"],
                        isPassword = true,
                        showPassword = showPassword,
                        onToggleShowPassword = { showPassword = !showPassword }
                    )

                    passwordError?.message?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                    if (passwordState is SettingsViewModel.PasswordState.Success) {
                        Text(
                            "Password changed. You've been signed out of every other device.",
                            color = EmeraldStatus,
                            fontSize = 13.sp
                        )
                    }

                    Button(
                        onClick = { viewModel.changePassword(currentPassword, newPassword, confirmNewPassword) },
                        enabled = !isPasswordLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                    ) {
                        if (isPasswordLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                        } else {
                            Text("Change Password", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One field definition for this screen, mirroring `AuthTextField` in
 * `RegisterScreen.kt` (private there, so not shared directly) — same error /
 * helper / password-toggle behaviour, plus a disabled state for the read-only
 * email field.
 */
@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String? = null,
    helper: String? = null,
    enabled: Boolean = true,
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onToggleShowPassword: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, color = TextMuted) },
            isError = error != null,
            enabled = enabled,
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
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                            tint = TextMuted
                        )
                    }
                }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = RoseGoldPrimary,
                unfocusedBorderColor = Color(0x33E5B899),
                focusedTextColor = TextLight,
                unfocusedTextColor = TextLight,
                disabledTextColor = TextMuted,
                disabledBorderColor = Color(0x22E5B899)
            )
        )

        when {
            error != null -> Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
            helper != null -> Text(
                text = helper,
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}
