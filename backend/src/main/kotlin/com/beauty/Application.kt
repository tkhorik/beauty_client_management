package com.beauty

import com.beauty.auth.MembershipService
import com.beauty.auth.OneTimeTokenService
import com.beauty.auth.RefreshTokenService
import com.beauty.config.AppSettings
import com.beauty.db.DatabaseFactory
import com.beauty.plugins.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import kotlinx.coroutines.launch

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    val settings = AppSettings(environment.config)
    // Refuse to boot in production with development secrets or an H2 database.
    settings.validateOrFail()
    log.info("Starting Aura Beauty Log API in '{}' mode", settings.environment)

    DatabaseFactory.init(settings)
    configureSerialization()
    configureSecurity()
    configureRouting()

    // Refresh tokens rotate on every use, so the table gains a row per refresh
    // — with a 15-minute access token that is roughly 100 rows per user per
    // day. Purging on boot keeps it bounded without needing a scheduler; the
    // service retains a grace period so reuse detection still works.
    launch {
        runCatching { RefreshTokenService(settings.refreshTokenDays).purgeExpired() }
            .onFailure { log.warn("Could not purge expired refresh tokens: {}", it.message) }
    }

    // Same reasoning for the verification and password-reset tokens: every
    // registration mints one, every reset request mints another, and nothing
    // ever removed them. Unlike refresh tokens there is no reuse detection to
    // preserve, so the service's own grace period is the only retention rule.
    launch {
        runCatching { OneTimeTokenService().purgeExpired() }
            .onFailure { log.warn("Could not purge expired one-time tokens: {}", it.message) }
    }

    // Promote the configured addresses to SUPER_ADMIN. Done here rather than
    // through an API because an endpoint capable of granting unrestricted
    // access to every organization is an endpoint worth attacking. Only ever
    // promotes — see AppSettings.superAdminEmails for why it never demotes.
    launch {
        runCatching { MembershipService.bootstrapSuperAdmins(settings.superAdminEmails) }
            .onSuccess { promoted ->
                if (promoted > 0) log.info("Promoted {} account(s) to SUPER_ADMIN from SUPER_ADMIN_EMAILS.", promoted)
            }
            .onFailure { log.warn("Could not apply SUPER_ADMIN_EMAILS: {}", it.message) }
    }
}
