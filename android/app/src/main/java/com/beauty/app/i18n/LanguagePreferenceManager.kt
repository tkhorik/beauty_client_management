package com.beauty.app.i18n

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.api.UserDto
import com.beauty.app.data.api.LanguagePreferenceResponse
import com.beauty.app.data.local.LanguageStore
import com.beauty.app.data.local.LanguageStateStorage
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Injectable boundary keeps synchronization tests independent of Android framework stubs. */
internal interface AppLocaleBridge {
    fun tags(): String
    fun apply(tags: String)
}

/** Call from the main dispatcher: locale application can recreate the hosting activity. */
class LanguagePreferenceManager internal constructor(
    private val store: LanguageStateStorage,
    private val locales: AppLocaleBridge
) {
    constructor(context: Context) : this(LanguageStore(context.applicationContext), object : AppLocaleBridge {
        override fun tags() = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        override fun apply(tags: String) = AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
    })

    private val mutex = Mutex()
    private var generation = 0L
    var activeAccountId: String? = null
        set(value) {
            if (field == value) return
            generation++
            if (value == null && field != null) {
                // Logout keeps the visible language without moving any pending account write.
                store.save(null, LanguageStore.State(preference = LanguagePreference.fromLocaleTags(locales.tags())))
            }
            field = value
            notice = null
            changeCounter++
        }
    var changeCounter by mutableLongStateOf(0)
        private set
    var notice by mutableStateOf<SyncResult?>(null)
        private set

    fun current(accountId: String?) = store.state(accountId)

    fun select(accountId: String?, preference: String) {
        require(preference in LanguagePreference.all)
        if (accountId != activeAccountId) return
        val state = store.state(accountId)
        if (state.preference != preference || state.pending) {
            store.save(accountId, state.copy(preference = preference, pending = accountId != null,
                baseRevision = if (state.pending) state.baseRevision else state.revision,
                sequence = state.sequence + 1))
            changeCounter++
        }
        notice = null
        apply(preference)
    }

    /** Read the native preference before server reconciliation, including after a killed process. */
    fun detectExternalOverride(accountId: String?): Boolean {
        if (accountId != activeAccountId) return false
        val tags = locales.tags()
        val selfApplied = store.pendingApplicationTags == tags
        val changed = tags != store.observedTags
        store.pendingApplicationTags = null
        store.observedTags = tags
        if (!changed || selfApplied) return false
        val state = store.state(accountId)
        store.save(accountId, state.copy(preference = LanguagePreference.fromLocaleTags(tags),
            pending = accountId != null,
            baseRevision = if (state.pending) state.baseRevision else state.revision,
            sequence = state.sequence + 1))
        changeCounter++
        return true
    }

    suspend fun synchronize(repository: BeautyRepository, accountId: String?, user: UserDto? = null): SyncResult =
        synchronize(accountId,
            fetch = {
                val profile = user ?: repository.getCurrentUser()
                require(profile.id == accountId) { "Language profile belongs to another account" }
                LanguagePreferenceResponse(profile.languagePreference, profile.languageRevision)
            },
            write = { preference, revision ->
                try { WriteResult(repository.updateLanguagePreference(preference, revision, accountId), false) }
                catch (error: ClientRequestException) {
                    if (error.response.status != HttpStatusCode.Conflict) throw error
                    WriteResult(error.response.body(), true)
                }
            })

    internal data class WriteResult(val value: LanguagePreferenceResponse, val conflict: Boolean)

    internal suspend fun synchronize(
        accountId: String?,
        fetch: suspend () -> LanguagePreferenceResponse,
        write: suspend (String, Long) -> WriteResult
    ): SyncResult {
        val startedGeneration = generation
        fun isActive() = accountId != null && accountId == activeAccountId && startedGeneration == generation
        if (!isActive()) return SyncResult.Idle
        return mutex.withLock {
            if (!isActive()) return@withLock SyncResult.Idle
            try {
                // Also covers network callbacks that race the activity's ON_START observer.
                detectExternalOverride(accountId)
                val remote = fetch()
                if (!isActive()) return@withLock SyncResult.Idle
                require(remote.preference in LanguagePreference.all && remote.revision >= 0)
                var local = store.state(accountId)
                if (!local.pending) {
                    if (remote.revision >= local.revision) accept(accountId, remote, local.sequence)
                    return@withLock SyncResult.Downloaded
                }
                if (remote.revision > local.baseRevision) {
                    val conflict = remote.preference != local.preference
                    accept(accountId, remote, local.sequence)
                    notice = if (conflict) SyncResult.Conflict else null
                    return@withLock if (conflict) SyncResult.Conflict else SyncResult.Uploaded
                }
                // A successful write rebases subsequent local edits and flushes them immediately.
                while (isActive()) {
                    local = store.state(accountId)
                    if (!local.pending) return@withLock SyncResult.Uploaded
                    val result = write(local.preference, local.baseRevision)
                    if (!isActive()) return@withLock SyncResult.Idle
                    val saved = result.value
                    require(saved.preference in LanguagePreference.all && saved.revision >= 0)
                    val latest = store.state(accountId)
                    if (result.conflict) {
                        // A stale offline choice must not overwrite a newer account choice.
                        val conflict = latest.preference != saved.preference
                        accept(accountId, saved, latest.sequence)
                        notice = if (conflict) SyncResult.Conflict else null
                        return@withLock if (conflict) SyncResult.Conflict else SyncResult.Uploaded
                    }
                    if (latest.sequence == local.sequence) {
                        accept(accountId, saved, latest.sequence)
                        notice = null
                        return@withLock SyncResult.Uploaded
                    }
                    store.save(accountId, latest.copy(revision = saved.revision, baseRevision = saved.revision))
                    changeCounter++
                }
                SyncResult.Idle
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (isActive() && store.state(accountId).pending) notice = SyncResult.PendingOffline
                SyncResult.PendingOffline
            }
        }
    }

    /** Registration already submits the guest choice; retained for existing call-site compatibility. */
    fun adoptNewAccountFromGuest(accountId: String?) {
        if (accountId == null) return
        val guest = store.state(null)
        if (store.state(accountId).sequence == 0L) store.save(accountId, guest.copy(pending = false))
    }

    fun clearNotice() { notice = null }

    private fun accept(accountId: String?, value: LanguagePreferenceResponse, sequence: Long) {
        store.save(accountId, LanguageStore.State(value.preference, value.revision, sequence = sequence))
        changeCounter++
        apply(value.preference)
    }

    private fun apply(preference: String) {
        val tags = if (preference == "system") "" else preference
        if (locales.tags() == tags) {
            store.observedTags = tags
            store.pendingApplicationTags = null
            return
        }
        // If the process stops after the framework call, this intent suppresses its echo on restart.
        store.pendingApplicationTags = tags
        locales.apply(tags)
        store.observedTags = tags
        store.pendingApplicationTags = null
    }

    enum class SyncResult { Idle, Downloaded, Uploaded, Conflict, PendingOffline }
}
