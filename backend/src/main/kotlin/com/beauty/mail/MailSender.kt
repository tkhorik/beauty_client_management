package com.beauty.mail

import com.beauty.config.AppSettings
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.Properties

/**
 * One outbound email.
 *
 * Both a plain-text and an HTML body are always required. Text-only mail looks
 * broken in modern clients; HTML-only mail is penalised by spam filters and is
 * unreadable in text-mode clients. Sending `multipart/alternative` with both is
 * the only combination that behaves everywhere.
 */
data class Email(
    val to: String,
    val subject: String,
    val textBody: String,
    val htmlBody: String
)

/**
 * How the application sends mail.
 *
 * An interface rather than a direct SMTP call for two reasons. First, local
 * development and CI have no SMTP server, and a verification flow that cannot
 * run without one is a flow nobody tests. Second, mail transport is the part of
 * this feature most likely to change: SMTP from a bare VPS IP has poor
 * deliverability, and switching to a transactional HTTP provider should be one
 * new class here, not an edit to every auth route.
 */
interface MailSender {
    /**
     * Sends [email].
     *
     * Implementations must not throw on delivery failure. A caller is always in
     * the middle of an auth flow, and an SMTP timeout must not turn a
     * successful registration into a 500 — nor must it change the response
     * shape, which for [com.beauty.routes.authRoutes]' `/forgot-password` would
     * reintroduce the account-enumeration oracle the always-200 answer exists to
     * close. Returns true if the message was handed to the transport.
     */
    suspend fun send(email: Email): Boolean

    companion object {
        /**
         * Picks an implementation from configuration.
         *
         * The absence of `MAIL_HOST` selects [LogMailSender], so a developer who
         * has configured nothing still gets a fully working flow with the links
         * printed to the console. Production cannot reach that branch:
         * [AppSettings.validateOrFail] refuses to start without a mail host.
         */
        fun from(settings: AppSettings): MailSender =
            if (settings.mailHost.isBlank()) LogMailSender() else SmtpMailSender(settings)
    }
}

/**
 * Development sender: logs what would have been sent, including the body, so
 * verification and reset links can be copied straight out of the console.
 *
 * Deliberately logs the full text body. That includes single-use token URLs,
 * which is exactly why this must never run in production — see the mail-host
 * check in [AppSettings.validateOrFail].
 */
class LogMailSender : MailSender {
    private val log = LoggerFactory.getLogger(LogMailSender::class.java)

    override suspend fun send(email: Email): Boolean {
        log.info(
            "\n--- DEV MAIL (not actually sent) ---\nTo: {}\nSubject: {}\n\n{}\n------------------------------------",
            email.to,
            email.subject,
            email.textBody
        )
        return true
    }
}

/**
 * Sends over SMTP with STARTTLS.
 *
 * The [Session] is built once and reused: it is thread-safe and immutable, and
 * `Transport.send` opens its own connection per message anyway, so rebuilding
 * the session per email buys nothing.
 */
class SmtpMailSender(private val settings: AppSettings) : MailSender {
    private val log = LoggerFactory.getLogger(SmtpMailSender::class.java)

    private val session: Session by lazy {
        val props = Properties().apply {
            put("mail.smtp.host", settings.mailHost)
            put("mail.smtp.port", settings.mailPort.toString())
            put("mail.smtp.auth", settings.mailUser.isNotBlank().toString())
            put("mail.smtp.starttls.enable", settings.mailStartTls.toString())
            // Without this, STARTTLS is attempted but silently falls back to a
            // plaintext session if the server declines — which would send
            // password-reset links in the clear.
            put("mail.smtp.starttls.required", settings.mailStartTls.toString())
            // Implicit TLS (port 465) rather than STARTTLS (587).
            put("mail.smtp.ssl.enable", settings.mailSslOnConnect.toString())
            // Bound both, or a hung SMTP server pins a request thread forever.
            put("mail.smtp.connectiontimeout", SMTP_TIMEOUT_MS.toString())
            put("mail.smtp.timeout", SMTP_TIMEOUT_MS.toString())
            put("mail.smtp.writetimeout", SMTP_TIMEOUT_MS.toString())
        }

        if (settings.mailUser.isNotBlank()) {
            Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(settings.mailUser, settings.mailPassword)
            })
        } else {
            Session.getInstance(props)
        }
    }

    /**
     * Runs on [Dispatchers.IO] because jakarta.mail is fully blocking. Calling
     * it directly from a Ktor handler would block a coroutine thread for the
     * duration of the SMTP conversation, which under load starves every other
     * request in the process.
     */
    override suspend fun send(email: Email): Boolean = withContext(Dispatchers.IO) {
        try {
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(settings.mailFrom, settings.mailFromName))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(email.to))
                setSubject(email.subject, "UTF-8")

                // `multipart/alternative` (not `mixed`): the parts are two
                // renderings of the same message, and the client picks one.
                // With `mixed` both would be shown, one after the other.
                val text = MimeBodyPart().apply { setText(email.textBody, "UTF-8") }
                val html = MimeBodyPart().apply { setContent(email.htmlBody, "text/html; charset=UTF-8") }
                // Least-preferred part first — clients pick the last one they
                // can render, so HTML must come second.
                setContent(MimeMultipart("alternative").apply { addBodyPart(text); addBodyPart(html) })
            }

            Transport.send(message)
            log.info("Sent '{}' to {}", email.subject, email.to)
            true
        } catch (e: Exception) {
            // Logged, never rethrown. See the contract on MailSender.send.
            // The recipient address is logged but the body is not: bodies carry
            // single-use tokens and logs are not a secure store.
            log.error("Failed to send '{}' to {}: {}", email.subject, email.to, e.message, e)
            false
        }
    }

    private companion object {
        const val SMTP_TIMEOUT_MS = 10_000
    }
}
