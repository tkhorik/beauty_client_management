package com.beauty.app.data.api

/** Keep server diagnostics as stable codes; never render server English copy. */
fun ValidationErrorResponse.fieldMessageCodes(): Map<String, String> =
    if (fieldErrors.isNotEmpty()) fieldErrors.mapValues { (_, issue) ->
        val limit = issue.args["min"] ?: issue.args["max"]
        if (limit == null) issue.code else "${issue.code}:$limit"
    } else errors.mapValues { "VALIDATION_FAILED" }
