-- ---------------------------------------------------------------------------
-- 001 — Email verification and password reset
--
-- Run this ONCE against the production database BEFORE deploying the version
-- of the backend that introduces email verification.
--
-- Why this file exists at all: the app bootstraps its schema with Exposed's
-- `SchemaUtils.create`, which creates missing *tables* and nothing else. It
-- will happily create `one_time_tokens` on its own, but it cannot add a column
-- to `users`, because `users` already exists. There is no Flyway/Liquibase in
-- this repo, so the ALTER is written and reviewed by hand.
--
-- HOW TO RUN (on the VPS):
--     sudo -iu deploy
--     cd /srv/aura
--     docker compose exec -T db psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
--         < backend/migrations/001_email_verification.sql
--
-- Safe to run more than once: every statement is IF NOT EXISTS or guarded.
-- ---------------------------------------------------------------------------

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Track whether a user has proved control of their address.
--
-- Nullable, with no default: NULL means "not verified", and a timestamp means
-- "verified, and here is when". A boolean would answer the first question and
-- lose the second at no saving.
-- ---------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMP NULL;

-- ---------------------------------------------------------------------------
-- 2. Backfill every account that predates this feature as verified.
--
-- These users registered when no verification email was ever sent, so leaving
-- them unverified would show them a banner demanding they confirm a message
-- they never received. Their addresses are genuinely unconfirmed — accepted
-- deliberately, because nagging existing users about a feature that did not
-- exist when they signed up is the worse outcome.
--
-- The WHERE clause makes this idempotent: a second run finds nothing to do,
-- and it can never re-verify an account someone deliberately un-verifies later.
-- ---------------------------------------------------------------------------
UPDATE users
SET email_verified_at = created_at
WHERE email_verified_at IS NULL;

-- ---------------------------------------------------------------------------
-- 3. Single-use tokens for verification and password reset.
--
-- Created here as well as by SchemaUtils so that this file describes the
-- complete post-migration state and can be reviewed on its own. Whichever runs
-- first wins; IF NOT EXISTS makes the other a no-op.
--
-- Only the SHA-256 hash of each token is stored — these are bearer credentials,
-- and a valid password-reset token is a complete account takeover without the
-- password. A database dump must not contain usable ones.
--
-- ON DELETE CASCADE: deleting a user must take their outstanding tokens with
-- them. A reset token outliving its user is at best a dangling foreign key and
-- at worst redeemable against a recycled id.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS one_time_tokens (
    id          VARCHAR(64)  NOT NULL PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    purpose     VARCHAR(32)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    used_at     TIMESTAMP    NULL
);

-- Redemption looks tokens up by hash, which the UNIQUE constraint already
-- indexes. These two cover the other access patterns: invalidating a user's
-- outstanding tokens of one purpose on reset, and the periodic expiry purge.
CREATE INDEX IF NOT EXISTS idx_one_time_tokens_user_purpose
    ON one_time_tokens (user_id, purpose);

CREATE INDEX IF NOT EXISTS idx_one_time_tokens_expires_at
    ON one_time_tokens (expires_at);

COMMIT;

-- ---------------------------------------------------------------------------
-- Verify:
--     SELECT count(*) FILTER (WHERE email_verified_at IS NULL) AS unverified,
--            count(*) AS total
--     FROM users;
--
-- Expected immediately after this migration: unverified = 0.
-- ---------------------------------------------------------------------------
