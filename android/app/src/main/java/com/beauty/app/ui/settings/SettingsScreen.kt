package com.beauty.app.ui.settings

import com.beauty.app.ui.i18n.localizedMessage
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
import androidx.compose.ui.res.stringResource
import com.beauty.app.ui.auth.AuthValidation
import com.beauty.app.ui.i18n.LanguageSelector
import com.beauty.app.ui.theme.CardSurface
import com.beauty.app.ui.theme.EmeraldStatus
import com.beauty.app.ui.theme.RoseGoldPrimary
import com.beauty.app.ui.theme.TextLight
import com.beauty.app.ui.theme.TextMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    accountId: String?,
    languageManager: com.beauty.app.i18n.LanguagePreferenceManager,
    onLanguageSelected: (String?) -> Unit,
    onBack: () -> Unit
) {
    var currentPassword by viewModel::currentPasswordDraft
    var newPassword by viewModel::newPasswordDraft
    var confirmNewPassword by viewModel::confirmPasswordDraft
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
                    Text(stringResource(com.beauty.app.R.string.account_settings_2), color = RoseGoldPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(com.beauty.app.R.string.back), tint = TextLight)
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

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(com.beauty.app.R.string.language), color = RoseGoldPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    LanguageSelector(accountId = accountId, modifier = Modifier.fillMaxWidth(), onSelected = onLanguageSelected)
                    when (languageManager.notice) {
                        com.beauty.app.i18n.LanguagePreferenceManager.SyncResult.Conflict ->
                            Text(stringResource(com.beauty.app.R.string.language_conflict), color = TextMuted)
                        com.beauty.app.i18n.LanguagePreferenceManager.SyncResult.PendingOffline ->
                            Text(stringResource(com.beauty.app.R.string.language_saved_offline), color = TextMuted)
                        else -> Unit
                    }
                }
            }

            // Profile card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(com.beauty.app.R.string.profile), color = RoseGoldPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    SettingsTextField(
                        value = viewModel.email,
                        onValueChange = {},
                        label = stringResource(com.beauty.app.R.string.email),
                        enabled = false,
                        helper = stringResource(com.beauty.app.R.string.your_sign_in_identifier_can_t_be_changed_here_yet)
                    )

                    SettingsTextField(
                        value = viewModel.fullName,
                        onValueChange = {
                            viewModel.updateFullName(it)
                            viewModel.resetProfileState()
                        },
                        label = stringResource(com.beauty.app.R.string.full_name),
                        error = profileFieldErrors["fullName"]
                    )

                    profileError?.message?.let {
                        Text(localizedMessage(it), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                    if (profileState is SettingsViewModel.ProfileState.Success) {
                        Text(stringResource(com.beauty.app.R.string.profile_updated), color = EmeraldStatus, fontSize = 13.sp)
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
                            Text(stringResource(com.beauty.app.R.string.save_name), color = Color.Black, fontWeight = FontWeight.Bold)
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
                    Text(stringResource(com.beauty.app.R.string.change_password), color = RoseGoldPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    SettingsTextField(
                        value = currentPassword,
                        onValueChange = { currentPassword = it; viewModel.resetPasswordState() },
                        label = stringResource(com.beauty.app.R.string.current_password),
                        error = passwordFieldErrors["currentPassword"],
                        isPassword = true,
                        showPassword = showPassword,
                        onToggleShowPassword = { showPassword = !showPassword }
                    )

                    SettingsTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it; viewModel.resetPasswordState() },
                        label = stringResource(com.beauty.app.R.string.new_password_2),
                        error = passwordFieldErrors["newPassword"],
                        helper = if (passwordFieldErrors["newPassword"] == null) {
                            stringResource(com.beauty.app.R.string.password_minimum, AuthValidation.PASSWORD_MIN_LENGTH)
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
                        label = stringResource(com.beauty.app.R.string.confirm_new_password_2),
                        error = passwordFieldErrors["confirmNewPassword"],
                        isPassword = true,
                        showPassword = showPassword,
                        onToggleShowPassword = { showPassword = !showPassword }
                    )

                    passwordError?.message?.let {
                        Text(localizedMessage(it), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                    if (passwordState is SettingsViewModel.PasswordState.Success) {
                        Text(
                            stringResource(com.beauty.app.R.string.ui2_password_changed_you_ve_been_signed_out_of_every_other_device),
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
                            Text(stringResource(com.beauty.app.R.string.change_password), color = Color.Black, fontWeight = FontWeight.Bold)
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
                            contentDescription = if (showPassword) stringResource(com.beauty.app.R.string.hide_password) else stringResource(com.beauty.app.R.string.show_password),
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
                text = localizedMessage(error),
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
