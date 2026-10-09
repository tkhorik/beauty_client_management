package com.beauty.mail

import com.beauty.auth.AdminContact
import com.beauty.auth.OneTimeTokenService
import com.beauty.config.AppSettings
import io.ktor.server.config.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JoinNotificationMailTest {

    private class RecordingSender : MailSender {
        val sent = mutableListOf<Email>()
        override suspend fun send(email: Email): Boolean {
            sent += email
            return true
        }
    }

    private val settings = AppSettings(MapApplicationConfig("app.publicUrl" to "https://salon.example/"))

    /**
     * Unconfined, so each dispatched send runs to completion before
     * `dispatch` returns — [RecordingSender] never suspends.
     */
    private fun mailerWith(sender: RecordingSender) =
        AccountMailer(settings, OneTimeTokenService(), sender, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `every admin is mailed in their own language with a link to the members screen`() = runBlocking {
        val sender = RecordingSender()

        mailerWith(sender).sendJoinRequestToAdmins(
            organizationId = "org-1",
            organizationName = "Salon A",
            requesterName = "Nina",
            requesterEmail = "nina@example.com",
            admins = listOf(
                AdminContact("u1", "en@example.com", "Ann", "en"),
                AdminContact("u2", "ru@example.com", "Олег", "ru")
            )
        )

        assertEquals(listOf("en@example.com", "ru@example.com"), sender.sent.map { it.to })
        assertTrue(sender.sent[0].subject.contains("Nina asked to join Salon A"))
        assertTrue(sender.sent[1].subject.contains("просит доступ"))
        assertTrue(sender.sent.all { "https://salon.example/?members=org-1" in it.textBody })
    }

    @Test
    fun `names cannot inject headers or markup`() = runBlocking {
        val sender = RecordingSender()

        mailerWith(sender).sendJoinRequestToAdmins(
            organizationId = "org-1",
            organizationName = "Salon\r\nBcc: victim@example.com",
            requesterName = "<script>x</script>",
            requesterEmail = "nina@example.com",
            admins = listOf(AdminContact("u1", "en@example.com", "Ann", "en"))
        )

        val mail = sender.sent.single()
        assertFalse(mail.subject.contains('\n') || mail.subject.contains('\r'))
        assertFalse(mail.htmlBody.contains("<script>"))
    }

    @Test
    fun `the requester is told the decision`() = runBlocking {
        val sender = RecordingSender()
        val mailer = mailerWith(sender)

        mailer.sendJoinDecision("nina@example.com", "Nina", "system", "Salon A", approved = true)
        mailer.sendJoinDecision("nina@example.com", "Nina", "system", "Salon A", approved = false)

        assertEquals("You now have access to Salon A", sender.sent[0].subject)
        assertEquals("Your request to join Salon A was declined", sender.sent[1].subject)
    }
}
