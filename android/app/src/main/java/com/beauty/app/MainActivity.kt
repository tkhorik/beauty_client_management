package com.beauty.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.beauty.app.data.local.BeautyDatabaseProvider
import com.beauty.app.data.local.ClientEntity
import com.beauty.app.sync.SyncWorker
import com.beauty.app.ui.auth.AuthViewModel
import com.beauty.app.ui.auth.LoginScreen
import com.beauty.app.ui.auth.RegisterScreen
import com.beauty.app.ui.client.EditClientScreen
import com.beauty.app.ui.client.EditClientViewModel
import com.beauty.app.ui.theme.*
import kotlinx.serialization.json.Json

data class DirectoryClient(
    val id: String,
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
                    AppNavHost()
                }
            }
        }
    }
}

@Composable
fun AppNavHost() {
    val context = LocalContext.current
    val tokenStore = remember { AppContainer.tokenStore(context) }
    val repository = remember { AppContainer.repository(context, tokenStore) }
    val database = remember { BeautyDatabaseProvider.get(context) }

    val navController = rememberNavController()
    val startDestination = if (tokenStore.getToken() != null) "clients" else "login"

    // AuthViewModel factory — uses an auth-capable Ktor client (no token yet, but endpoint is public)
    val authViewModel: AuthViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val api = com.beauty.app.data.api.KtorBeautyApi(AppContainer.buildLoginClient())
                return AuthViewModel(api, tokenStore) as T
            }
        }
    )

    NavHost(navController = navController, startDestination = startDestination) {

        composable("login") {
            LoginScreen(
                viewModel = authViewModel,
                onLoginSuccess = {
                    navController.navigate("clients") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onNavigateToRegister = { navController.navigate("register") }
            )
        }

        composable("register") {
            RegisterScreen(
                viewModel = authViewModel,
                onRegisterSuccess = {
                    // Registration returns a token, so the new user lands in
                    // the app already signed in. The whole auth stack is popped
                    // so Back cannot return to the form.
                    navController.navigate("clients") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onNavigateToLogin = { navController.popBackStack() }
            )
        }

        composable("clients") {
            BeautyAppScreen(
                tokenStore = tokenStore,
                onClientTap = { clientId ->
                    navController.navigate("edit_client/$clientId")
                },
                onLogout = {
                    // Revokes the refresh token server-side before clearing it
                    // locally; navigation waits for that so the user is never
                    // returned to the login screen while still holding a live
                    // session. Failures still clear locally — see the ViewModel.
                    authViewModel.logout {
                        navController.navigate("login") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(
            route = "edit_client/{clientId}",
            arguments = listOf(navArgument("clientId") { type = NavType.StringType })
        ) { backStackEntry ->
            val clientId = backStackEntry.arguments!!.getString("clientId")!!
            val clientDao = database.clientDao()
            val editViewModel: EditClientViewModel = viewModel(
                key = "edit_$clientId",
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        EditClientViewModel(clientId, repository, clientDao) as T
                }
            )
            EditClientScreen(
                viewModel = editViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeautyAppScreen(
    tokenStore: com.beauty.app.data.local.TokenStore,
    onClientTap: (clientId: String) -> Unit,
    onLogout: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    val database = remember { BeautyDatabaseProvider.get(context) }
    val repository = remember { AppContainer.repository(context, tokenStore) }
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
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(
                            Icons.Default.ExitToApp,
                            contentDescription = "Logout",
                            tint = TextMuted
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
                onClick = { /* Visit-entry UI — future work */ },
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
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search clients or procedure specs...", color = TextMuted) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = TextMuted)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RoseGoldPrimary,
                    unfocusedBorderColor = Color(0x33E5B899)
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

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

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(clients.filter { client ->
                    client.name.contains(searchQuery, ignoreCase = true) ||
                        client.tagsJson.contains(searchQuery, ignoreCase = true)
                }) { client ->
                    ClientCardItem(
                        client = client.toDirectoryClient(),
                        onClick = { onClientTap(client.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun ClientCardItem(client: DirectoryClient, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
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
                        Text(
                            client.name,
                            color = TextLight,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
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
    val tags = runCatching {
        Json.decodeFromString<List<String>>(tagsJson).joinToString(" • ")
    }.getOrDefault("")
    return DirectoryClient(
        id = id,
        name = name,
        phone = phone,
        tag = tags.ifBlank { "No tags" },
        visitsCount = totalVisits,
        lastVisit = "Synced from API"
    )
}
