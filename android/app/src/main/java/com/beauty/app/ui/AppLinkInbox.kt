package com.beauty.app.ui

/** Main-thread handoff from the transient entry activity, deliberately not persisted. */
internal object AppLinkInbox {
    private val pending = mutableListOf<AppLink>()
    fun receive(raw: String) { parseAppLink(raw)?.let { pending += it } }
    fun drain(): List<AppLink> = pending.toList().also { pending.clear() }
}
