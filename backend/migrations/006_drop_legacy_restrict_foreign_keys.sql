-- 006_drop_legacy_restrict_foreign_keys.sql
--
-- Repairs 005, which did not actually take effect.
--
-- WHAT WENT WRONG
--
-- 005 intended to replace the RESTRICT foreign keys on `visits.client_id` and
-- `attachments.visit_id` with ON DELETE CASCADE. It did so by dropping the
-- constraint names PostgreSQL generates by default — `visits_client_id_fkey`,
-- `attachments_visit_id_fkey` — and adding cascading ones under the same names.
--
-- But these tables were never created by PostgreSQL's own DDL defaults. They
-- were created by Exposed's `SchemaUtils.create`, which names foreign keys
-- `fk_<table>_<column>__<referenced column>`. So 005's DROP statements matched
-- nothing (they logged `constraint ... does not exist, skipping` and carried
-- on), while its ADD statements happily created a *second* foreign key on each
-- column. Verified on production 2026-08-21:
--
--     visits       fk_visits_client_id__id       r   <- original, RESTRICT
--     visits       visits_client_id_fkey         c   <- added by 005, CASCADE
--     attachments  fk_attachments_visit_id__id   r   <- original, RESTRICT
--     attachments  attachments_visit_id_fkey     c   <- added by 005, CASCADE
--
-- PostgreSQL enforces every foreign key on a column, so the strictest one wins:
-- deleting a client that has visits still fails exactly as it did before 005
-- ran. The migration reported success and changed nothing observable, which is
-- the worst way for a migration to fail.
--
-- WHAT THIS DOES
--
-- Re-asserts the cascading constraints (idempotently, so this file is also
-- correct on a database where 005 never ran), then removes any *other*
-- single-column foreign key on those two columns that is not ON DELETE CASCADE.
--
-- The removal is written as a catalogue query rather than two DROP statements
-- naming `fk_visits_client_id__id` and `fk_attachments_visit_id__id` directly.
-- That is the whole lesson of 005: hardcoding a constraint name couples the
-- migration to whichever tool created the table, and a name that does not match
-- fails silently. Selecting by *what the constraint does* — wrong column, wrong
-- delete action — cannot miss for that reason, and stays correct if a future
-- Exposed version changes its naming scheme again.
--
-- Order matters within the transaction: the cascading constraint is in place
-- before anything is dropped, so referential integrity is never unenforced,
-- not even briefly inside the transaction.
--
-- SAFE TO RE-RUN. Unlike 004, this destroys no data and converges to the same
-- state every time.
--
-- HOW TO RUN (on the VPS):
--     sudo -iu deploy
--     cd /srv/aura
--     docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
--         < backend/migrations/006_drop_legacy_restrict_foreign_keys.sql
--
-- The single quotes are load-bearing: POSTGRES_USER/POSTGRES_DB live in .env,
-- which docker compose reads but never exports into the deploy user's shell.
-- Unquoted, they expand to empty on the host and psql falls back to the login
-- name — 'FATAL: role "root" does not exist'. Quoted, they expand inside the
-- container, where they are set.
--
-- VERIFY afterwards — each column should have exactly one foreign key, with
-- confdeltype 'c':
--
--     SELECT conrelid::regclass AS table_name, conname, confdeltype
--     FROM pg_constraint
--     WHERE contype = 'f'
--       AND conrelid IN ('visits'::regclass, 'attachments'::regclass)
--     ORDER BY 1, 2;
--
-- (`visits_created_by_fkey` and `visits_organization_id_fkey` show 'a' and are
-- unrelated — deleting a user or an organization is not meant to cascade into
-- clinical records.)
-- ---------------------------------------------------------------------------

BEGIN;

-- Idempotent re-assertion of what 005 meant to do. On a database where 005 did
-- run, these drop and immediately recreate the identical constraint; on one
-- where it did not, they create it for the first time.
ALTER TABLE attachments DROP CONSTRAINT IF EXISTS attachments_visit_id_fkey;
ALTER TABLE attachments
    ADD CONSTRAINT attachments_visit_id_fkey
    FOREIGN KEY (visit_id) REFERENCES visits (id) ON DELETE CASCADE;

ALTER TABLE visits DROP CONSTRAINT IF EXISTS visits_client_id_fkey;
ALTER TABLE visits
    ADD CONSTRAINT visits_client_id_fkey
    FOREIGN KEY (client_id) REFERENCES clients (id) ON DELETE CASCADE;

-- Now remove every other foreign key on those two columns that does not
-- cascade — whatever it happens to be called.
DO $$
DECLARE
    legacy record;
BEGIN
    FOR legacy IN
        SELECT c.conrelid::regclass AS table_name, c.conname
        FROM pg_constraint c
        JOIN pg_attribute a
          ON a.attrelid = c.conrelid
         AND a.attnum = c.conkey[1]
        WHERE c.contype = 'f'
          -- 'c' is ON DELETE CASCADE. Anything else on these columns is the
          -- constraint that has been silently blocking deletes.
          AND c.confdeltype <> 'c'
          -- Single-column keys only; a composite key on one of these columns
          -- would mean something this migration was not written to reason
          -- about, and should be looked at by a human rather than dropped.
          AND array_length(c.conkey, 1) = 1
          AND (
                (c.conrelid = 'visits'::regclass      AND a.attname = 'client_id')
             OR (c.conrelid = 'attachments'::regclass AND a.attname = 'visit_id')
              )
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', legacy.table_name, legacy.conname);
        RAISE NOTICE 'Dropped legacy non-cascading foreign key % on %',
            legacy.conname, legacy.table_name;
    END LOOP;
END $$;

COMMIT;
