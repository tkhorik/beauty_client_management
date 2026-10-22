package com.beauty.app.ui.about

import com.beauty.app.ui.i18n.localizedMessage
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.BuildConfig
import com.beauty.app.ui.theme.*
import com.beauty.app.updater.UpdateDistributionMode
import com.beauty.app.updater.UpdateManager
import com.beauty.app.updater.UpdateState
import kotlinx.coroutines.launch

/**
 * App identity and update status, reached from the overflow menu.
 *
 * The update check itself is started by the caller ([onCheckForUpdates]) in a
 * scope that outlives this screen: [UpdateManager] is an app-wide singleton,
 * and a check cancelled by navigating back would leave it stuck in `Checking`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    updateManager: UpdateManager,
    onCheckForUpdates: () -> Unit,
    onOpenUpdateDialog: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val updateState by updateManager.state.collectAsState()
    val versionLabel = stringResource(com.beauty.app.R.string.version_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(com.beauty.app.R.string.ui2_no_app_available_to_open_the_link)) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("aura", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(com.beauty.app.R.string.back), tint = Forest)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            AuraPageHeader(
                stringResource(com.beauty.app.R.string.about),
                stringResource(com.beauty.app.R.string.redesign_about_intro)
            )
            AboutCard {
                Text(stringResource(com.beauty.app.R.string.aura_beauty_log), color = Forest, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(versionLabel, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    // Support asks for the exact build first; make it one tap to paste.
                    val versionCopied = stringResource(com.beauty.app.R.string.version_copied)
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString("Aura Beauty Log $versionLabel"))
                        scope.launch { snackbarHostState.showSnackbar(versionCopied) }
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(com.beauty.app.R.string.copy_version), tint = Muted)
                    }
                }
            }

            AboutCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = Forest, modifier = Modifier.size(20.dp))
                    Text(stringResource(com.beauty.app.R.string.updates), color = Forest, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                UpdateStatus(updateState)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(com.beauty.app.R.string.source), color = Muted, fontSize = 13.sp)
                    Text(
                        when (updateManager.getDistributionMode()) {
                            UpdateDistributionMode.PLAY_STORE -> stringResource(com.beauty.app.R.string.google_play)
                            else -> stringResource(com.beauty.app.R.string.github_releases)
                        },
                        color = Forest,
                        fontSize = 13.sp
                    )
                }

                UpdateAction(
                    state = updateState,
                    onCheckForUpdates = onCheckForUpdates,
                    onOpenUpdateDialog = onOpenUpdateDialog
                )
            }

            AboutCard {
                val release = when (val s = updateState) {
                    is UpdateState.Available -> s.release
                    is UpdateState.Downloading -> s.release
                    is UpdateState.ReadyToInstall -> s.release
                    is UpdateState.Error -> s.release
                    else -> null
                }
                LinkRow(
                    label = if (release != null) stringResource(com.beauty.app.R.string.whats_new_version, release.versionName) else stringResource(com.beauty.app.R.string.release_notes),
                    onClick = { openUrl(release?.htmlUrl?.ifBlank { null } ?: updateManager.releasesPageUrl) }
                )
                // In-app install can fail (no space, blocked installer, flaky
                // network); the release page is always a working way out.
                if (updateState is UpdateState.Error) {
                    LinkRow(label = stringResource(com.beauty.app.R.string.download_from_github), onClick = { openUrl(updateManager.releasesPageUrl) })
                }
            }
        }
    }
}

@Composable
private fun UpdateStatus(state: UpdateState) {
    when (state) {
        UpdateState.Idle -> Text(stringResource(com.beauty.app.R.string.not_checked_yet), color = Muted, fontSize = 13.sp)
        UpdateState.Checking -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Forest, strokeWidth = 2.dp)
            Text(stringResource(com.beauty.app.R.string.checking_for_updates), color = Muted, fontSize = 13.sp)
        }
        is UpdateState.UpToDate -> Column {
            Text(stringResource(com.beauty.app.R.string.you_re_on_the_latest_version), color = Success, fontSize = 13.sp)
            Text(stringResource(com.beauty.app.R.string.checked_time, relativeTime(state.checkedAt)), color = Muted, fontSize = 13.sp)
        }
        is UpdateState.Available -> Text(
            stringResource(com.beauty.app.R.string.update_available, state.release.versionName),
            color = Forest,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
        is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(com.beauty.app.R.string.update_downloading, state.release.versionName, (state.progress * 100).toInt()), color = Forest, fontSize = 13.sp)
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
                color = Forest,
                trackColor = Border
            )
        }
        is UpdateState.ReadyToInstall -> Text(
            stringResource(com.beauty.app.R.string.update_ready, state.release.versionName),
            color = Success,
            fontSize = 13.sp
        )
        is UpdateState.Error -> Text(localizedMessage(state.message), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
    }
}

/** One primary action per state, so the user is never asked to choose between equivalent buttons. */
@Composable
private fun UpdateAction(
    state: UpdateState,
    onCheckForUpdates: () -> Unit,
    onOpenUpdateDialog: () -> Unit
) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    val shape = RoundedCornerShape(14.dp)
    when (state) {
        is UpdateState.Available, is UpdateState.Downloading, is UpdateState.ReadyToInstall -> {
            val (label, color) = when (state) {
                is UpdateState.ReadyToInstall -> stringResource(com.beauty.app.R.string.install_update) to Success
                is UpdateState.Downloading -> stringResource(com.beauty.app.R.string.view_download) to Forest
                else -> stringResource(com.beauty.app.R.string.update_now) to Forest
            }
            Button(
                onClick = onOpenUpdateDialog,
                modifier = modifier,
                shape = shape,
                colors = ButtonDefaults.buttonColors(containerColor = color)
            ) {
                Text(label, color = WhiteCard, fontWeight = FontWeight.Bold)
            }
        }
        else -> OutlinedButton(
            onClick = onCheckForUpdates,
            enabled = state !is UpdateState.Checking,
            modifier = modifier,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Forest)
        ) {
            Text(if (state is UpdateState.Error) stringResource(com.beauty.app.R.string.try_again) else stringResource(com.beauty.app.R.string.check_for_updates), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AboutCard(content: @Composable ColumnScope.() -> Unit) {
    AuraCard(content = content)
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Forest, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun relativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    if (now - timestamp < DateUtils.MINUTE_IN_MILLIS) return stringResource(com.beauty.app.R.string.just_now)
    return DateUtils.getRelativeTimeSpanString(timestamp, now, DateUtils.MINUTE_IN_MILLIS).toString()
}
