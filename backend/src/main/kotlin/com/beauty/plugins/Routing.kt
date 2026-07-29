package com.beauty.plugins

import com.beauty.config.AppSettings
import com.beauty.routes.attachmentRoutes
import com.beauty.routes.authRoutes
import com.beauty.routes.authenticatedAuthRoutes
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
        // Lets a client declare that its refresh token should be delivered as
        // an httpOnly cookie rather than in the response body.
        allowHeader("X-Auth-Transport")

        // Required for the browser to send and store the refresh cookie on a
        // cross-origin request. Note this is incompatible with `anyHost()`:
        // browsers reject `Access-Control-Allow-Origin: *` on a credentialed
        // request, which is why the development branch below lists origins
        // explicitly instead.
        allowCredentials = true

        if (settings.isProduction) {
            // Production serves the web app and the API from the same origin
            // through the edge Nginx, so no cross-origin request is legitimate
            // unless it is explicitly listed in ALLOWED_ORIGINS.
            settings.allowedOrigins.forEach { origin ->
                val parsed = Url(origin)
                allowHost(parsed.hostWithPort, schemes = listOf(parsed.protocol.name))
            }
        } else {
            // Local development: the Vite dev server runs on a different port
            // from the API, so this genuinely is cross-origin. Listed
            // explicitly rather than `anyHost()` because credentialed requests
            // (the refresh cookie) cannot use a wildcard origin.
            //
            // Native clients and curl are unaffected — CORS is a browser
            // mechanism and is never applied to them.
            listOf("127.0.0.1", "localhost").forEach { host ->
                allowHost("$host:5174", schemes = listOf("http"))
                allowHost("$host:5173", schemes = listOf("http"))
            }
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
            authenticatedAuthRoutes()
            clientRoutes()
            visitRoutes()
            attachmentRoutes()
        }
    }
}
