package com.beauty.mail

/**
 * Bodies for the three transactional emails this app sends.
 *
 * Kept together so the tone and the security wording stay consistent, and so
 * the expiry stated in the copy is derived from the same constant the server
 * enforces rather than typed out separately and left to drift.
 *
 * Styling is inline and minimal on purpose. Email clients strip `<style>`
 * blocks, ignore most modern CSS, and render on wildly different engines; a
 * plain layout that degrades gracefully beats one that only looks right in
 * Gmail.
 */
object EmailTemplates {

    private const val APP_NAME = "Aura Beauty Log"

    /**
     * Escapes text interpolated into the HTML body.
     *
     * The user's own `fullName` reaches these templates, and it is arbitrary
     * user input that was never sanitised on the way in. Without escaping, a
     * name containing markup would be injected into mail sent to that address —
     * and, more usefully to an attacker, into the change-notice mail, which is
     * the one message a victim reads most carefully.
     */
    private fun esc(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun layout(heading: String, bodyHtml: String, buttonLabel: String, link: String): String = """
        <!DOCTYPE html>
        <html><body style="margin:0;padding:24px;background:#faf7f5;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#2d2a28;">
          <div style="max-width:520px;margin:0 auto;background:#ffffff;border-radius:12px;padding:32px;">
            <h1 style="margin:0 0 16px;font-size:20px;font-weight:600;">${esc(heading)}</h1>
            $bodyHtml
            <p style="margin:28px 0;">
              <a href="$link" style="display:inline-block;background:#b76e79;color:#ffffff;text-decoration:none;padding:12px 24px;border-radius:8px;font-weight:600;">${esc(buttonLabel)}</a>
            </p>
            <p style="margin:0 0 8px;font-size:13px;color:#6b625d;">If the button does not work, paste this address into your browser:</p>
            <p style="margin:0;font-size:13px;color:#6b625d;word-break:break-all;">$link</p>
            <hr style="border:none;border-top:1px solid #eee;margin:28px 0 16px;">
            <p style="margin:0;font-size:12px;color:#9a918c;">$APP_NAME</p>
          </div>
        </body></html>
    """.trimIndent()

    fun verification(fullName: String, link: String, expiryHours: Long) = Email(
        to = "",
        subject = "Confirm your $APP_NAME email address",
        textBody = """
            Hi ${fullName.ifBlank { "there" }},

            Confirm this email address to finish setting up your $APP_NAME account:

            $link

            This link expires in $expiryHours hours and can only be used once.

            If you did not create an account, you can ignore this email.

            — $APP_NAME
        """.trimIndent(),
        htmlBody = layout(
            heading = "Confirm your email address",
            bodyHtml = """
                <p style="margin:0 0 12px;font-size:15px;line-height:1.6;">Hi ${esc(fullName.ifBlank { "there" })},</p>
                <p style="margin:0;font-size:15px;line-height:1.6;">Confirm this email address to finish setting up your $APP_NAME account. This link expires in $expiryHours hours and can only be used once.</p>
                <p style="margin:12px 0 0;font-size:15px;line-height:1.6;color:#6b625d;">If you did not create an account, you can ignore this email.</p>
            """.trimIndent(),
            buttonLabel = "Confirm email address",
            link = link
        )
    )

    fun passwordReset(fullName: String, link: String, expiryMinutes: Long) = Email(
        to = "",
        subject = "Reset your $APP_NAME password",
        textBody = """
            Hi ${fullName.ifBlank { "there" }},

            Someone asked to reset the password for this $APP_NAME account. If it
            was you, choose a new password here:

            $link

            This link expires in $expiryMinutes minutes and can only be used once.

            If you did not ask for this, no action is needed — your password has
            not changed, and whoever made the request cannot see this email.

            — $APP_NAME
        """.trimIndent(),
        htmlBody = layout(
            heading = "Reset your password",
            bodyHtml = """
                <p style="margin:0 0 12px;font-size:15px;line-height:1.6;">Hi ${esc(fullName.ifBlank { "there" })},</p>
                <p style="margin:0;font-size:15px;line-height:1.6;">Someone asked to reset the password for this $APP_NAME account. If it was you, choose a new password below. This link expires in $expiryMinutes minutes and can only be used once.</p>
                <p style="margin:12px 0 0;font-size:15px;line-height:1.6;color:#6b625d;">If you did not ask for this, no action is needed — your password has not changed, and whoever made the request cannot see this email.</p>
            """.trimIndent(),
            buttonLabel = "Choose a new password",
            link = link
        )
    )

    /**
     * Sent after a password actually changes.
     *
     * This is the security control that makes account takeover *visible*. A
     * reset performed by an attacker is otherwise completely silent to the
     * owner: the first they learn of it is when their own password stops
     * working, by which point the attacker has had free use of the account.
     */
    fun passwordChanged(fullName: String, whenText: String, supportLink: String) = Email(
        to = "",
        subject = "Your $APP_NAME password was changed",
        textBody = """
            Hi ${fullName.ifBlank { "there" }},

            The password for your $APP_NAME account was changed on $whenText.
            Every device that was signed in has been signed out.

            If this was you, nothing further is needed.

            If it was NOT you, reset your password immediately — whoever made the
            change still knows the password they set:

            $supportLink

            — $APP_NAME
        """.trimIndent(),
        htmlBody = layout(
            heading = "Your password was changed",
            bodyHtml = """
                <p style="margin:0 0 12px;font-size:15px;line-height:1.6;">Hi ${esc(fullName.ifBlank { "there" })},</p>
                <p style="margin:0;font-size:15px;line-height:1.6;">The password for your $APP_NAME account was changed on ${esc(whenText)}. Every device that was signed in has been signed out.</p>
                <p style="margin:12px 0 0;font-size:15px;line-height:1.6;">If this was you, nothing further is needed. If it was <strong>not</strong> you, reset your password immediately — whoever made the change still knows the password they set.</p>
            """.trimIndent(),
            buttonLabel = "Reset your password",
            link = supportLink
        )
    )
}
