-- ---------------------------------------------------------------------------
-- 003 — Admin panel + restricted, invite-based organization creation
--
-- Run this ONCE against the production database BEFORE deploying the version
-- of the backend that gates `POST /api/organizations` behind a creation
-- token and adds account suspension.
--
-- WHY THIS IS A HAND-WRITTEN MIGRATION
-- `SchemaUtils.create` only creates whole tables that are missing. It will
-- add `organization_creation_tokens` by itself (like `one_time_tokens` did
-- originally), but it will not add `suspended_at` to the existing `users`
-- table. Deploying the new backend without running this first means every
-- suspension check reads a column that does not exist and 500s.
--
-- NOT DESTRUCTIVE. Both changes are additive: a new nullable column and a
-- new table. No existing row is touched.
--
-- HOW TO RUN (on the VPS):
--     sudo -iu deploy
--     cd /srv/aura
--     docker compose exec -T db psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
--         < backend/migrations/003_admin_panel_and_org_creation_links.sql
--
-- Safe to run more than once: every statement is IF NOT EXISTS or guarded.
-- ---------------------------------------------------------------------------

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Account-wide suspension.
--
-- Nullable, with no default: NULL means "in good standing", and a timestamp
-- means "suspended, and here is when" — the same pattern as
-- `email_verified_at`. This is a lock on the whole account, distinct from
-- being removed from one organization.
-- ---------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMP NULL;

-- ---------------------------------------------------------------------------
-- 2. Organization creation tokens.
--
-- Admin-issued, multi-use, expiring bearer tokens. Only the SHA-256 hash is
-- stored, matching every other bearer-token table in this schema — a dump
-- must not hand an attacker a working set of them. `max_uses` and
-- `expires_at` are NOT NULL: a link with no cap and no expiry is a standing
-- backdoor, so both bounds are mandatory at creation time, not optional.
--
-- ON DELETE CASCADE on created_by would let a deleted admin account silently
-- take their outstanding links with them; there is no user-deletion feature
-- in this app yet, so the FK is left unrestricted like the rest of the
-- schema's audit columns.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS organization_creation_tokens (
    id          VARCHAR(64)  NOT NULL PRIMARY KEY,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    label       VARCHAR(255) NULL,
    created_by  VARCHAR(64)  NOT NULL REFERENCES users (id),
    max_uses    INTEGER      NOT NULL,
    uses_count  INTEGER      NOT NULL DEFAULT 0,
    expires_at  TIMESTAMP    NOT NULL,
    revoked_at  TIMESTAMP    NULL,
    created_at  TIMESTAMP    NOT NULL
);

COMMIT;

-- ---------------------------------------------------------------------------
-- Verify:
--     SELECT column_name FROM information_schema.columns
--     WHERE table_name = 'users' AND column_name = 'suspended_at';
--
--     SELECT count(*) FROM organization_creation_tokens;
--
-- Expected immediately after this migration: the column exists, the table is
-- empty (nothing has been issued yet).
-- ---------------------------------------------------------------------------
