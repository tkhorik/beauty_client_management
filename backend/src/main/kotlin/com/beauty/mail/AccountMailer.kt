package com.beauty.mail

import com.beauty.auth.OneTimeTokenService
import com.beauty.auth.TokenPurpose
import com.beauty.config.AppSettings
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime

/**
 * Ties together "mint a one-time token" and "send the email carrying it".
 *
 * Exists so no route handler ever holds a raw token and a template at the same
 * time. Issuing and sending are a single indivisible step here, which is what
 * stops a token from being created and then, on some later edit, logged,
 * returned in a response body, or mailed to the wrong address.
 */
class AccountMailer(
    private val settings: AppSettings,
    private val tokens: OneTimeTokenService,
    private val mail: MailSender
) {
    private val log = LoggerFactory.getLogger(AccountMailer::class.java)

    private val verificationTtl: Duration = Duration.ofHours(settings.verificationTokenHours)
    private val resetTtl: Duration = Duration.ofMinutes(settings.resetTokenMinutes)

    /**
     * Origin comes from [AppSettings.publicUrl], never from the incoming
     * request. See the note there: deriving it from the Host header would let
     * an attacker have the real account owner mailed a reset link that points
     * at the attacker's server.
     */
    private fun link(path: String, token: String): String {
        val encoded = URLEncoder.encode(token, StandardCharsets.UTF_8)
        return "${settings.publicUrl}$path?token=$encoded"
    }

    suspend fun sendVerification(userId: String, email: String, fullName: String) {
        val token = tokens.issue(userId, TokenPurpose.EMAIL_VERIFICATION, verificationTtl)
        val template = EmailTemplates.verification(
            fullName = fullName,
            link = link("/api/auth/verify-email", token),
            expiryHours = settings.verificationTokenHours
        )
        mail.send(template.copy(to = email))
    }

    suspend fun sendPasswordReset(userId: String, email: String, fullName: String) {
        val token = tokens.issue(userId, TokenPurpose.PASSWORD_RESET, resetTtl)
        val template = EmailTemplates.passwordReset(
            fullName = fullName,
            // Points at the web app, not the API: the user has to *type* a new
            // password, so the link must land on a form. Verification needs no
            // input and so can be redeemed by the API directly.
            link = link("/reset-password", token),
            expiryMinutes = settings.resetTokenMinutes
        )
        mail.send(template.copy(to = email))
    }

    /**
     * Sent after a password actually changes, to the address on the account.
     *
     * Best-effort and never blocking the response: the password has already
     * been changed by the time this runs, so a mail failure must not make the
     * reset look like it failed and tempt the user into trying again.
     */
    suspend fun sendPasswordChangedNotice(email: String, fullName: String) {
        val template = EmailTemplates.passwordChanged(
            fullName = fullName,
            whenText = LocalDateTime.now().format(NOTICE_TIME_FORMAT),
            supportLink = "${settings.publicUrl}/forgot-password"
        )
        if (!mail.send(template.copy(to = email))) {
            // Worth its own line in the log: this is the notification that
            // makes an account takeover visible to its owner, so a silent
            // failure to deliver it is a security-relevant event, not just a
            // bounced email.
            log.warn("Password-changed notice could not be delivered to {}.", email)
        }
    }

    private companion object {
        val NOTICE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm")
    }
}
