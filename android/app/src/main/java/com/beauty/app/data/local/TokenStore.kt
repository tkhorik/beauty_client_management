package com.beauty.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Persists the JWT token in EncryptedSharedPreferences (AES256-GCM).
 * Instantiate once via AppContainer and inject wherever needed.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "beauty_secure_prefs",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    init { accountState.value = getAccountId() }

    val accountFlow: StateFlow<String?> get() = accountState

    /** JWT identity is only a cache partition hint; server authorization remains authoritative. */
    fun getAccountId(): String? = prefs.getString(KEY_ACCOUNT, null) ?: getToken()?.let(::accountFromToken)

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun saveToken(token: String) = saveSession(token, null)

    /** The long-lived, revocable half of the session. */
    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    /**
     * Persists both halves together.
     *
     * Deliberately one call rather than two: refresh tokens rotate, so the
     * access token and the refresh token that produced it must be written
     * atomically. Saving one without the other leaves the app holding a
     * refresh token the server has already spent — which, on next use, looks
     * exactly like token theft and revokes the whole session.
     */
    fun saveSession(accessToken: String, refreshToken: String?, accountId: String? = accountFromToken(accessToken)) {
        prefs.edit().apply {
            putString(KEY_TOKEN, accessToken)
            putString(KEY_ACCOUNT, accountId)
            // A null refresh token means the server did not issue a new one
            // (cookie transport), so keep whatever we already have.
            if (refreshToken != null) putString(KEY_REFRESH_TOKEN, refreshToken)
        }.apply()
        accountState.value = accountId
    }

    /** Clears both tokens. Used on logout and whenever a session is rejected. */
    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_REFRESH_TOKEN).remove(KEY_ACCOUNT).apply()
        accountState.value = null
    }

    companion object {
        private val accountState = MutableStateFlow<String?>(null)
        private const val KEY_ACCOUNT = "account_id"
        private fun accountFromToken(token: String): String? = runCatching {
            val payload = token.split('.')[1]
            val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP))
            Json.parseToJsonElement(decoded).jsonObject["userId"]?.jsonPrimitive?.content
                ?: Json.parseToJsonElement(decoded).jsonObject["sub"]?.jsonPrimitive?.content
        }.getOrNull()
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_REFRESH_TOKEN = "jwt_refresh_token"
    }
}
