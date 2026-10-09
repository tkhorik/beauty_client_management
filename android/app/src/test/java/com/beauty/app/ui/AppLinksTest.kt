package com.beauty.app.ui

import org.junit.Assert.*
import org.junit.Test

class AppLinksTest {
    private val origin = "https://site.example:8443"

    @Test fun `trust includes scheme host port and rejects credentials and fragments`() {
        listOf("http://site.example:8443/reset-password?token=t", "https://evil.example:8443/?orgToken=t",
            "https://site.example/?orgToken=t", "https://site.example.evil:8443/", "https://user@site.example:8443/",
            "https://site.example:8443/reset-password?token=t#fragment", "not a URI").forEach {
            assertNull(it, parseAppLink(it, origin))
        }
        assertNotNull(parseAppLink("https://SITE.example:8443/", origin))
        assertNotNull(parseAppLink("https://site.example:443/", "https://site.example"))
    }

    @Test fun `each website flow is recognized and unrelated paths go home`() {
        assertEquals("first", (parseAppLink("$origin/reset-password?token=first", origin) as AppLink.ResetPassword).token)
        assertSame(AppLink.ForgotPassword, parseAppLink("$origin/forgot-password/", origin))
        assertEquals("mail", (parseAppLink("$origin/api/auth/verify-email?token=mail", origin) as AppLink.VerifyEmail).token)
        assertEquals("success", (parseAppLink("$origin/verify-email?status=success", origin) as AppLink.VerifyEmail).status)
        assertEquals("org", (parseAppLink("$origin/?orgToken=org", origin) as AppLink.CreateOrganization).token)
        assertSame(AppLink.Home, parseAppLink("$origin/clients/123", origin))
        assertEquals("salon-a", (parseAppLink("$origin/?join=Salon-A", origin) as AppLink.JoinOrganization).slug)
        assertEquals("org-1", (parseAppLink("$origin/?members=org-1", origin) as AppLink.ManageMembers).organizationId)
    }

    @Test fun `a creation link wins over a join handle and malformed handles go home`() {
        assertTrue(parseAppLink("$origin/?orgToken=org&join=salon", origin) is AppLink.CreateOrganization)
        listOf("join=", "join=a&join=b", "join=bad%20handle", "join=%3Cscript%3E").forEach { query ->
            assertSame(query, AppLink.Home, parseAppLink("$origin/?$query", origin))
        }
    }

    @Test fun `empty duplicate and malformed token parameters never become credentials`() {
        listOf("token=", "token=a&token=b", "token=a&%74oken=b", "token=%FF", "token=%").forEach { query ->
            val link = parseAppLink("$origin/reset-password?$query", origin)
            if (link != null) {
                assertNull((link as AppLink.ResetPassword).token)
            }
        }
        assertNull((parseAppLink("$origin/reset-password", origin) as AppLink.ResetPassword).token)
    }

    @Test fun `different deployments configure trust independently of API origin`() {
        assertNotNull(parseAppLink("https://second.example/reset-password?token=t", "https://second.example"))
        assertNull(parseAppLink("https://site.example:8443/reset-password?token=t", "https://second.example"))
        assertNull(parseAppLink("http://10.0.2.2:8080/reset-password?token=t", "http://10.0.2.2:5174"))
        assertNotNull(parseAppLink("http://10.0.2.2:5174/reset-password?token=t", "http://10.0.2.2:5174"))
    }
}
