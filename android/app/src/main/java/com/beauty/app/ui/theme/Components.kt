package com.beauty.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beauty.app.R
import com.beauty.app.ui.i18n.LanguageSelector

@Composable
fun AuraCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = WhiteCard),
        border = BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
fun AuraPageHeader(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = Forest)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Muted) }
        }
        trailing?.invoke()
    }
}

@Composable
fun AuraSectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Forest)
}

/** Native single-column counterpart to the web's editorial authentication layout. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuraAuthLayout(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accountId: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier.fillMaxSize().background(Paper).statusBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Spa, contentDescription = null, tint = Forest, modifier = Modifier.size(28.dp))
                Text("aura", style = MaterialTheme.typography.headlineMedium, color = Forest, modifier = Modifier.weight(1f))
            }
            FlowRow(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ThemeSelector(compact = true)
                LanguageSelector(accountId = accountId, compact = true)
            }
            Spacer(Modifier.height(24.dp))
            Surface(color = Sage, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 14.dp, bottomEnd = 14.dp)) {
                Icon(Icons.Default.Spa, contentDescription = null, tint = Forest, modifier = Modifier.padding(14.dp).size(28.dp))
            }
            Spacer(Modifier.height(20.dp))
            AuraPageHeader(title, subtitle)
            Spacer(Modifier.height(24.dp))
            AuraCard(content = content)
            Text(
                stringResource(R.string.redesign_auth_footer), color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 24.dp, bottom = 16.dp)
            )
        }
    }
}
