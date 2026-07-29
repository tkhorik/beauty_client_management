package com.beauty.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun saveToken(token: String) = prefs.edit().putString(KEY_TOKEN, token).apply()

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
    fun saveSession(accessToken: String, refreshToken: String?) {
        prefs.edit().apply {
            putString(KEY_TOKEN, accessToken)
            // A null refresh token means the server did not issue a new one
            // (cookie transport), so keep whatever we already have.
            if (refreshToken != null) putString(KEY_REFRESH_TOKEN, refreshToken)
        }.apply()
    }

    /** Clears both tokens. Used on logout and whenever a session is rejected. */
    fun clearToken() = prefs.edit().remove(KEY_TOKEN).remove(KEY_REFRESH_TOKEN).apply()

    companion object {
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_REFRESH_TOKEN = "jwt_refresh_token"
    }
}
