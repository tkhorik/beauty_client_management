package com.beauty.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.ui.theme.*
import com.beauty.app.data.local.ClientEntity
import com.beauty.app.data.local.BeautyDatabaseProvider
import com.beauty.app.sync.SyncWorker
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class DirectoryClient(
    val name: String,
    val phone: String,
    val tag: String,
    val visitsCount: Int,
    val lastVisit: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BeautyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BeautyAppScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeautyAppScreen() {
    var searchQuery by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val database = remember { BeautyDatabaseProvider.get(context) }
    val repository = remember { AppContainer.repository(context) }
    val clients by database.clientDao().getAllClients().collectAsState(initial = emptyList())
    LaunchedEffect(Unit) {
        repository.refreshClients()
        SyncWorker.enqueue(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Aura Beauty Mobile",
                            color = RoseGoldPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            "Client & Procedure Logging Studio",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CardSurface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { /* Visit-entry UI is intentionally out of scope; repository can queue visits. */ },
                containerColor = RoseGoldPrimary,
                contentColor = Color.Black
            ) {
                Icon(Icons.Default.Add, contentDescription = "Log Visit")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search clients or procedure specs...", color = TextMuted) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = TextMuted) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RoseGoldPrimary,
                    unfocusedBorderColor = Color(0x33E5B899)
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Section Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Client Directory",
                    color = TextLight,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    "Offline Caching Active",
                    color = EmeraldStatus,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Client Cards List
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(clients.filter { client ->
                    client.name.contains(searchQuery, ignoreCase = true) ||
                        client.tagsJson.contains(searchQuery, ignoreCase = true)
                }) { client ->
                    ClientCardItem(client.toDirectoryClient())
                }
            }
        }
    }
}

@Composable
fun ClientCardItem(client: DirectoryClient) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x22E5B899)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = RoseGoldPrimary)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(client.name, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(client.phone, color = TextMuted, fontSize = 12.sp)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0x15E5B899)
                ) {
                    Text(
                        "${client.visitsCount} Visits",
                        color = RoseGoldPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(client.tag, color = TextMuted, fontSize = 12.sp)
                Text("Last: ${client.lastVisit}", color = ChampagneAccent, fontSize = 12.sp)
            }
        }
    }
}

private fun ClientEntity.toDirectoryClient(): DirectoryClient {
    val tags = runCatching { Json.decodeFromString<List<String>>(tagsJson).joinToString(" • ") }
        .getOrDefault("")
    return DirectoryClient(
        name = name,
        phone = phone,
        tag = tags.ifBlank { "No tags" },
        visitsCount = totalVisits,
        lastVisit = "Synced from API"
    )
}
