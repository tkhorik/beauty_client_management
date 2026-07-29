package com.beauty.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.json.jsonb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json

object UsersTable : Table("users") {
    val id = varchar("id", 64)
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val fullName = varchar("full_name", 255)
    val createdAt = datetime("created_at")

    /**
     * When the user proved they control this address, or null if they have not.
     *
     * A nullable timestamp rather than a boolean: "verified" and "verified at
     * 09:12 on Tuesday" cost the same to store, and the second answers support
     * questions the first cannot.
     *
     * NOTE: `SchemaUtils.create` does not add columns to an existing table, so
     * this needs `backend/migrations/001_email_verification.sql` run against
     * any database that already has a `users` table.
     */
    val emailVerifiedAt = datetime("email_verified_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Single-use tokens for email verification and password reset.
 *
 * One table with a [purpose] discriminator rather than two near-identical
 * tables. The lifecycle is genuinely the same — issue, mail, redeem once,
 * expire — and splitting it would mean two copies of the redemption logic,
 * which is precisely the code where a subtle difference becomes a
 * vulnerability.
 *
 * As with `RefreshTokensTable`, only the SHA-256 hash is stored. These tokens
 * are bearer credentials: anyone holding a valid reset token can take over the
 * account without knowing the password, so a database dump must not hand an
 * attacker a working set of them. (A fast hash is right here, not BCrypt — the
 * token is 256 bits of `SecureRandom` output, so there is nothing to guess and
 * nothing for a slow hash to frustrate.)
 */
object OneTimeTokensTable : Table("one_time_tokens") {
    val id = varchar("id", 64)
    val userId = varchar("user_id", 64).references(UsersTable.id).index()

    /** SHA-256 hex digest of the token. Never the token itself. */
    val tokenHash = varchar("token_hash", 64).uniqueIndex()

    /** `EMAIL_VERIFICATION` or `PASSWORD_RESET`. See `auth/TokenPurpose`. */
    val purpose = varchar("purpose", 32).index()

    val createdAt = datetime("created_at")
    val expiresAt = datetime("expires_at")

    /**
     * Set the moment the token is redeemed.
     *
     * This is what makes the token single-use, and it is not optional: a reset
     * link that still works after the password has been changed lets an
     * attacker who saw the mail once reset the account again at any time before
     * expiry.
     */
    val usedAt = datetime("used_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Long-lived refresh tokens, one row per issued token.
 *
 * Access tokens are short-lived JWTs and are deliberately *not* stored — that
 * is the point of a stateless token. Refresh tokens are the opposite: they
 * live for weeks, so they must be revocable, which means the server has to
 * know about them.
 *
 * Only the SHA-256 hash of the token is stored. A refresh token is a bearer
 * credential, so a dump of this table must not hand an attacker a working set
 * of logins. (Unlike a password, the token is high-entropy random, so a plain
 * fast hash is appropriate here — BCrypt's slowness exists to frustrate
 * dictionary attacks on guessable input, and there is nothing to guess.)
 */
object RefreshTokensTable : Table("refresh_tokens") {
    val id = varchar("id", 64)
    val userId = varchar("user_id", 64).references(UsersTable.id)

    /** SHA-256 hex digest of the token. Never the token itself. */
    val tokenHash = varchar("token_hash", 64).uniqueIndex()

    /**
     * Groups every token descended from one login. Rotation issues a new token
     * in the same family, so detecting reuse of a spent token lets us revoke
     * the entire chain rather than just the one row.
     */
    val familyId = varchar("family_id", 64).index()

    val issuedAt = datetime("issued_at")
    val expiresAt = datetime("expires_at")

    /** Set when the token is spent by rotation, or explicitly revoked by logout. */
    val revokedAt = datetime("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object ClientsTable : Table("clients") {
    val id = varchar("id", 64)
    val name = varchar("name", 255)
    val phone = varchar("phone", 50)
    val email = varchar("email", 255).nullable()
    val tags = text("tags") // Comma-separated or JSON list
    val customFields = jsonb<JsonObject>("custom_fields", Json.Default)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object VisitsTable : Table("visits") {
    val id = varchar("id", 64)
    val clientId = varchar("client_id", 64).references(ClientsTable.id)
    val visitDateTime = datetime("visit_date_time")
    val durationMinutes = integer("duration_minutes")
    val procedureNotes = text("procedure_notes")
    val status = varchar("status", 50) // COMPLETED, SCHEDULED, CANCELLED
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

object AttachmentsTable : Table("attachments") {
    val id = varchar("id", 64)
    val visitId = varchar("visit_id", 64).references(VisitsTable.id)
    val fileUrl = varchar("file_url", 512)
    val fileType = varchar("file_type", 100)
    val fileSize = long("file_size")
    val caption = text("caption").nullable()
    val tag = varchar("tag", 50) // BEFORE, AFTER, PROCEDURE, DOCUMENT
    val uploadedAt = datetime("uploaded_at")

    override val primaryKey = PrimaryKey(id)
}
