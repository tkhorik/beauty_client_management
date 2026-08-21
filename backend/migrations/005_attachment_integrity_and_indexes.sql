-- 005_attachment_integrity_and_indexes.sql
--
-- Apply after 004.  Existing foreign keys were originally RESTRICT; replace
-- them with database-enforced cascades and add the lookup indexes used by the
-- paginated client/visit endpoints.

BEGIN;

ALTER TABLE attachments DROP CONSTRAINT IF EXISTS attachments_visit_id_fkey;
ALTER TABLE attachments
    ADD CONSTRAINT attachments_visit_id_fkey
    FOREIGN KEY (visit_id) REFERENCES visits (id) ON DELETE CASCADE;

ALTER TABLE visits DROP CONSTRAINT IF EXISTS visits_client_id_fkey;
ALTER TABLE visits
    ADD CONSTRAINT visits_client_id_fkey
    FOREIGN KEY (client_id) REFERENCES clients (id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS visits_client_id ON visits (client_id);
CREATE INDEX IF NOT EXISTS attachments_visit_id ON attachments (visit_id);

COMMIT;
