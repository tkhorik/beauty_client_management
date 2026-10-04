package com.beauty.app.ui.updater

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.ui.theme.CardSurface
import com.beauty.app.ui.theme.EmeraldStatus
import com.beauty.app.ui.theme.RoseGoldPrimary
import com.beauty.app.ui.theme.TextLight
import com.beauty.app.ui.theme.TextMuted
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
            RoseGoldPrimary
        )
        is UpdateState.Downloading -> Triple(
            stringResource(com.beauty.app.R.string.update_banner_downloading, (state.progress * 100).toInt()),
            stringResource(com.beauty.app.R.string.view),
            RoseGoldPrimary
        )
        is UpdateState.ReadyToInstall -> Triple(
            stringResource(com.beauty.app.R.string.update_banner_ready, state.release.versionName),
            stringResource(com.beauty.app.R.string.install),
            EmeraldStatus
        )
        else -> return
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = title,
                        color = TextLight,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = stringResource(com.beauty.app.R.string.ui2_tap_to_review_changes_install),
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            Button(
                onClick = onTap,
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(
                    text = actionLabel,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}
