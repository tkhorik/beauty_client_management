-- ---------------------------------------------------------------------------
-- 004 — Re-verify every account, ahead of enforcing email verification
--
-- Numbered 004 rather than 003 deliberately: `003_admin_panel_and_org_creation_links.sql`
-- is described in CLAUDE.md and belongs to work that is not in this tree. Taking
-- 003 here would collide with it at merge time, and two files claiming the same
-- number is exactly how one of them ends up silently skipped on the VPS. The gap
-- is intentional; do not renumber this file to close it.
--
-- Run this ONCE against the production database BEFORE deploying the backend
-- that enforces verification — but see the ordering note at the bottom, because
-- *when* enforcement actually starts is controlled by an environment variable,
-- not by this file.
--
-- Adds no columns and creates no tables. `email_verified_at` already exists
-- (migration 001). This migration only changes data.
--
-- HOW TO RUN (on the VPS):
--     sudo -iu deploy
--     cd /srv/aura
--     docker compose exec -T db psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
--         < backend/migrations/004_email_verification_enforcement.sql
-- ---------------------------------------------------------------------------

BEGIN;

-- ---------------------------------------------------------------------------
-- Clear every verification stamp, so that every existing account must confirm
-- its address.
--
-- THIS IS DELIBERATE AND IT IS NOT IDEMPOTENT IN THE USUAL SENSE. Migration 001
-- backfilled all pre-existing accounts as verified with
-- `email_verified_at = created_at`, on the reasoning that nobody should be
-- nagged about a feature that did not exist when they signed up. That was the
-- right call while enforcement was soft. It is the wrong starting point for
-- enforcement, because those addresses were never actually confirmed — nobody
-- ever clicked anything — so trusting the backfill would mean enforcing a rule
-- against new users while permanently exempting every older account, including
-- any registered with a typo'd or someone else's address.
--
-- The blanket reset was chosen over the narrower
-- `WHERE email_verified_at = created_at` (which would spare users who genuinely
-- clicked a link since 001). Blanket means one rule for everyone and no
-- reliance on a heuristic that a clock skew or a same-second signup could get
-- wrong.
--
-- CONSEQUENCE, STATED PLAINLY: running this a second time re-clears users who
-- have verified in the meantime, and they will have to do it again. It is not
-- safe to re-run casually. If you need to repeat part of a deploy, skip this
-- file.
--
-- Nobody is locked out by this statement on its own. Verification is enforced
-- only when EMAIL_VERIFICATION_ENFORCED_FROM is set, and even then only after
-- VERIFICATION_GRACE_DAYS have passed. Running this against a backend with that
-- variable unset changes nothing a user can perceive except the banner.
-- ---------------------------------------------------------------------------
UPDATE users
SET email_verified_at = NULL;

-- ---------------------------------------------------------------------------
-- Retire outstanding verification tokens.
--
-- Anyone mid-flow is holding a link issued against the state this migration
-- just discarded. Those links still work — the token does not encode the
-- verification status — but the tidier reason to clear them is that a user who
-- verified an hour ago and is now unverified again should get a fresh mail
-- rather than hunting for an old one in their inbox.
--
-- Password-reset tokens are untouched: PURPOSE is matched on redemption, and
-- invalidating a reset someone is in the middle of would lock them out of an
-- account they are trying to recover.
-- ---------------------------------------------------------------------------
UPDATE one_time_tokens
SET used_at = NOW()
WHERE purpose = 'EMAIL_VERIFICATION'
  AND used_at IS NULL;

COMMIT;

-- ---------------------------------------------------------------------------
-- Verify:
--     SELECT count(*) FILTER (WHERE email_verified_at IS NULL) AS unverified,
--            count(*) AS total
--     FROM users;
--
-- Expected immediately after this migration: unverified = total.
--
-- ORDERING — this matters more than the SQL above:
--
--   1. Run this migration.
--   2. Deploy the backend with EMAIL_VERIFICATION_ENFORCED_FROM *unset*.
--      Nothing is enforced; the API simply starts reporting emailVerified=false.
--   3. Ship the web and Android clients, which show the banner and the resend
--      button. This is the ONLY channel through which users learn they need to
--      re-verify — this migration sends no mail, and there is no bulk mailer in
--      this repo. Enforcing before the banner is live means users meet the
--      restriction as an unexplained error.
--   4. Only then set EMAIL_VERIFICATION_ENFORCED_FROM to a timestamp and
--      restart. Every account gets VERIFICATION_GRACE_DAYS from that moment,
--      not from its creation date.
--
-- To back out at any point: unset EMAIL_VERIFICATION_ENFORCED_FROM and restart.
-- That restores unrestricted access immediately, with no migration to reverse.
-- Restoring the *stamps* this migration cleared is not possible — that data is
-- gone once this commits.
-- ---------------------------------------------------------------------------
