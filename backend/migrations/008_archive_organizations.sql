-- Soft-archive organizations without removing memberships or tenant records.
-- Run before deploying the backend version that reads organizations.archived_at.
ALTER TABLE organizations ADD COLUMN archived_at TIMESTAMP NULL;
CREATE INDEX idx_organizations_archived_at ON organizations (archived_at);
