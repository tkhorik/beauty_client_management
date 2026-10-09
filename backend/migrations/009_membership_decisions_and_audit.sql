-- Membership decisions (decline with re-request cooldown, revoke/restore) and
-- the organization audit trail. Not destructive.
--
-- Run before deploying the backend version that reads user_organizations.decided_at,
-- or every membership query 500s. The audit table would be created by
-- SchemaUtils.create on startup anyway; it is spelled out here so the schema
-- change is reviewable in one place. Safe to re-run.
ALTER TABLE user_organizations ADD COLUMN IF NOT EXISTS decided_at TIMESTAMP NULL;
ALTER TABLE user_organizations ADD COLUMN IF NOT EXISTS decided_by VARCHAR(64) NULL REFERENCES users (id);

CREATE TABLE IF NOT EXISTS organization_audit_events (
    id              VARCHAR(64)  PRIMARY KEY,
    organization_id VARCHAR(64)  NOT NULL REFERENCES organizations (id),
    actor_user_id   VARCHAR(64)  NOT NULL,
    target_user_id  VARCHAR(64)  NULL,
    action          VARCHAR(32)  NOT NULL,
    detail          VARCHAR(255) NULL,
    created_at      TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS organization_audit_events_organization_id_created_at
    ON organization_audit_events (organization_id, created_at);
