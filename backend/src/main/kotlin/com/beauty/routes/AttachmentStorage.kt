package com.beauty.routes

import java.io.File

/**
 * The database stores an internal, legacy-compatible path (`/uploads/<name>`),
 * never a public URL.  Keeping the storage key separate from the URL exposed
 * by the API lets us change delivery policy without moving every file.
 */
fun attachmentDownloadUrl(attachmentId: String): String = "/api/attachments/$attachmentId/file"

/** Resolves a stored upload path without allowing it to escape [uploadDir]. */
fun storedAttachmentFile(uploadDir: File, storedPath: String): File? {
    val prefix = "/uploads/"
    val name = storedPath.removePrefix(prefix)
    if (!storedPath.startsWith(prefix) || name.isBlank() || name.contains('/') || name.contains('\\')) return null

    val root = uploadDir.canonicalFile
    val file = File(root, name).canonicalFile
    return file.takeIf { it.parentFile == root }
}
