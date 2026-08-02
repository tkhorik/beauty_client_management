package com.beauty.app.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers which organization the app is working in.
 *
 * Plain [SharedPreferences], not the encrypted store the tokens live in, and
 * the difference is deliberate rather than an oversight. An organization id is
 * not a credential: possessing one grants nothing, because the backend re-reads
 * membership from its own database on every request. Encrypting it would imply
 * a secrecy the value does not have, and would put a non-secret in the same
 * bucket as the two things that genuinely are secret.
 *
 * What it does buy is that reopening the app returns to the salon the user was
 * last in, instead of showing an organization picker every launch.
 */
class OrgStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("beauty_org_prefs", Context.MODE_PRIVATE)

    fun getActiveOrgId(): String? = prefs.getString(KEY_ACTIVE_ORG, null)

    fun setActiveOrgId(orgId: String?) {
        prefs.edit().apply {
            if (orgId == null) remove(KEY_ACTIVE_ORG) else putString(KEY_ACTIVE_ORG, orgId)
        }.apply()
    }

    /**
     * Called on sign-out.
     *
     * Not optional: the next person to sign in on this device must not inherit
     * a selected organization they may have no membership of. The server would
     * refuse them, but the app would show a salon name in its header that has
     * nothing to do with the account now signed in.
     */
    fun clear() = setActiveOrgId(null)

    companion object {
        private const val KEY_ACTIVE_ORG = "active_organization_id"
    }
}
