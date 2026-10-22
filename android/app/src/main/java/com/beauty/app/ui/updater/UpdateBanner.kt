package com.beauty.app.ui.updater

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beauty.app.ui.theme.*
import com.beauty.app.updater.UpdateState

@Composable
fun UpdateBanner(
    state: UpdateState,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (title, actionLabel, accentColor) = when (state) {
        is UpdateState.Available -> Triple(
            stringResource(com.beauty.app.R.string.update_banner_available, state.release.versionName),
            stringResource(com.beauty.app.R.string.update),
            Forest
        )
        is UpdateState.Downloading -> Triple(
            stringResource(com.beauty.app.R.string.update_banner_downloading, (state.progress * 100).toInt()),
            stringResource(com.beauty.app.R.string.view),
            Forest
        )
        is UpdateState.ReadyToInstall -> Triple(
            stringResource(com.beauty.app.R.string.update_banner_ready, state.release.versionName),
            stringResource(com.beauty.app.R.string.install),
            Success
        )
        else -> return
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onTap),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = WhiteCard)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.SystemUpdate, contentDescription = null,
                tint = accentColor, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Forest, style = MaterialTheme.typography.bodySmall)
                Text(actionLabel, color = accentColor, style = MaterialTheme.typography.labelLarge)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null,
                tint = accentColor, modifier = Modifier.size(20.dp))
        }
    }
}
