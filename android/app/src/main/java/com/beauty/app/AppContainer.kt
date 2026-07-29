package com.beauty.app

import android.content.Context
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.api.AuthResponse
import com.beauty.app.data.api.KtorBeautyApi
import com.beauty.app.data.api.RefreshRequest
import com.beauty.app.data.local.BeautyDatabaseProvider
import com.beauty.app.data.local.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object AppContainer {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A client with **no Auth plugin**, used only to call `/api/auth/refresh`.
     *
     * The refresh call must not itself be able to trigger a refresh. If it were
     * made on the authenticated client, a 401 from the refresh endpoint would
     * re-enter the `refreshTokens` block and recurse. Ktor offers a marker
     * attribute for this, but a separate client is unambiguous and does not
     * depend on that API staying put across versions.
     *
     * `expectSuccess = false` because a rejected refresh is an expected outcome
     * to inspect, not an exception to catch.
     */
    private fun buildRefreshClient(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
        }
        defaultRequest { url(BuildConfig.API_BASE_URL) }
    }

    /** Build an HttpClient that automatically attaches Bearer tokens from TokenStore. */
    private fun buildClient(tokenStore: TokenStore): HttpClient {
        val refreshClient = buildRefreshClient()

        return HttpClient(OkHttp) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 15_000
            }
            defaultRequest { url(BuildConfig.API_BASE_URL) }
            install(Auth) {
                bearer {
                    loadTokens {
                        val access = tokenStore.getToken() ?: return@loadTokens null
                        BearerTokens(access, tokenStore.getRefreshToken().orEmpty())
                    }

                    // Called automatically when a request comes back 401. Access
                    // tokens are short-lived now, so this is the ordinary path
                    // during a long session, not an error case — the user should
                    // never notice it happening.
                    //
                    // Ktor serialises this block, which matters more than it
                    // looks: refresh tokens are single-use and rotate, so two
                    // concurrent refreshes would present the same spent token and
                    // the backend would read that as theft and revoke the session.
                    refreshTokens {
                        val refreshToken = tokenStore.getRefreshToken()
                        if (refreshToken.isNullOrBlank()) {
                            tokenStore.clearToken()
                            return@refreshTokens null
                        }

                        val response = try {
                            refreshClient.post("api/auth/refresh") {
                                contentType(ContentType.Application.Json)
                                setBody(RefreshRequest(refreshToken))
                            }
                        } catch (e: Exception) {
                            // Offline. Keep the stored tokens: the session may
                            // well still be valid once the network returns, and
                            // wiping them would sign the user out for a lost
                            // connection.
                            return@refreshTokens null
                        }

                        if (!response.status.isSuccess()) {
                            // The server actively rejected it — expired, revoked,
                            // or reused. This session is genuinely over.
                            tokenStore.clearToken()
                            return@refreshTokens null
                        }

                        val body: AuthResponse = response.body()
                        tokenStore.saveSession(body.token, body.refreshToken)
                        BearerTokens(body.token, body.refreshToken.orEmpty())
                    }
                }
            }
        }
    }

    fun tokenStore(context: Context): TokenStore = TokenStore(context)

    /** Build a plain HttpClient for the login endpoint (no bearer token needed). */
    fun buildLoginClient(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
        }
        defaultRequest { url(BuildConfig.API_BASE_URL) }
    }

    fun repository(context: Context, tokenStore: TokenStore): BeautyRepository {
        val database = BeautyDatabaseProvider.get(context)
        val client = buildClient(tokenStore)
        return BeautyRepository(KtorBeautyApi(client), database.clientDao(), database.visitDao(), json)
    }
}
