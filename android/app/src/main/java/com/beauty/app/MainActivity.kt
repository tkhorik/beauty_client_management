package com.beauty.app

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.beauty.app.ui.AppLink
import com.beauty.app.ui.AppLinkInbox
import com.beauty.app.ui.AppLinkViewModel
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import com.beauty.app.ui.admin.AdminScreen
import com.beauty.app.ui.admin.AdminViewModel
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
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
import com.beauty.app.ui.auth.ResetPasswordScreen
import com.beauty.app.ui.auth.LoginScreen
import com.beauty.app.ui.auth.RegisterScreen
import com.beauty.app.ui.client.ClientDetailScreen
import com.beauty.app.ui.client.ClientDetailViewModel
import com.beauty.app.ui.client.EditClientScreen
import com.beauty.app.ui.client.EditClientViewModel
import com.beauty.app.ui.client.ClientDirectoryScreen
import com.beauty.app.ui.client.ClientDirectoryViewModel
import com.beauty.app.ui.org.OrganizationScreen
import com.beauty.app.ui.org.OrganizationViewModel
import com.beauty.app.ui.about.AboutScreen
import com.beauty.app.ui.settings.SettingsScreen
import com.beauty.app.ui.settings.SettingsViewModel
import com.beauty.app.ui.verification.VerificationBanner
import com.beauty.app.ui.verification.VerificationGate
import com.beauty.app.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class DirectoryClient(
    val id: String,
    val name: String,
    val phone: String,
    val tag: String,
    val visitsCount: Int
)

class MainActivity : AppCompatActivity() {
    private val links by lazy { ViewModelProvider(this)[AppLinkViewModel::class.java] }

    private fun receiveLinks(incoming: Intent) {
        // The external entry component hands off through memory, never Intent extras.
        incoming.data = null
        incoming.replaceExtras(null as Bundle?)
        intent = incoming
        AppLinkInbox.drain().forEach(links::receive)
    }

    override fun onNewIntent(intent: Intent) {
        receiveLinks(intent)
        super.onNewIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiveLinks(intent)
        setContent {
            BeautyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost(links)
                }
            }
        }
    }
}

@Composable
internal fun AppNavHost(links: AppLinkViewModel) {
    val context = LocalContext.current
    val tokenStore = remember { AppContainer.tokenStore(context) }
    val orgStore = remember { AppContainer.orgStore(context) }
    val accountId by tokenStore.accountFlow.collectAsState()
    val repository = remember(accountId) { AppContainer.repository(context, tokenStore) }
    val database = remember(accountId) { BeautyDatabaseProvider.get(context, accountId) }
    val updateManager = remember { AppContainer.updateManager(context) }
    val updateState by updateManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val languageManager = remember { AppContainer.languageManager(context) }
    languageManager.activeAccountId = accountId

    DisposableEffect(lifecycleOwner, accountId, repository) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (accountId != null) scope.launch {
                    languageManager.detectExternalOverride(accountId)
                    languageManager.synchronize(repository, accountId)
                }
            }
        }
        connectivity.registerDefaultNetworkCallback(networkCallback)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                scope.launch { updateManager.checkForUpdates(force = false) }
                languageManager.detectExternalOverride(accountId)
                if (accountId != null) scope.launch { languageManager.synchronize(repository, accountId) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            connectivity.unregisterNetworkCallback(networkCallback)
        }
    }

    LaunchedEffect(accountId) {
        languageManager.detectExternalOverride(accountId)
        if (accountId != null) languageManager.synchronize(repository, accountId)
    }

    val navController = rememberNavController()
    val startDestination = rememberSaveable { if (tokenStore.getToken() != null) "clients" else "login" }

    // AuthViewModel factory — uses an auth-capable Ktor client (no token yet, but endpoint is public)
    val authViewModel: AuthViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val api = com.beauty.app.data.api.KtorBeautyApi(AppContainer.buildLoginClient())
                return AuthViewModel(api, tokenStore, orgStore) {
                    languageManager.current(null).preference.takeUnless { it == "system" }
                } as T
            }
        }
    )

    // Hoisted to the NavHost so the "clients" and "organizations" destinations
    // share one instance: switching salons on the second must be visible to the
    // first without a reload.
    val orgViewModel: OrganizationViewModel = viewModel(
        key = links.organizationKey(accountId),
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                OrganizationViewModel(repository, orgStore) as T
        }
    )

    val publicApi = remember { com.beauty.app.data.api.KtorBeautyApi(AppContainer.buildLoginClient()) }

    // Links that finish on the organization screen once there is a session.
    fun organizationScreenPending() = links.pendingOrganizationToken != null ||
        links.pendingJoinSlug != null || links.pendingMembersOrgId != null || links.pendingInviteToken != null
    fun openHome() {
        navController.navigate(if (tokenStore.getToken() == null) "login"
            else if (organizationScreenPending()) "organizations" else "clients") {
            popUpTo(0) { inclusive = true }
        }
    }
    fun afterSignIn() {
        navController.navigate(if (organizationScreenPending()) "organizations" else "clients") {
            popUpTo(0) { inclusive = true }
        }
    }

    // Consume before dispatch. A recomposition or rotation cannot redeem it again.
    // Verification deliveries are serialized, while reset/create links can replace a form.
    LaunchedEffect(links.inbox, links.verifying) {
        if (!links.verifying) {
            when (val link = links.take()) {
                is AppLink.ResetPassword -> {
                    authViewModel.resetState()
                    links.reset(link.token)
                    navController.navigate("reset-password") { popUpTo(0) { inclusive = true } }
                }
                AppLink.ForgotPassword -> {
                    links.clearReset()
                    navController.navigate("forgot-password") { popUpTo(0) { inclusive = true } }
                }
                is AppLink.VerifyEmail -> {
                    links.verify(link, publicApi) {
                        if (tokenStore.getToken() != null) repository.getCurrentUser()
                    }
                    navController.navigate("verify-email") { popUpTo(0) { inclusive = true } }
                }
                is AppLink.CreateOrganization -> {
                    links.holdOrganization(link.token)
                    navController.navigate(if (tokenStore.getToken() != null) "organizations" else "login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
                is AppLink.JoinOrganization -> {
                    // Signed out, this is almost always someone without an
                    // account yet: open registration with the handle filled in.
                    // Signed in, the organization screen pre-fills its join form.
                    if (tokenStore.getToken() != null) {
                        links.holdJoin(link.slug)
                        navController.navigate("organizations") { popUpTo(0) { inclusive = true } }
                    } else {
                        authViewModel.registerOrganizationSlug = link.slug
                        navController.navigate("register") { popUpTo("login") }
                    }
                }
                is AppLink.AcceptInvite -> {
                    // Held until there is a session; the organization screen
                    // then asks the user to confirm before anything is joined.
                    links.holdInvite(link.token)
                    navController.navigate(if (tokenStore.getToken() != null) "organizations" else "login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
                is AppLink.ManageMembers -> {
                    links.holdMembers(link.organizationId)
                    navController.navigate(if (tokenStore.getToken() != null) "organizations" else "login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
                AppLink.Home -> openHome()
                null -> Unit
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {

        composable("login") {
            LoginScreen(
                viewModel = authViewModel,
                onLoginSuccess = { afterSignIn() },
                onNavigateToRegister = { navController.navigate("register") },
                onNavigateToForgotPassword = { navController.navigate("forgot-password") }
            )
        }

        composable("forgot-password") {
            ForgotPasswordScreen(
                viewModel = authViewModel,
                onNavigateBackToLogin = { openHome() },
                onEnterResetLink = { links.reset(null); navController.navigate("reset-password") }
            )
        }

        composable("reset-password") {
            key(links.resetGeneration) { ResetPasswordScreen(
                viewModel = authViewModel,
                initialToken = links.resetToken,
                onTokenUsed = { links.clearReset() },
                onNavigateBackToLogin = {
                    links.clearReset()
                    openHome()
                },
                onRequestNewLink = {
                    links.clearReset()
                    navController.navigate("forgot-password") {
                        popUpTo("forgot-password") { inclusive = true }
                    }
                }
            ) }
        }

        composable("verify-email") {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                Text(if (links.verifying) stringResource(com.beauty.app.R.string.ui2_confirming_your_email) else links.verificationMessage?.let { com.beauty.app.ui.i18n.localizedMessage(it) } ?: stringResource(com.beauty.app.R.string.ui2_reopen_your_verification_link_to_continue))
                Button(onClick = { openHome() }, enabled = !links.verifying) { Text(stringResource(R.string.continue_action)) }
            }
        }

        composable("register") {
            RegisterScreen(
                viewModel = authViewModel,
                onRegisterSuccess = { afterSignIn() },
                onNavigateToLogin = { if (!navController.popBackStack()) openHome() },
                offerOrganizationField = links.pendingOrganizationToken == null
            )
        }

        composable("clients") {
            // Wrapped in the verification gate rather than gated inside
            // BeautyAppScreen: a restricted account is refused the organization
            // list as well as the clients, so the branch below would send it to
            // the organization picker — a screen that cannot load either, and
            // that explains nothing about why.
            VerificationGate(
                repository = repository,
                refreshKey = links.profileRevision,
                onLogout = {
                    authViewModel.logout {
                        navController.navigate("login") { popUpTo(0) { inclusive = true } }
                    }
                }
            ) content@ {
                // Clients belong to an organization, so with none selected there is
                // nothing to show and every request would come back
                // MISSING_ORGANIZATION. Send the user somewhere they can act on it
                // instead of to an empty list that never loads.
                val activeOrgId = orgViewModel.activeOrgId
                if (!orgViewModel.loading && activeOrgId == null) {
                    OrganizationScreen(
                        viewModel = orgViewModel,
                        onDone = null,
                        onOpenAdmin = { navController.navigate("admin") },
                        onLogout = {
                            authViewModel.logout {
                                navController.navigate("login") { popUpTo(0) { inclusive = true } }
                            }
                        }
                    )
                    return@content
                }

                val selectedOrgId = activeOrgId ?: return@content

                val directoryViewModel: ClientDirectoryViewModel = viewModel(
                    key = "directory_${selectedOrgId}_${accountId}",
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            ClientDirectoryViewModel(selectedOrgId, repository, database.clientDao()) as T
                    }
                )
                // Removed or revoked while away: the directory has already
                // purged this organization's cache. Re-read the list (which
                // re-selects or clears the active organization) and let the
                // user pick where to go next.
                LaunchedEffect(directoryViewModel.membershipLost) {
                    if (directoryViewModel.membershipLost) {
                        orgViewModel.refresh()
                        navController.navigate("organizations") { popUpTo(0) { inclusive = true } }
                    }
                }
                ClientDirectoryScreen(
                    viewModel = directoryViewModel,
                    repository = repository,
                    organizationName = orgViewModel.current?.name ?: stringResource(R.string.organization),
                    onClientTap = { clientId -> navController.navigate("client/$clientId") },
                    onNewClient = { navController.navigate("new_client") },
                    onLogVisit = { clientId -> navController.navigate("client/$clientId?logVisit=true") },
                    onSettings = { navController.navigate("settings") },
                    onAbout = { navController.navigate("about") },
                    onOrganizations = { navController.navigate("organizations") },
                    pendingRequestCount = orgViewModel.current?.pendingRequestCount ?: 0,
                    onAdmin = if (orgViewModel.isSuperAdmin) ({ navController.navigate("admin") }) else null,
                    onLogout = {
                        authViewModel.logout { navController.navigate("login") { popUpTo(0) { inclusive = true } } }
                    },
                    updateManager = updateManager,
                    onOpenUpdateDialog = { updateManager.reopenUpdateDialog() }
                )
            }
        }

        composable("organizations") {
            // Same gate: joining or switching organizations is refused for an
            // unverified account, so this destination has nothing to show it
            // either.
            VerificationGate(
                repository = repository,
                refreshKey = links.profileRevision,
                onLogout = {
                    authViewModel.logout {
                        navController.navigate("login") { popUpTo(0) { inclusive = true } }
                    }
                }
            ) {
                LaunchedEffect(orgViewModel, links.pendingOrganizationToken) {
                    links.takeOrganization()?.let { orgViewModel.checkCreationToken(it) }
                }
                LaunchedEffect(orgViewModel, links.pendingJoinSlug) {
                    links.takeJoin()?.let { orgViewModel.joinDraft = it }
                }
                LaunchedEffect(orgViewModel, links.pendingInviteToken) {
                    links.takeInvite()?.let { orgViewModel.offerInvite(it) }
                }
                LaunchedEffect(orgViewModel, links.pendingMembersOrgId) {
                    links.takeMembers()?.let { orgViewModel.focusMembers(it) }
                }
                OrganizationScreen(
                    viewModel = orgViewModel,
                    onDone = { if (!navController.popBackStack()) openHome() },
                    onLogout = {
                        authViewModel.logout {
                            navController.navigate("login") { popUpTo(0) { inclusive = true } }
                        }
                    }
                )
            }
        }

        /**
         * System-wide administration. Deliberately *not* wrapped in
         * `VerificationGate`: a super admin passes the verification policy
         * anyway, and this screen is organization-independent, so the gate's
         * fallback — "go pick an organization" — would be the wrong advice
         * here. The server's `requireSuperAdmin()` is the real guard.
         */
        composable("admin") {
            val adminViewModel: AdminViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        AdminViewModel(repository) as T
                }
            )
            AdminScreen(
                viewModel = adminViewModel,
                onDone = {
                    orgViewModel.refresh()
                    navController.popBackStack()
                },
                onOpenOrganization = { organization ->
                    orgViewModel.select(organization.id)
                    orgViewModel.refresh()
                    navController.navigate("clients") { popUpTo("admin") { inclusive = true } }
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
                accountId = accountId,
                languageManager = languageManager,
                onLanguageSelected = { selectedAccount ->
                    if (selectedAccount != null) scope.launch {
                        languageManager.synchronize(repository, selectedAccount)
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("about") {
            AboutScreen(
                updateManager = updateManager,
                onCheckForUpdates = { scope.launch { updateManager.checkForUpdates(force = true) } },
                onOpenUpdateDialog = { updateManager.reopenUpdateDialog() },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "client/{clientId}?logVisit={logVisit}",
            arguments = listOf(
                navArgument("clientId") { type = NavType.StringType },
                navArgument("logVisit") { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStackEntry ->
            val clientId = backStackEntry.arguments!!.getString("clientId")!!
            val logVisit = backStackEntry.arguments!!.getBoolean("logVisit")
            val detailOrgId = orgViewModel.activeOrgId ?: return@composable
            VerificationGate(
                repository = repository,
                refreshKey = links.profileRevision,
                onLogout = {
                    authViewModel.logout {
                        navController.navigate("login") { popUpTo(0) { inclusive = true } }
                    }
                }
            ) {
                val detailViewModel: ClientDetailViewModel = viewModel(
                    key = "detail_${detailOrgId}_$clientId",
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            ClientDetailViewModel(clientId, detailOrgId, repository, database.clientDao(), database.visitDao()) as T
                    }
                )
                ClientDetailScreen(
                    viewModel = detailViewModel,
                    repository = repository,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate("edit_client/$clientId") },
                    openVisitForm = logVisit
                )
            }
        }

        composable("new_client") {
            val createOrgId = orgViewModel.activeOrgId ?: return@composable
            val createVm: EditClientViewModel = viewModel(
                key = "new_${createOrgId}_${accountId}",
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        EditClientViewModel(null, createOrgId, repository, database.clientDao()) as T
                }
            )
            EditClientScreen(viewModel = createVm, onBack = { navController.popBackStack() })
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

    val showUpdateDialog = when (val s = updateState) {
        is com.beauty.app.updater.UpdateState.Available -> !s.dismissed
        is com.beauty.app.updater.UpdateState.Downloading -> true
        is com.beauty.app.updater.UpdateState.ReadyToInstall -> true
        is com.beauty.app.updater.UpdateState.Error -> s.release != null
        else -> false
    }

    if (showUpdateDialog) {
        com.beauty.app.ui.updater.UpdateDialog(
            state = updateState,
            currentVersion = updateManager.currentVersionName,
            distributionMode = updateManager.getDistributionMode(),
            onDismiss = { updateManager.dismissCurrentUpdate() },
            onStartDownload = { updateManager.startDownload(scope) },
            onCancelDownload = { updateManager.cancelDownload() },
            onInstall = { updateManager.installOrOpenStore(context) },
            onOpenPlayStore = { com.beauty.app.updater.UpdateInstaller.openGooglePlayStore(context) },
            onRequestPermission = {
                context.startActivity(com.beauty.app.updater.UpdateInstaller.createInstallPermissionIntent(context))
            },
            needsInstallPermission = !com.beauty.app.updater.UpdateInstaller.canInstallApk(context)
        )
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
    /**
     * Null for an ordinary account, which is how the shield stays hidden.
     *
     * A capability hint, not a control: `requireSuperAdmin()` refuses every
     * endpoint behind that screen regardless of whether this app draws the
     * button.
     */
    onOpenAdmin: (() -> Unit)? = null,
    onLogout: () -> Unit
) {
    var searchQuery by remember(organizationId) { mutableStateOf("") }
    var showVisitClientPicker by remember(organizationId) { mutableStateOf(false) }
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
                        refreshError = "NETWORK_ERROR"
                    }
                isRefreshing = false
            }
        }
    }
    if (showVisitClientPicker) {
        AlertDialog(
            onDismissRequest = { showVisitClientPicker = false },
            title = { Text(stringResource(R.string.choose_a_client)) },
            text = {
                if (clients.isEmpty()) {
                    Text(stringResource(com.beauty.app.R.string.ui2_no_clients_available_refresh_the_directory_or_create_a_client_on_))
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(clients, key = { it.id }) { client ->
                            TextButton(onClick = {
                                showVisitClientPicker = false
                                onClientTap(client.id)
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("${client.name} • ${client.phone}")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showVisitClientPicker = false }) { Text(stringResource(com.beauty.app.R.string.cancel)) } }
        )
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
                            stringResource(com.beauty.app.R.string.ui2_aura_beauty_mobile),
                            color = RoseGoldPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            stringResource(com.beauty.app.R.string.ui2_client_procedure_logging_studio),
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenOrganizations) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = stringResource(com.beauty.app.R.string.organizations),
                            tint = TextMuted
                        )
                    }
                    if (onOpenAdmin != null) {
                        IconButton(onClick = onOpenAdmin) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = stringResource(R.string.admin_panel),
                                tint = RoseGoldPrimary
                            )
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.account_settings),
                            tint = TextMuted
                        )
                    }
                    IconButton(onClick = onLogout) {
                        Icon(
                            Icons.Default.ExitToApp,
                            contentDescription = stringResource(com.beauty.app.R.string.sign_out),
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
                onClick = { showVisitClientPicker = true },
                containerColor = RoseGoldPrimary,
                contentColor = Color.Black
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.log_visit))
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
                // Renders nothing for a verified account, or when this
                // deployment has enforcement switched off.
                VerificationBanner(
                    repository = repository,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(com.beauty.app.R.string.ui2_search_clients_or_procedure_specs), color = TextMuted) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = stringResource(com.beauty.app.R.string.search), tint = TextMuted)
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
                        stringResource(R.string.client_directory),
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

@Composable
private fun directorySyncLabel(
    isRefreshing: Boolean,
    lastSuccessfulSyncAt: Long,
    refreshError: String?
): String = when {
    isRefreshing -> stringResource(com.beauty.app.R.string.ui2_updating_directory)
    refreshError != null -> stringResource(com.beauty.app.R.string.ui2_offline_showing_cached_data)
    lastSuccessfulSyncAt == 0L -> stringResource(com.beauty.app.R.string.ui2_offline_cache)
    System.currentTimeMillis() - lastSuccessfulSyncAt < 60_000L -> stringResource(com.beauty.app.R.string.ui2_synced_just_now)
    else -> pluralStringResource(com.beauty.app.R.plurals.synced_minutes, ((System.currentTimeMillis() - lastSuccessfulSyncAt) / 60_000L).toInt(), ((System.currentTimeMillis() - lastSuccessfulSyncAt) / 60_000L).toInt())
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
                        pluralStringResource(com.beauty.app.R.plurals.visits_count, client.visitsCount, client.visitsCount),
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
                Text(stringResource(R.string.view_visits), color = ChampagneAccent, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ClientEntity.toDirectoryClient(): DirectoryClient {
    val tags = runCatching {
        Json.decodeFromString<List<String>>(tagsJson).joinToString(" • ")
    }.getOrDefault("")
    return DirectoryClient(
        id = id,
        name = name,
        phone = phone,
        tag = tags.ifBlank { stringResource(R.string.no_tags) },
        visitsCount = totalVisits
    )
}
