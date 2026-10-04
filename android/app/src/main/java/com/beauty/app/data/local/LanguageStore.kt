package com.beauty.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.beauty.app.i18n.LanguagePreference

/** Preferences are non-secret, but pending writes must remain scoped to their account. */
interface LanguageStateStorage {
    fun state(accountId: String?): LanguageStore.State
    fun save(accountId: String?, state: LanguageStore.State)
    var observedTags: String
    var pendingApplicationTags: String?
}

class LanguageStore(context: Context) : LanguageStateStorage {
    private val prefs: SharedPreferences = context.getSharedPreferences("beauty_language", Context.MODE_PRIVATE)

    data class State(
        val preference: String = "system",
        val revision: Long = 0,
        val pending: Boolean = false,
        val baseRevision: Long = revision,
        val sequence: Long = 0
    )

    override fun state(accountId: String?): State {
        val key = key(accountId)
        val preference = prefs.getString("$key.preference", "system")?.takeIf { it in LanguagePreference.all } ?: "system"
        return State(preference, prefs.getLong("$key.revision", 0), prefs.getBoolean("$key.pending", false),
            prefs.getLong("$key.base_revision", 0), prefs.getLong("$key.sequence", 0))
    }

    override fun save(accountId: String?, state: State) {
        require(state.preference in LanguagePreference.all)
        val key = key(accountId)
        prefs.edit().putString("$key.preference", state.preference)
            .putLong("$key.revision", state.revision).putBoolean("$key.pending", state.pending)
            .putLong("$key.base_revision", state.baseRevision).putLong("$key.sequence", state.sequence).apply()
    }

    override var observedTags: String
        get() = prefs.getString("last_observed_tags", "") ?: ""
        set(value) { prefs.edit().putString("last_observed_tags", value).apply() }
    override var pendingApplicationTags: String?
        get() = prefs.getString("pending_application_tags", null)
        set(value) { prefs.edit().putString("pending_application_tags", value).apply() }

    private fun key(accountId: String?) = accountId?.let { "account:$it" } ?: "guest"
}
