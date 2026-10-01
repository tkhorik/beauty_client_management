package com.beauty.app.ui

import android.net.Uri
import com.beauty.app.BuildConfig

/**
 * Pulls a one-time token out of a link the user pasted from the web app —
 * an organization-creation link (`?orgToken=`) or a password-reset link
 * (`/reset-password?token=`).
 *
 * Pasting rather than a deep link on purpose: without App Links verification
 * (`assetlinks.json` served from the domain) any installed app could register
 * the same URL pattern and intercept these links. Only links for this
 * deployment's own web origin are accepted, so a lookalike host is rejected
 * before its token is sent anywhere.
 *
 * @param path when given, the link's path must match it exactly.
 * @return the token, or null if the link is not one of ours.
 */
internal fun tokenFromWebAppLink(raw: String, queryParam: String, path: String? = null): String? {
    val candidate = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
    val expected = runCatching { Uri.parse(BuildConfig.API_BASE_URL) }.getOrNull() ?: return null
    if (candidate.scheme !in setOf("http", "https")) return null

    val token = candidate.getQueryParameter(queryParam)?.trim()
    if (token.isNullOrEmpty()) return null
    if (path != null && candidate.path?.trimEnd('/') != path) return null

    // Debug builds talk to the backend on the emulator's host alias; the web
    // dev server that issued the link runs beside it on 5174.
    val expectedPort = when {
        expected.host == "10.0.2.2" && (expected.port == -1 || expected.port == 8080) -> 5174
        expected.port != -1 -> expected.port
        expected.scheme == "https" -> 443
        else -> 80
    }
    val candidatePort = when {
        candidate.port != -1 -> candidate.port
        candidate.scheme == "https" -> 443
        else -> 80
    }
    val sameOrigin = candidate.scheme == expected.scheme &&
        candidate.host == expected.host &&
        candidatePort == expectedPort
    return token.takeIf { sameOrigin }
}
