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
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.beauty.app.ui.auth.ForgotPasswordScreen
import com.beauty.app.ui.auth.LoginScreen
import com.beauty.app.ui.auth.RegisterScreen
import com.beauty.app.ui.client.EditClientScreen
import com.beauty.app.ui.client.EditClientViewModel
import com.beauty.app.ui.org.OrganizationScreen
import com.beauty.app.ui.org.OrganizationViewModel
import com.beauty.app.ui.settings.SettingsScreen
import com.beauty.app.ui.settings.SettingsViewModel
import com.beauty.app.ui.theme.*
import kotlinx.coroutines.launch
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
    val orgStore = remember { AppContainer.orgStore(context) }
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
                return AuthViewModel(api, tokenStore, orgStore) as T
            }
        }
    )

    // Hoisted to the NavHost so the "clients" and "organizations" destinations
    // share one instance: switching salons on the second must be visible to the
    // first without a reload.
    val orgViewModel: OrganizationViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                OrganizationViewModel(repository, orgStore) as T
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
                onNavigateToRegister = { navController.navigate("register") },
                onNavigateToForgotPassword = { navController.navigate("forgot-password") }
            )
        }

        composable("forgot-password") {
            // Only starts the flow. The emailed link opens the web app, which
            // is where the new password is actually set, so there is nothing
            // to navigate to on success — the user comes back and signs in.
            ForgotPasswordScreen(
                viewModel = authViewModel,
                onNavigateBackToLogin = { navController.popBackStack() }
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
            // Clients belong to an organization, so with none selected there is
            // nothing to show and every request would come back
            // MISSING_ORGANIZATION. Send the user somewhere they can act on it
            // instead of to an empty list that never loads.
            val activeOrgId = orgViewModel.activeOrgId
            if (!orgViewModel.loading && activeOrgId == null) {
                OrganizationScreen(
                    viewModel = orgViewModel,
                    onDone = null,
                    onLogout = {
                        authViewModel.logout {
                            navController.navigate("login") { popUpTo(0) { inclusive = true } }
                        }
                    }
                )
                return@composable
            }

            BeautyAppScreen(
                tokenStore = tokenStore,
                // Keying the screen on the organization means switching salons
                // rebuilds it, rather than leaving the previous one's search
                // text and selection sitting over the new one's data.
                organizationId = activeOrgId ?: return@composable,
                onClientTap = { clientId ->
                    navController.navigate("edit_client/$clientId")
                },
                onOpenSettings = { navController.navigate("settings") },
                onOpenOrganizations = { navController.navigate("organizations") },
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

        composable("organizations") {
            OrganizationScreen(
                viewModel = orgViewModel,
                onDone = { navController.popBackStack() },
                onLogout = {
                    authViewModel.logout {
                        navController.navigate("login") { popUpTo(0) { inclusive = true } }
                    }
                }
            )
        }

        composable("settings") {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        SettingsViewModel(repository, tokenStore) as T
                }
            )
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "edit_client/{clientId}",
            arguments = listOf(navArgument("clientId") { type = NavType.StringType })
        ) { backStackEntry ->
            val clientId = backStackEntry.arguments!!.getString("clientId")!!
            val clientDao = database.clientDao()
            // Captured when the editor opens, so a switch made elsewhere cannot
            // retarget a save that was started against this salon's record.
            val editOrgId = orgViewModel.activeOrgId ?: return@composable
            val editViewModel: EditClientViewModel = viewModel(
                key = "edit_${editOrgId}_$clientId",
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        EditClientViewModel(clientId, editOrgId, repository, clientDao) as T
                }
            )
            EditClientScreen(
                viewModel = editViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

private const val DIRECTORY_RESUME_REFRESH_AGE_MS = 60_000L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun BeautyAppScreen(
    tokenStore: com.beauty.app.data.local.TokenStore,
    /** The organization whose directory this screen shows. Never inferred. */
    organizationId: String,
    onClientTap: (clientId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOrganizations: () -> Unit,
    onLogout: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    val database = remember { BeautyDatabaseProvider.get(context) }
    val repository = remember { AppContainer.repository(context, tokenStore) }
    // Room holds every organization this device has seen, so the query is
    // scoped. An unscoped read here would show one salon's clients under
    // another's name for as long as the cache survives.
    val clients by database.clientDao().getAllClients(organizationId)
        .collectAsState(initial = emptyList())
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    var lastSuccessfulSyncAt by rememberSaveable { mutableLongStateOf(0L) }
    var refreshError by rememberSaveable { mutableStateOf<String?>(null) }

    // Room stays the only UI data source. This action refreshes the Room cache
    // from the API, so the list reacts to web changes as soon as the request
    // completes while remaining usable when the device is offline.
    val refreshDirectory = {
        if (!isRefreshing) {
            // Set this before launching so an ON_RESUME event and the initial
            // screen load cannot start two concurrent refreshes.
            isRefreshing = true
            scope.launch {
                repository.refreshClients(organizationId)
                    .onSuccess {
                        lastSuccessfulSyncAt = System.currentTimeMillis()
                        refreshError = null
                    }
                    .onFailure { error ->
                        refreshError = error.message ?: "Could not reach the server"
                    }
                isRefreshing = false
            }
        }
    }
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = refreshDirectory
    )

    // Keyed on the organization, not Unit: switching salons has to re-download
    // the directory, otherwise the screen sits on whatever this organization
    // happened to have cached the last time it was open.
    LaunchedEffect(organizationId) {
        refreshDirectory()
        SyncWorker.enqueue(context)
    }

    // A foreground app should not retain a stale directory after the user
    // switches back from the web app.  The age guard avoids unnecessary calls
    // during routine configuration/navigation events; pull-to-refresh always
    // bypasses it.
    DisposableEffect(lifecycleOwner, lastSuccessfulSyncAt) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                System.currentTimeMillis() - lastSuccessfulSyncAt >= DIRECTORY_RESUME_REFRESH_AGE_MS
            ) {
                refreshDirectory()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                    IconButton(onClick = onOpenOrganizations) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "Organizations",
                            tint = TextMuted
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Account settings",
                            tint = TextMuted
                        )
                    }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .pullRefresh(pullRefreshState)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
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
                        directorySyncLabel(isRefreshing, lastSuccessfulSyncAt, refreshError),
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

            PullRefreshIndicator(
                refreshing = isRefreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                contentColor = RoseGoldPrimary,
                backgroundColor = CardSurface
            )
        }
    }
}

private fun directorySyncLabel(
    isRefreshing: Boolean,
    lastSuccessfulSyncAt: Long,
    refreshError: String?
): String = when {
    isRefreshing -> "Updating directory…"
    refreshError != null -> "Offline — showing cached data"
    lastSuccessfulSyncAt == 0L -> "Offline cache"
    System.currentTimeMillis() - lastSuccessfulSyncAt < 60_000L -> "Synced just now"
    else -> "Synced ${(System.currentTimeMillis() - lastSuccessfulSyncAt) / 60_000L} min ago"
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
