package com.beauty.app

import android.content.Context
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.api.KtorBeautyApi
import com.beauty.app.data.local.BeautyDatabaseProvider
import com.beauty.app.data.local.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object AppContainer {
    private val json = Json { ignoreUnknownKeys = true }

    /** Build an HttpClient that automatically attaches Bearer tokens from TokenStore. */
    private fun buildClient(tokenStore: TokenStore): HttpClient = HttpClient(OkHttp) {
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
                    val t = tokenStore.getToken() ?: return@loadTokens null
                    BearerTokens(t, "") // no refresh token in single-user flow
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
