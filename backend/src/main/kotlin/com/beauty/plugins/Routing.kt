package com.beauty.plugins

import com.beauty.config.AppSettings
import com.beauty.routes.attachmentRoutes
import com.beauty.routes.authRoutes
import com.beauty.routes.clientRoutes
import com.beauty.routes.visitRoutes
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.auth.*

fun Application.configureRouting() {
    val settings = AppSettings(environment.config)

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)

        if (settings.isProduction) {
            // Production serves the web app and the API from the same origin
            // through the edge Nginx, so no cross-origin request is legitimate
            // unless it is explicitly listed in ALLOWED_ORIGINS.
            settings.allowedOrigins.forEach { origin ->
                val parsed = Url(origin)
                allowHost(parsed.hostWithPort, schemes = listOf(parsed.protocol.name))
            }
        } else {
            // Local development: Vite dev server, Android emulator, curl.
            anyHost()
        }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            // Never leak exception detail (stack traces, SQL, file paths) to the
            // client in production; log it server-side instead.
            call.application.log.error("Unhandled exception on ${call.request.local.uri}", cause)
            val message = if (settings.isProduction) {
                "Internal Server Error"
            } else {
                cause.localizedMessage ?: "Internal Server Error"
            }
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to message))
        }
    }

    routing {
        get("/") {
            call.respondText("Beauty Client & Visit Management API v1.0.0 is running.")
        }

        // Lightweight endpoint for the Docker healthcheck and the edge proxy.
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        // Serve uploaded attachment files from the configured upload directory.
        staticFiles("/uploads", settings.uploadDir)

        authRoutes()

        authenticate("auth-jwt") {
            clientRoutes()
            visitRoutes()
            attachmentRoutes()
        }
    }
}
