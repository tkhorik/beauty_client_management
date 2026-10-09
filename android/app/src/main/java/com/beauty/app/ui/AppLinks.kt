package com.beauty.app.ui

import com.beauty.app.BuildConfig
import java.net.URI
import java.net.URLDecoder

/** Shared by delivered links and manual paste. API configuration never grants link trust. */
internal fun trustedWebLink(raw: String, webBaseUrl: String = BuildConfig.APP_WEB_BASE_URL): URI? {
    val link = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
    val origin = runCatching { URI(webBaseUrl) }.getOrNull() ?: return null
    fun port(uri: URI) = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
    return link.takeIf {
        it.scheme in setOf("https", "http") && it.scheme == origin.scheme &&
            it.host != null && it.host.equals(origin.host, ignoreCase = true) &&
            port(it) == port(origin) && it.rawUserInfo == null && it.rawFragment == null
    }
}

private fun URI.parameter(name: String): String? = runCatching {
    val values = rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
        .filter { URLDecoder.decode(it[0], "UTF-8") == name }
    // Ambiguous duplicate parameters and malformed percent encoding are not accepted.
    values.singleOrNull()?.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }
        ?.takeIf { it.isNotBlank() && it.length <= 2048 && it.matches(Regex("[A-Za-z0-9_-]+")) }
}.getOrNull()

internal fun tokenFromWebAppLink(raw: String, queryParam: String, path: String? = null): String? {
    val link = trustedWebLink(raw) ?: return null
    if (path != null && link.path.trimEnd('/') != path) return null
    return link.parameter(queryParam)
}

internal sealed interface AppLink {
    class ResetPassword(val token: String?) : AppLink
    object ForgotPassword : AppLink
    class VerifyEmail(val token: String?, val status: String?) : AppLink
    class CreateOrganization(val token: String) : AppLink
    /** An admin's `?join=<handle>` link: pre-fills the handle to request access with. */
    class JoinOrganization(val slug: String) : AppLink
    /** From the "new access request" email: open that organization's members. */
    class ManageMembers(val organizationId: String) : AppLink
    object Home : AppLink
}

internal fun parseAppLink(raw: String, webBaseUrl: String = BuildConfig.APP_WEB_BASE_URL): AppLink? {
    val link = trustedWebLink(raw, webBaseUrl) ?: return null
    return when (link.path.trimEnd('/')) {
        "/reset-password" -> AppLink.ResetPassword(link.parameter("token"))
        "/forgot-password" -> AppLink.ForgotPassword
        "/api/auth/verify-email", "/verify-email" -> AppLink.VerifyEmail(link.parameter("token"), link.parameter("status"))
        else -> link.parameter("orgToken")?.let { AppLink.CreateOrganization(it) }
            ?: link.parameter("join")?.let { AppLink.JoinOrganization(it.lowercase()) }
            ?: link.parameter("members")?.let { AppLink.ManageMembers(it) }
            ?: AppLink.Home
    }
}
