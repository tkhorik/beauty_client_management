-- 002_multi_tenant_rbac.sql
--
-- Multi-tenant RBAC: organizations, membership, and organization ownership of
-- clients and visits.
--
-- WHY THIS IS A HAND-WRITTEN MIGRATION
-- `SchemaUtils.create` only creates whole tables that are missing. It will add
-- `organizations` and `user_organizations` by itself, but it will *not* add
-- `organization_id` to the existing `clients` and `visits` tables, nor
-- `global_role` to `users`. Deploying the new backend without running this
-- first produces a 500 on every client and visit route.
--
-- DESTRUCTIVE. `clients.organization_id` and `visits.organization_id` are NOT
-- NULL with no backfill, because there is no correct organization to guess for
-- a pre-existing row and a nullable column would mean "visible to nobody, or
-- worse, to everybody". This migration therefore DELETES existing client,
-- visit and attachment rows. That was an explicit decision for this cutover —
-- there is no production data worth preserving. If that is no longer true when
-- you read this, stop and write a backfill instead: create one organization,
-- UPDATE the rows to point at it, and only then add the NOT NULL constraint.
--
-- Run it once, against the target database, BEFORE deploying the version of
-- the backend that expects it:
--   psql "$DB_URL" -f backend/migrations/002_multi_tenant_rbac.sql

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Global role on users
-- ---------------------------------------------------------------------------
-- Separate from organization role by design: this is the one privilege with no
-- organization to scope it to. Existing accounts become plain USERs; promote a
-- super admin deliberately with the UPDATE at the bottom of this file.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS global_role VARCHAR(32) NOT NULL DEFAULT 'USER';

-- ---------------------------------------------------------------------------
-- 2. Organizations
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS organizations (
    id          VARCHAR(64)  PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    -- Unique so that "join aura-downtown" is never ambiguous.
    slug        VARCHAR(100) NOT NULL UNIQUE,
    -- Nullable: deleting a founder must not orphan the organization.
    created_by  VARCHAR(64)  REFERENCES users (id),
    created_at  TIMESTAMP    NOT NULL
);

-- ---------------------------------------------------------------------------
-- 3. Membership
-- ---------------------------------------------------------------------------
-- role:   ORG_ADMIN | ORG_USER
-- status: ACTIVE | PENDING | INVITED  -- only ACTIVE grants access
CREATE TABLE IF NOT EXISTS user_organizations (
    id              VARCHAR(64) PRIMARY KEY,
    user_id         VARCHAR(64) NOT NULL REFERENCES users (id),
    organization_id VARCHAR(64) NOT NULL REFERENCES organizations (id),
    role            VARCHAR(32) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    invited_by      VARCHAR(64) REFERENCES users (id),
    created_at      TIMESTAMP   NOT NULL,
    updated_at      TIMESTAMP   NOT NULL
);

CREATE INDEX IF NOT EXISTS user_organizations_user_id
    ON user_organizations (user_id);
CREATE INDEX IF NOT EXISTS user_organizations_organization_id
    ON user_organizations (organization_id);

-- One row per (user, organization). Without it, a re-request after removal or
-- a race between an invitation and a join request leaves two rows, and an
-- authorization check that happens to read the wrong one grants the wrong
-- thing.
CREATE UNIQUE INDEX IF NOT EXISTS user_organizations_user_id_organization_id
    ON user_organizations (user_id, organization_id);

-- ---------------------------------------------------------------------------
-- 4. Organization ownership of clients and visits  (DESTRUCTIVE — see header)
-- ---------------------------------------------------------------------------
-- Children first: attachments reference visits, visits reference clients.
DELETE FROM attachments;
DELETE FROM visits;
DELETE FROM clients;

ALTER TABLE clients
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(64) NOT NULL REFERENCES organizations (id),
    ADD COLUMN IF NOT EXISTS created_by      VARCHAR(64)          REFERENCES users (id);

ALTER TABLE visits
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(64) NOT NULL REFERENCES organizations (id),
    ADD COLUMN IF NOT EXISTS created_by      VARCHAR(64)          REFERENCES users (id);

CREATE INDEX IF NOT EXISTS clients_organization_id ON clients (organization_id);
CREATE INDEX IF NOT EXISTS visits_organization_id  ON visits (organization_id);

COMMIT;

-- ---------------------------------------------------------------------------
-- 5. Bootstrap a super admin (manual, optional)
-- ---------------------------------------------------------------------------
-- There is no API that can grant this role, deliberately: an endpoint capable
-- of minting a super admin is an endpoint worth attacking. Either set
-- SUPER_ADMIN_EMAILS in the environment, which promotes matching accounts at
-- startup, or run this by hand:
--
--   UPDATE users SET global_role = 'SUPER_ADMIN' WHERE email = 'you@example.com';
