package com.beauty.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
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

import com.beauty.app.BuildConfig
import com.beauty.app.updater.AppRelease
import com.beauty.app.updater.UpdateDistributionMode
import com.beauty.app.updater.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenUpdateDialog: () -> Unit = {}
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmNewPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    fun openApkDownload(release: AppRelease) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)))
        } catch (_: Exception) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Unable to open the APK download link.")
            }
        }
    }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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

            // About card
            val updateManager = viewModel.updateManager
            val updateState by (updateManager?.state ?: MutableStateFlow(UpdateState.Idle)).collectAsState()
            val distributionMode = updateManager?.getDistributionMode() ?: UpdateDistributionMode.AUTO
            val isChecking = updateState is UpdateState.Checking
            val canCheckForUpdates = updateManager != null && updateState !is UpdateState.Checking &&
                updateState !is UpdateState.Downloading && updateState !is UpdateState.ReadyToInstall

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = RoseGoldPrimary, modifier = Modifier.size(20.dp))
                            Text("About", color = RoseGoldPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x15E5B899)
                        ) {
                            Text(
                                "v${BuildConfig.VERSION_NAME}",
                                color = RoseGoldPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text("Application version", color = TextLight, fontSize = 13.sp)
                    Text("v${BuildConfig.VERSION_NAME}", color = TextMuted, fontSize = 13.sp)

                    // Distribution source info
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Update Source", color = TextLight, fontSize = 13.sp)
                        Text(
                            when (distributionMode) {
                                UpdateDistributionMode.PLAY_STORE -> "Google Play Store"
                                UpdateDistributionMode.GITHUB_DIRECT -> "GitHub Releases"
                                UpdateDistributionMode.AUTO -> "Auto (GitHub)"
                            },
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }

                    // Dynamic update status
                    when (val state = updateState) {
                        is UpdateState.Checking -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = RoseGoldPrimary, strokeWidth = 2.dp)
                                Text("Checking for updates…", color = TextMuted, fontSize = 13.sp)
                            }
                        }
                        is UpdateState.Available -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("v${state.release.versionName} is available!", color = RoseGoldPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text("New features and fixes are ready.", color = TextMuted, fontSize = 11.sp)
                                    }
                                    Button(
                                        onClick = onOpenUpdateDialog,
                                        colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Text("Update", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                                ApkDownloadLink(state.release, ::openApkDownload)
                            }
                        }
                        is UpdateState.Downloading -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Downloading update: ${(state.progress * 100).toInt()}%", color = RoseGoldPrimary, fontSize = 13.sp)
                                        LinearProgressIndicator(
                                            progress = { state.progress },
                                            modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 4.dp),
                                            color = RoseGoldPrimary,
                                            trackColor = Color(0x33E5B899)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    OutlinedButton(
                                        onClick = onOpenUpdateDialog,
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("View", fontSize = 12.sp)
                                    }
                                }
                                ApkDownloadLink(state.release, ::openApkDownload)
                            }
                        }
                        is UpdateState.ReadyToInstall -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Update downloaded and ready to install.", color = EmeraldStatus, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    Button(
                                        onClick = onOpenUpdateDialog,
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldStatus),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Text("Install", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                                ApkDownloadLink(state.release, ::openApkDownload)
                            }
                        }
                        is UpdateState.UpToDate -> {
                            Text("Aura Beauty is up to date.", color = EmeraldStatus, fontSize = 13.sp)
                        }
                        is UpdateState.Error -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(state.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                                state.release?.let { ApkDownloadLink(it, ::openApkDownload) }
                            }
                        }
                        UpdateState.Idle -> Text("Update status not checked yet.", color = TextMuted, fontSize = 13.sp)
                    }

                    if (updateManager == null) {
                        Text("Update service is unavailable.", color = TextMuted, fontSize = 13.sp)
                    }

                    // Check for Updates action button
                    OutlinedButton(
                        onClick = viewModel::checkForUpdates,
                        enabled = canCheckForUpdates,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RoseGoldPrimary)
                    ) {
                        if (isChecking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = RoseGoldPrimary, strokeWidth = 2.dp)
                        } else {
                            Text("Check for Updates", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApkDownloadLink(release: AppRelease, onClick: (AppRelease) -> Unit) {
    TextButton(
        onClick = { onClick(release) },
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Download APK (v${release.versionName})",
            color = RoseGoldPrimary,
            fontSize = 13.sp,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.fillMaxWidth()
        )
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
