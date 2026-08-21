package com.beauty.app.ui.verification

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.data.BeautyRepository
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
 * The standing notice that this account's address is unconfirmed, shown *over a
 * working app*.
 *
 * Renders only for [VerificationStanding.WARNING] — an account that predates
 * enforcement and is still inside its grace window. A registration made under
 * the rule is restricted from its first request and gets
 * [VerificationWallScreen] instead, put in front of this screen by
 * [VerificationGate]; a banner there would sit above a directory the server
 * refuses to fill.
 *
 * Renders nothing when the backend advertises no deadline — a deployment with
 * enforcement switched off has nothing to warn about, and a client that nags
 * anyway would be inventing a restriction the server does not apply.
 *
 * The profile is fetched here rather than passed down because the screen that
 * hosts this banner is the client directory, which otherwise has no reason to
 * know anything about the signed-in user. Keeping the fetch local means the
 * banner can be dropped onto another screen without threading state through it.
 */
@Composable
fun VerificationBanner(
    repository: BeautyRepository,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf<String?>(null) }
    var verified by remember { mutableStateOf(true) }
    var deadline by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var cooldownUntil by remember { mutableLongStateOf(0L) }
    var checking by remember { mutableStateOf(false) }

    suspend fun load() {
        runCatching { repository.getCurrentUser() }.onSuccess { user ->
            email = user.email
            verified = user.emailVerified
            deadline = user.verificationDeadline
        }
        // A failed profile read leaves the banner as it was. Being unable to
        // reach the server says nothing about whether the address is confirmed,
        // and showing a restriction warning because the network dropped would
        // be actively misleading.
    }

    LaunchedEffect(Unit) { load() }

    // RESTRICTED is the wall's job and VERIFIED has nothing to say, so this
    // composable draws exactly one of the three states.
    if (standingFor(verified, deadline) != VerificationStanding.WARNING) return
    val daysLeft = remember(deadline) { daysUntil(deadline!!) }
    var coolingDown by remember { mutableStateOf(false) }

    // Compose does not recompose on the passage of time, so without this the
    // button would read "Link sent" until some unrelated state change happened
    // to redraw it — which, on a screen the user is only reading, might be
    // never. The effect is keyed on the cooldown, so it re-arms per send.
    LaunchedEffect(cooldownUntil) {
        coolingDown = cooldownUntil > System.currentTimeMillis()
        if (coolingDown) {
            delay((cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0L))
            coolingDown = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardSurface)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Email,
                contentDescription = null,
                tint = RoseGoldPrimary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Confirm your email within $daysLeft day${if (daysLeft == 1L) "" else "s"}",
                color = TextLight,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = "We sent a link to ${email ?: "your address"} — check your spam folder if it " +
                "hasn't arrived. After that, using Aura needs a confirmed address.",
            color = TextMuted,
            fontSize = 12.sp
        )

        status?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = RoseGoldPrimary, fontSize = 12.sp)
        }

        Row {
            TextButton(
                enabled = !coolingDown,
                onClick = {
                    scope.launch {
                        repository.resendVerificationEmail()
                            .onSuccess {
                                status = "Link sent — check your inbox and spam folder."
                                cooldownUntil = System.currentTimeMillis() + RESEND_COOLDOWN_MS
                            }
                            .onFailure {
                                status = "Couldn't send the link. Check your connection."
                            }
                    }
                }
            ) {
                Text(
                    if (coolingDown) "Link sent" else "Resend link",
                    color = if (coolingDown) TextMuted else RoseGoldPrimary,
                    fontSize = 13.sp
                )
            }

            // The link is opened in a mail client, often on another device, so
            // this screen has no way to notice on its own. Asking the user to
            // restart the app would work and would look like a broken app.
            TextButton(
                enabled = !checking,
                onClick = {
                    scope.launch {
                        checking = true
                        load()
                        checking = false
                        if (!verified) status = "Still unconfirmed — try the link again."
                    }
                }
            ) {
                Text(
                    if (checking) "Checking…" else "I've confirmed",
                    color = RoseGoldPrimary,
                    fontSize = 13.sp
                )
            }
        }
    }
}
