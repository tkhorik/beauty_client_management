package com.beauty.plugins

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
import java.io.File

fun Application.configureRouting() {
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.AccessControlAllowOrigin)
        anyHost()
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.localizedMessage ?: "Internal Server Error")))
        }
    }

    routing {
        get("/") {
            call.respondText("Beauty Client & Visit Management API v1.0.0 is running.")
        }

        // Serve static uploads folder
        staticFiles("/uploads", File("uploads"))

        authRoutes()

        authenticate("auth-jwt") {
            clientRoutes()
            visitRoutes()
            attachmentRoutes()
        }
    }
}
