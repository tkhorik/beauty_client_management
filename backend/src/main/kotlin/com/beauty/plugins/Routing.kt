package com.beauty.plugins

import com.beauty.config.AppSettings
import com.beauty.routes.adminRoutes
import com.beauty.routes.attachmentRoutes
import com.beauty.routes.authRoutes
import com.beauty.routes.authenticatedAuthRoutes
import com.beauty.routes.clientRoutes
import com.beauty.routes.organizationRoutes
import com.beauty.routes.userRoutes
import com.beauty.routes.visitRoutes
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.origin
import kotlin.time.Duration.Companion.seconds

/** Tight bucket for endpoints that send mail to an address the caller does not own. */
const val RATE_LIMIT_EMAIL = "auth-email"

/** Looser bucket for credential endpoints. */
const val RATE_LIMIT_AUTH = "auth-credentials"

/**
 * The bucket key for a request: the real client IP.
 *
 * `request.origin.remoteHost` is the value XForwardedHeaders has already
 * resolved from X-Forwarded-For, so behind the proxy this is the browser's
 * address rather than the Docker network gateway. Trusting that header is safe
 * here for the same reason it is safe elsewhere in this file: the backend binds
 * no host port, so only Nginx can reach it to set one.
 */
private fun ApplicationCall.clientKey(): String = request.origin.remoteHost

fun Application.configureRouting() {
    val settings = AppSettings(environment.config)

    // Nginx terminates TLS and forwards to this process over plain HTTP on the
    // internal Docker network. Without this plugin the app believes every
    // request arrived unencrypted, which breaks client-IP logging and makes
    // Ktor refuse to set `Secure` cookies ("You should set secure cookie only
    // via secure transport") even though the browser-facing connection is HTTPS.
    //
    // `deploy/nginx/proxy_params_aura.conf` sets X-Forwarded-Proto/For/Host on
    // every proxied location. Trusting those headers is only safe because the
    // backend is never published directly — only the proxy container binds a
    // host port, so nothing but Nginx can reach it to forge them.
    install(XForwardedHeaders)

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
        // Names the organization a request is scoped to. Without it in this
        // list the browser's preflight fails and every data request 403s with
        // a CORS error that says nothing about organizations.
        allowHeader(ORG_HEADER)

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

    // -----------------------------------------------------------------------
    // Rate limiting for the public auth surface.
    //
    // Keyed on the client IP, which is only correct because XForwardedHeaders
    // is installed above and nothing but the proxy can reach this process —
    // without both, every request would appear to come from the Docker gateway
    // and share one bucket, so a single attacker would lock out all users.
    //
    // Two named limiters rather than one, because the endpoints fail
    // differently. Guessing a password is bounded by the password; asking for
    // reset mail costs the attacker nothing and lands in a real person's inbox,
    // so it needs a much tighter bound.
    // -----------------------------------------------------------------------
    install(RateLimit) {
        // /forgot-password and /resend-verification: each call sends an email
        // to an address the caller does not control. Loose limits here mean
        // this API can be used to flood a stranger's inbox and burn the
        // domain's sending reputation.
        register(RateLimitName(RATE_LIMIT_EMAIL)) {
            rateLimiter(limit = 3, refillPeriod = 60.seconds)
            requestKey { call -> call.clientKey() }
        }

        // /login and /register: generous enough that a salon on one office IP
        // never notices, tight enough to make online password guessing and
        // bulk account creation impractical.
        register(RateLimitName(RATE_LIMIT_AUTH)) {
            rateLimiter(limit = 10, refillPeriod = 60.seconds)
            requestKey { call -> call.clientKey() }
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
            userRoutes()
            // Mounted before the data routes because a user with no
            // organization can still reach these — they are how one is
            // obtained. Everything below requires an organization context.
            organizationRoutes()
            adminRoutes()
            clientRoutes()
            visitRoutes()
            attachmentRoutes()
        }
    }
}
