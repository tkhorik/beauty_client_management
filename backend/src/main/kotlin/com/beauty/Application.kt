package com.beauty

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
}
