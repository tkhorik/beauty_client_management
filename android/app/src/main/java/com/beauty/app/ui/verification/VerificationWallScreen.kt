package com.beauty.app.ui.verification

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.data.BeautyRepository
import com.beauty.app.ui.theme.DarkObsidian
import com.beauty.app.ui.theme.CardSurface
import com.beauty.app.ui.theme.RoseGoldPrimary
import com.beauty.app.ui.theme.TextLight
import com.beauty.app.ui.theme.TextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long the resend button stays disabled after a successful send.
 *
 * The backend allows three mail-sending calls a minute per IP, shared by
 * everyone in the salon. The cooldown exists so one impatient user cannot spend
 * that budget and hand a colleague a 429 for a button they pressed once.
 */
private const val RESEND_COOLDOWN_MS = 60_000L

/**
 * What an unverified account sees instead of the app.
 *
 * The server refuses every organization-scoped request from a restricted
 * account, reads included, so there is no client list to render behind this —
 * the alternative is a permanently empty directory with a banner over it, which
 * looks like a broken app rather than an account that needs one action taken.
 *
 * Deliberately mirrors `web/src/components/VerificationWall.tsx` in what it
 * offers, because a salon owner who reads the explanation on their phone and
 * then opens the web app should not have to work out that they are looking at
 * the same situation: which address the mail went to, that spam is the usual
 * culprit, resend, re-check, and sign out for a mistyped address.
 *
 * Every action here calls a route that is deliberately outside the server's
 * verification gate. If one of them starts answering 403, this screen becomes a
 * dead end — `EmailVerificationEnforcementTest` pins each of those routes.
 */
@Composable
fun VerificationWallScreen(
    email: String?,
    onResend: suspend () -> Result<Unit>,
    onRecheck: suspend () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var cooldownUntil by remember { mutableLongStateOf(0L) }
    var coolingDown by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    // Compose does not recompose on the passage of time, so without this the
    // button would stay disabled until some unrelated state change happened to
    // redraw it — which, on a screen the user is only reading, might be never.
    LaunchedEffect(cooldownUntil) {
        coolingDown = cooldownUntil > System.currentTimeMillis()
        if (coolingDown) {
            delay((cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0L))
            coolingDown = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkObsidian),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CardSurface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            com.beauty.app.ui.i18n.LanguageSelector(
                accountId = com.beauty.app.AppContainer.tokenStore(androidx.compose.ui.platform.LocalContext.current).getAccountId(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Icon(
                Icons.Default.Email,
                contentDescription = null,
                tint = RoseGoldPrimary,
                modifier = Modifier.size(40.dp)
            )

            Spacer(Modifier.height(16.dp))

            Text(
                stringResource(com.beauty.app.R.string.confirm_your_email),
                color = TextLight,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                stringResource(com.beauty.app.R.string.ui2_your_account_is_ready_it_just_needs_a_confirmed_email_address_bef),
                color = TextMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Text(stringResource(com.beauty.app.R.string.ui2_we_sent_a_confirmation_link_to), color = TextMuted, fontSize = 13.sp)
            Text(
                email ?: stringResource(com.beauty.app.R.string.your_address),
                color = TextLight,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            // The single most useful sentence on the screen: spam is where
            // confirmation mail goes, and it is the one cause the user can fix
            // without contacting anyone.
            Text(
                stringResource(com.beauty.app.R.string.verification_wall_help),
                color = TextMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )

            status?.let {
                Spacer(Modifier.height(12.dp))
                Text(com.beauty.app.ui.i18n.verificationMessage(it), color = RoseGoldPrimary, fontSize = 13.sp, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(20.dp))

            // The re-check is the primary action, not the resend. Most people
            // reaching this screen have already had the mail and are coming
            // back after clicking it; offering "send another" first would spend
            // a rate-limit slot on a problem they no longer have.
            Button(
                onClick = {
                    scope.launch {
                        checking = true
                        onRecheck()
                        checking = false
                        status = "RESOURCE:ui2_still_unconfirmed_open_the_link_then_try_again"
                    }
                },
                enabled = !checking,
                colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (checking) stringResource(com.beauty.app.R.string.checking) else stringResource(com.beauty.app.R.string.ui2_i_ve_confirmed_my_email))
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = {
                    scope.launch {
                        onResend()
                            .onSuccess {
                                status = "RESOURCE:ui2_new_link_sent_it_can_take_a_minute_to_arrive"
                                cooldownUntil = System.currentTimeMillis() + RESEND_COOLDOWN_MS
                            }
                            .onFailure {
                                status = "RESOURCE:ui2_couldn_t_send_the_link_check_your_connection"
                            }
                    }
                },
                enabled = !coolingDown,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (coolingDown) stringResource(com.beauty.app.R.string.link_sent) else stringResource(com.beauty.app.R.string.send_the_link_again),
                    color = if (coolingDown) TextMuted else RoseGoldPrimary
                )
            }

            // The way out for someone who registered with a typo. Without it
            // the only remedy is reinstalling the app, and the address shown
            // above is exactly what tells them they need one.
            TextButton(onClick = onLogout) {
                Text(stringResource(com.beauty.app.R.string.wrong_address_sign_out), color = TextMuted, fontSize = 13.sp)
            }
        }
    }
}

/**
 * Stands between a signed-in session and the organization-scoped screens.
 *
 * Loads the profile once and renders either [content] or the wall. While the
 * profile is in flight it renders nothing rather than guessing: flashing the
 * wall at a verified user for the length of a round trip would be alarming, and
 * flashing the app at a restricted one would show a screen full of failures.
 *
 * A profile read that *fails* falls through to [content]. Being unable to reach
 * the server says nothing about whether the address is confirmed, and this
 * app's whole offline story — Room cache, queued visits — depends on a dropped
 * connection not looking like a policy decision. The server is still the
 * authority: every request the content makes is refused independently if the
 * account really is restricted.
 */
@Composable
fun VerificationGate(
    repository: BeautyRepository,
    onLogout: () -> Unit,
    refreshKey: Int = 0,
    content: @Composable () -> Unit
) {
    var standing by remember { mutableStateOf<VerificationStanding?>(null) }
    var email by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        runCatching { repository.getCurrentUser() }
            .onSuccess { user ->
                email = user.email
                standing = standingFor(user.emailVerified, user.verificationDeadline)
            }
            .onFailure { standing = VerificationStanding.VERIFIED }
    }

    LaunchedEffect(repository, refreshKey) { load() }

    when (standing) {
        null -> Unit
        VerificationStanding.RESTRICTED -> VerificationWallScreen(
            email = email,
            onResend = { repository.resendVerificationEmail() },
            onRecheck = { load() },
            onLogout = onLogout
        )
        else -> content()
    }
}
