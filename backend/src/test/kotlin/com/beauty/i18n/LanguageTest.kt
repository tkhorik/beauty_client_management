package com.beauty.i18n

import com.beauty.mail.EmailTemplates
import com.beauty.models.ValidationErrorResponse
import com.beauty.validation.Validation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class LanguageTest {
    @Test fun `explicit account choice overrides request and system honors quality and regions`() {
        assertEquals("en", Languages.resolve("en", "ru-RU"))
        assertEquals("ru", Languages.resolve("ru", "en"))
        assertEquals("ru", Languages.resolve("system", "fr-FR,ru-RU;q=0.8,en;q=0.2"))
        assertEquals("en", Languages.resolve("system", "ru;q=0,en;q=1"))
        assertEquals("en", Languages.resolve("system", "ru;q=NaN,en;q=0.5"))
        assertEquals("en", Languages.resolve("system", "ru;q=bad,en"))
        assertEquals("en", Languages.resolve("system", null))
        assertEquals("en", Languages.resolve("system", "de,fr"))
    }

    @Test fun `all Russian email variants localize and escape names without changing links`() {
        val name = "<script>&\"'"
        val link = "https://example.test/reset-password?token=opaque"
        val messages = listOf(
            EmailTemplates.verification(name, link, 24, "ru"),
            EmailTemplates.passwordReset(name, link, 30, "ru"),
            EmailTemplates.passwordChanged(name, "3 октября 2026, 12:00", link, "ru")
        )
        for (email in messages) {
            assertTrue(email.subject.any { it in 'А'..'я' })
            assertTrue(email.textBody.contains("Здравствуйте"))
            assertTrue(email.textBody.contains(name))
            assertTrue(email.htmlBody.contains("&lt;script&gt;&amp;&quot;&#39;"))
            assertFalse(email.htmlBody.contains("<script>"))
            assertTrue(email.htmlBody.contains("lang=\"ru\""))
            assertTrue(email.htmlBody.contains(link))
            assertTrue(email.textBody.contains(link))
        }
        assertTrue(messages[0].textBody.contains("24 ч."))
        assertTrue(messages[1].textBody.contains("30 мин."))
        assertTrue(messages[2].textBody.contains("3 октября 2026"))
    }

    @Test fun `validation adds codes and preserves legacy messages and byte limit`() {
        val issue = assertNotNull(Validation.passwordIssue("я".repeat(37)))
        assertEquals("PASSWORD_TOO_LONG", issue.code)
        assertEquals(72, issue.args["max"])
        val response = ValidationErrorResponse.from(mapOf("password" to issue))
        assertEquals(Validation.validatePassword("я".repeat(37)), response.errors["password"])
        val json = Json.encodeToString(response)
        assertTrue(json.contains("PASSWORD_TOO_LONG"))
        assertTrue(json.contains("fieldErrors"))
    }
}
