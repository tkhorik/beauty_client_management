package com.beauty.routes

import io.ktor.server.application.ApplicationCall

/** A deliberately bounded default prevents an accidental unpaginated export. */
fun ApplicationCall.pageLimit(): Int =
    request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50

fun ApplicationCall.pageOffset(): Long =
    request.queryParameters["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
