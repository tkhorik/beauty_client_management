package com.beauty.mail

import com.beauty.auth.OneTimeTokenService
import com.beauty.auth.TokenPurpose
import com.beauty.config.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
 *
 * **Where the asynchrony lives.** Every method here suspends only for the fast,
 * local part — minting the token and rendering the template — and hands the
 * SMTP call to [scope] to finish on its own. The boundary is drawn here, at the
 * network call, rather than around the whole method in each route, for two
 * reasons:
 *
 *  - The response must not wait on SMTP. `/forgot-password` answers identically
 *    for a registered and an unregistered address, and awaiting a round trip
 *    for one but not the other is an account-enumeration oracle in the timing
 *    channel — see the note on that endpoint. What remains is two local indexed
 *    writes, which is far below the noise floor of a network request; the
 *    unbounded difference is gone.
 *  - The token write must *not* escape the request. It is a database
 *    transaction, and a transaction that outlives the call — or, in tests, the
 *    whole application — runs against whatever connection state happens to
 *    exist by then. Backgrounding the entire method rather than just the send
 *    made registration fail intermittently for exactly this reason.
 */
class AccountMailer(
    private val settings: AppSettings,
    private val tokens: OneTimeTokenService,
    private val mail: MailSender,
    /**
     * Where the SMTP call is dispatched. In production this is the `Application`,
     * so a send in flight when the server stops is cancelled with everything
     * else — an acceptable loss for mail that is, by design, resendable.
     */
    private val scope: CoroutineScope
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
        dispatch("verification", template.copy(to = email))
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
        dispatch("password reset", template.copy(to = email))
    }

    /**
     * Sent after a password actually changes, to the address on the account.
     *
     * Best-effort and never blocking the response: the password has already
     * been changed by the time this runs, so a mail failure must not make the
     * reset look like it failed and tempt the user into trying again.
     *
     * Not `suspend`, unlike the other two — this notice carries no token, so
     * there is nothing to mint and nothing to write before the send.
     */
    fun sendPasswordChangedNotice(email: String, fullName: String) {
        val template = EmailTemplates.passwordChanged(
            fullName = fullName,
            whenText = LocalDateTime.now().format(NOTICE_TIME_FORMAT),
            supportLink = "${settings.publicUrl}/forgot-password"
        )
        // Worth its own line in the log if it fails: this is the notification
        // that makes an account takeover visible to its owner, so a silent
        // failure to deliver it is a security-relevant event, not just a
        // bounced email.
        dispatch("password-changed notice", template.copy(to = email))
    }

    /**
     * Hands one message to [scope] and returns immediately.
     *
     * [MailSender.send] is contractually not allowed to throw on a delivery
     * failure, but this catches anyway: the coroutine has no parent waiting on
     * it, so an unexpected throw from a future implementation would otherwise
     * surface as an unhandled exception in the application scope rather than as
     * a line in the log.
     */
    private fun dispatch(what: String, email: Email) {
        scope.launch {
            val delivered = try {
                mail.send(email)
            } catch (e: CancellationException) {
                // The server is shutting down. Rethrown rather than logged as a
                // delivery failure: swallowing it would break cancellation, and
                // "could not be delivered" would misdescribe what happened.
                throw e
            } catch (e: Exception) {
                log.warn("Sending the {} email to {} threw: {}", what, email.to, e.message)
                false
            }

            if (!delivered) log.warn("The {} email could not be delivered to {}.", what, email.to)
        }
    }

    private companion object {
        val NOTICE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm")
    }
}
