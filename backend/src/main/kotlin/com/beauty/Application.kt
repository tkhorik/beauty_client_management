package com.beauty

import com.beauty.config.AppSettings
import com.beauty.db.DatabaseFactory
import com.beauty.plugins.*
import io.ktor.server.application.*
import io.ktor.server.netty.*

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
}
