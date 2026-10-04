package com.beauty.app.ui.updater

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.beauty.app.ui.theme.*
import com.beauty.app.updater.AppRelease
import com.beauty.app.updater.UpdateDistributionMode
import com.beauty.app.updater.UpdateState
import java.util.Locale

@Composable
fun UpdateDialog(
    state: UpdateState,
    currentVersion: String,
    distributionMode: UpdateDistributionMode,
    onDismiss: () -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenPlayStore: () -> Unit,
    onRequestPermission: () -> Unit,
    needsInstallPermission: Boolean
) {
    val release: AppRelease = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.ReadyToInstall -> state.release
        is UpdateState.Error -> state.release ?: return
        else -> return
    }

    Dialog(
        onDismissRequest = {
            if (state !is UpdateState.Downloading) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = state !is UpdateState.Downloading,
            dismissOnClickOutside = state !is UpdateState.Downloading
        )
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x22E5B899)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = stringResource(com.beauty.app.R.string.update),
                            tint = RoseGoldPrimary
                        )
                    }

                    Column {
                        Text(
                            text = stringResource(com.beauty.app.R.string.new_version_available),
                            color = TextLight,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "v$currentVersion",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                            Icon(
                                Icons.Default.ArrowForward,
                                contentDescription = null,
                                tint = RoseGoldPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "v${release.versionName}",
                                color = RoseGoldPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                // Changelog / Release Notes
                if (release.notes.isNotBlank()) {
                    Text(
                        text = stringResource(com.beauty.app.R.string.what_s_new_in_this_release),
                        color = TextLight,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x10FFFFFF),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = release.notes,
                                color = TextMuted,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                // Size info
                if (release.apkSize > 0L && distributionMode != UpdateDistributionMode.PLAY_STORE) {
                    Text(
                        text = stringResource(com.beauty.app.R.string.download_size, formatBytes(release.apkSize)),
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }

                // Downloading progress UI
                if (state is UpdateState.Downloading) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = RoseGoldPrimary,
                            trackColor = Color(0x33E5B899),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${(state.progress * 100).toInt()}%",
                                color = RoseGoldPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // Error message if any
                if (state is UpdateState.Error) {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }

                // Permission warning if about to install
                if (state is UpdateState.ReadyToInstall && needsInstallPermission) {
                    Surface(
                        color = Color(0x1AD4A373),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(com.beauty.app.R.string.ui2_to_install_this_update_you_ll_need_to_allow_aura_beauty_to_instal),
                            color = ChampagneAccent,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (state) {
                        is UpdateState.Downloading -> {
                            OutlinedButton(
                                onClick = onCancelDownload,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(stringResource(com.beauty.app.R.string.cancel), color = TextLight)
                            }
                        }
                        is UpdateState.ReadyToInstall -> {
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(com.beauty.app.R.string.later), color = TextMuted)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (needsInstallPermission) onRequestPermission() else onInstall()
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldStatus)
                            ) {
                                Text(
                                    text = if (needsInstallPermission) stringResource(com.beauty.app.R.string.grant_permission) else stringResource(com.beauty.app.R.string.install_now),
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        else -> {
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(com.beauty.app.R.string.later), color = TextMuted)
                            }
                            Spacer(modifier = Modifier.width(8.dp))

                            if (distributionMode == UpdateDistributionMode.PLAY_STORE) {
                                Button(
                                    onClick = onOpenPlayStore,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                                ) {
                                    Text(stringResource(com.beauty.app.R.string.open_google_play), color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Button(
                                    onClick = onStartDownload,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                                ) {
                                    Text(stringResource(com.beauty.app.R.string.download_install), color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return stringResource(com.beauty.app.R.string.size_bytes, 0)
    val kb = bytes / 1024f
    val mb = kb / 1024f
    return if (mb >= 1.0f) {
        stringResource(com.beauty.app.R.string.size_megabytes, String.format(Locale.getDefault(), "%.1f", mb))
    } else {
        stringResource(com.beauty.app.R.string.size_kilobytes, String.format(Locale.getDefault(), "%.0f", kb))
    }
}
