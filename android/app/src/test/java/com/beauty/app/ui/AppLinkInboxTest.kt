package com.beauty.app.ui

import com.beauty.app.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class AppLinkInboxTest {
    @Test fun `handoff preserves separate deliveries and drains before navigation`() {
        AppLinkInbox.drain()
        val raw = "${BuildConfig.APP_WEB_BASE_URL}/reset-password?token=synthetic"
        AppLinkInbox.receive(raw)
        AppLinkInbox.receive(raw)
        AppLinkInbox.receive("https://evil.example/reset-password?token=evil")
        val links = AppLinkInbox.drain()
        assertEquals(2, links.size)
        assertTrue(links.all { it is AppLink.ResetPassword })
        assertTrue(AppLinkInbox.drain().isEmpty())
    }
}
