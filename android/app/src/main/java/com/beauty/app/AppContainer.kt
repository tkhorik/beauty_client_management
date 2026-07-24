package com.beauty.app

import android.content.Context
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.api.KtorBeautyApi
import com.beauty.app.data.local.BeautyDatabaseProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object AppContainer {
    private val json = Json { ignoreUnknownKeys = true }

    fun repository(context: Context): BeautyRepository {
        val database = BeautyDatabaseProvider.get(context)
        val client = HttpClient(OkHttp) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 15_000
            }
            defaultRequest { url(BuildConfig.API_BASE_URL) }
        }
        return BeautyRepository(KtorBeautyApi(client), database.clientDao(), database.visitDao(), json)
    }
}
