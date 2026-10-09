-- Single-use organization invite links: an admin issues one, and whoever
-- redeems it becomes an ACTIVE ORG_USER without a further approval. Not
-- destructive — one new table, no existing row touched.
--
-- SchemaUtils.create would add this table on startup anyway (it is brand new);
-- it is spelled out here so the schema change is reviewable in one place and
-- can be applied ahead of the deploy. Safe to re-run.
CREATE TABLE IF NOT EXISTS organization_invite_links (
    id              VARCHAR(64) PRIMARY KEY,
    organization_id VARCHAR(64) NOT NULL REFERENCES organizations (id),
    token_hash      VARCHAR(64) NOT NULL UNIQUE,
    created_by      VARCHAR(64) NOT NULL REFERENCES users (id),
    expires_at      TIMESTAMP   NOT NULL,
    used_at         TIMESTAMP   NULL,
    used_by         VARCHAR(64) NULL REFERENCES users (id),
    revoked_at      TIMESTAMP   NULL,
    created_at      TIMESTAMP   NOT NULL
);
CREATE INDEX IF NOT EXISTS organization_invite_links_organization_id
    ON organization_invite_links (organization_id);
