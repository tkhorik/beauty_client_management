# Registration & Auth Hardening Plan

Status: **PR 1 and PR 2 implemented**; PR 3 and PR 4 outstanding. 2026-07-29
Scope: registration on web + Android, brought to production standard.

This is sequenced as **four PRs**, not one. Each is independently
reviewable, independently deployable, and ordered so that nothing ships in a
worse state than it started. `main` auto-deploys to production on merge, so
"independently deployable" is a hard requirement, not a nicety.

---

## Why this is bigger than "add a register screen"

The audit turned up three problems that are more serious than the missing
Android UI:

1. **No per-user data ownership.** `clients` and `visits` have no `user_id`.
   Registration is open on a public domain, so any stranger who signs up can
   read, edit, and delete every client record in the salon. This is a live
   data-exposure bug, not a future concern.
2. **JWTs never expire.** `generateJwtToken` never calls `.withExpiresAt()`.
   Every token ever issued is valid forever and there is no revocation path —
   so a leaked token is permanent, and "log out" only clears local storage.
3. **`/api/auth/register` validates nothing.** No email format check, no
   password policy, no normalisation. A one-character password is accepted.

Everything else below is ordinary hardening.

---

## Full findings

### Backend — `routes/AuthRoutes.kt`

| # | Finding | Severity |
|---|---------|----------|
| B1 | No password policy. `"a"` is a valid password. | High |
| B2 | No email format validation. `"notanemail"` registers fine. | High |
| B3 | Email is not normalised. `Foo@x.com` and `foo@x.com` become two separate accounts, and a user who registers with capitals cannot log in with lowercase. | High |
| B4 | Check-then-insert is not atomic. Two concurrent registrations for one email hit the `uniqueIndex`, throw, and are caught by `StatusPages` as a generic **500** instead of 409. | Medium |
| B5 | No max password length. BCrypt silently truncates at 72 bytes, so a 200-char passphrase is no stronger than its first 72 bytes — and users are not told. | Medium |
| B6 | Login timing oracle. When the email does not exist, `BCrypt.checkpw` never runs, so a missing account responds measurably faster than a wrong password. Enumerable. | Low |
| B7 | `createdAt` is computed twice — `now` (a string, used in the response) and a second `LocalDateTime.now()` (persisted). The value returned to the client is not the value stored. | Low |
| B8 | No email verification, so any address can be claimed by anyone. | Medium |
| B9 | No password reset. A forgotten password is unrecoverable. | Medium |

### Backend — auth infrastructure

| # | Finding | Severity |
|---|---------|----------|
| B10 | JWT has no `exp` claim. Tokens are valid forever, with no revocation. | High |
| B11 | No per-user ownership on `clients` / `visits`. Any authenticated user reaches every record. | **Critical** |
| B12 | Rate limiting exists at nginx (`auth_limit`, `5r/s burst=10`) but 5 requests/second per IP is ~300/min — far too loose for a login endpoint, and IP-based limits do nothing against distributed credential stuffing. No per-account throttle. | Medium |
| B13 | No `schema_migrations` table and no migration tool. `SchemaUtils.create()` only creates missing tables, so adding `user_id` to a live table needs a hand-written, versioned migration. | Blocker for PR 3 |

### Web — `components/LoginPage.tsx`

| # | Finding | Severity |
|---|---------|----------|
| W1 | No confirm-password field. A typo silently locks the user out of a brand-new account. | Medium |
| W2 | No `autocomplete` attributes. Password managers cannot reliably offer to save or fill credentials — the single biggest practical driver of password reuse. | Medium |
| W3 | No client-side policy check, so the user only discovers a rejected password after a round trip. | Low |
| W4 | Handles `res.status === 400`, but the backend never returns 400 — dead branch today, and there is no field-level error rendering for when it does. | Low |
| W5 | Calls `fetch` directly instead of going through `services/api.ts`, so it bypasses the centralised error and 401 handling. | Low |
| W6 | No show/hide password toggle. | Low |
| W7 | The `localStorage` demo fallback in `api.ts` means an offline user silently gets fake data with no login at all. Intentional per `CLAUDE.md`, but it must not be extended to the new auth screens. | Note |

### Android

| # | Finding | Severity |
|---|---------|----------|
| A1 | **Registration does not exist.** No `register()` in `BeautyApi`, no `RegisterScreen`, no nav route. An Android-only user cannot create an account at all. | High |
| A2 | `AuthViewModel` has no `register` path and no field-level validation. | High |
| A3 | No password reset entry point. | Medium |

---

## PR 1 — Registration correctness and validation

No schema change. Safe to deploy on its own.

**New:** `backend/src/main/kotlin/com/beauty/validation/Validation.kt`

- `normaliseEmail(raw)` → `trim().lowercase()`.
- `validateEmail` — length ≤ 254, single `@`, non-empty local part, dotted
  domain. Deliberately permissive: real validation is the verification email
  in PR 4, and over-strict regexes reject valid addresses.
- `validatePassword` — minimum **12** characters; maximum **72 bytes UTF-8**
  with an explicit error rather than silent BCrypt truncation. No composition
  rules (no "must contain a symbol") — current NIST guidance is that length
  beats character-class mandates and that composition rules push users toward
  predictable substitutions.
- `validateFullName` — trimmed, 1–255 chars, not blank.
- Returns a `Map<String, String>` of field → message, so the client can render
  errors inline.

**Changed:** `AuthRoutes.kt`

- Validate before touching the database; respond `400` with
  `{"errors": {"password": "..."}}`.
- Normalise email on **both** register and login.
- Wrap the insert and catch `ExposedSQLException` with SQLState `23505`
  (unique violation) → `409`. Keeps the pre-check as a fast path but no longer
  depends on it for correctness.
- Always run a BCrypt comparison on login, against a fixed dummy hash when the
  user is absent, to flatten the timing difference (B6).
- Compute `createdAt` once (B7).

**Changed:** `models/Models.kt` — add `ValidationErrorResponse(errors: Map<String, String>)`.

**One-off data concern:** existing rows may already hold mixed-case emails.
Before deploying, run on the VPS:

```sql
SELECT lower(email), count(*) FROM users GROUP BY 1 HAVING count(*) > 1;
```

If that returns nothing, `UPDATE users SET email = lower(email);` is safe. If
it returns rows, those duplicate accounts must be merged by hand first —
otherwise the `uniqueIndex` rejects the update.

**Tests:** `AuthRoutesTest` covering short password, 73-byte password, bad
email, mixed-case duplicate, and concurrent-duplicate → 409.

---

## PR 2 — JWT expiry and refresh tokens

Depends on PR 1. Deployable alone. **This one logs everybody out** — say so in
the release notes.

**New table `refresh_tokens`:** `id`, `user_id`, `token_hash`, `family_id`,
`issued_at`, `expires_at`, `revoked_at`.

- Access token: **15 minutes**, `exp` claim added and verified.
- Refresh token: 32 random bytes, base64url. Stored **hashed (SHA-256)** — a
  database leak must not yield usable tokens. 30-day expiry.
- **Rotation on use.** Presenting a refresh token issues a new one and revokes
  the old. If an already-revoked token is presented, the whole `family_id` is
  revoked — that is the standard reuse-detection signal for a stolen token.
- New routes: `POST /api/auth/refresh`, `POST /api/auth/logout` (revokes the
  presented token), `POST /api/auth/logout-all`.

**Web:** refresh token in an **httpOnly, Secure, SameSite=Strict cookie**, not
`localStorage`. Web and API are same-origin behind nginx, so this costs
nothing and takes the long-lived credential out of reach of XSS. `SameSite=Strict`
covers CSRF for the refresh endpoint. The short-lived access token stays in
memory (not `localStorage`), refreshed on load.

**Android:** keeps bearer semantics — refresh token in
`EncryptedSharedPreferences`, sent in the request body. The endpoint accepts
either transport.

`authFetch` in `api.ts` grows a single-flight 401 → refresh → retry, with
concurrent 401s queued behind one refresh call rather than stampeding.

---

## PR 3 — Per-user ownership (the actual security fix)

Depends on PR 2. **This is the PR that closes the data exposure.**

**New:** a minimal migration runner, since the repo has no Flyway/Liquibase.
`db/Migrations.kt` + a `schema_migrations(version, applied_at)` table. Each
migration is an idempotent, numbered Kotlin function run inside one
transaction at startup, recorded on success. Small, but it makes every future
column change reviewable instead of ad hoc.

**Migration 001 — add ownership:**

1. `ALTER TABLE clients ADD COLUMN user_id VARCHAR(64)` (nullable at first).
2. Same for `visits`.
3. Backfill: `UPDATE clients SET user_id = :ownerId WHERE user_id IS NULL`,
   where `:ownerId` comes from `AURA_BACKFILL_USER_ID` in the VPS `.env`. If
   the variable is unset **and** exactly one user row exists, use that user; if
   it is unset and there are several users, **abort startup** rather than
   guess who owns the salon's data.
4. `SET NOT NULL`, add the FK to `users(id)`, add an index on `user_id` (every
   list query now filters on it).

**Route changes** — `ClientRoutes`, `VisitRoutes`, `AttachmentRoutes`:

- A `call.userId()` extension reading the `userId` claim off `JWTPrincipal`.
- Every read filters by owner. `GET /api/clients` gains
  `.select { ClientsTable.userId eq userId }` — note it currently does
  `selectAll()` and filters **in Kotlin**, which also means search scans every
  row in the table; this is a good moment to push the filter into SQL.
- Insert sets `user_id`.
- Update/delete verify ownership and return **404, not 403**, on someone
  else's record — 403 confirms the record exists, which is itself a leak.
- Attachments inherit ownership through `visit → client`.

**Rollback note:** the backfill is one-way. Take a `pg_dump` before deploying
this one. (Per `memory.md` there is no backup job yet — this PR is a good
forcing function for that.)

---

## PR 4 — Verification, reset, and the Android registration flow

Depends on PR 3.

**New table `auth_tokens`:** `id`, `user_id`, `token_hash`, `purpose`
(`VERIFY_EMAIL` | `RESET_PASSWORD`), `expires_at`, `used_at`. Single-use,
hashed at rest, same reasoning as refresh tokens.

**Mail:** Resend's HTTP API via the existing Ktor client — no SMTP ports to
open on the VPS, and better deliverability than raw SMTP from a
`duckdns.org` domain. New env: `MAIL_API_KEY`, `MAIL_FROM`, reusing the
existing `SITE_URL` for link construction. A `MailSender` interface with a
logging no-op implementation for development, so local work needs no API key.

**`users.email_verified BOOLEAN NOT NULL DEFAULT false`** (migration 002,
defaulting existing users to `true` — they predate the feature and should not
be locked out).

- Registration sends a verification link, 24-hour expiry.
- Login **succeeds** while unverified, but the authenticated routes return
  `403 {"error": "email_not_verified"}` so the client can show a "check your
  inbox / resend" state rather than a dead end.
- `POST /api/auth/resend-verification`, rate-limited hard.

**Reset:** `POST /api/auth/forgot-password` **always returns 202**, whether or
not the address exists — anything else is an account-enumeration oracle.
`POST /api/auth/reset-password` takes token + new password, runs the same
validator, and **revokes every refresh token** for that user (a reset must
evict a session the attacker holds).

**Android:**

- `register()` on `BeautyApi` + `KtorBeautyApi`, using the public
  `buildLoginClient()` (no bearer plugin).
- `AuthViewModel.register()` with a `RegisterState` mirroring `LoginState`,
  mapping 400 → field errors, 409 → "account exists".
- `RegisterScreen` composable: full name, email, password, confirm password,
  show/hide toggle, inline validation matching the backend rules exactly.
- Nav route `"register"`, reachable from `LoginScreen`.
- Password reset **deep-links to the web page** rather than reimplementing the
  flow natively — one reset UI to maintain, and the link arrives by email
  anyway.

**Web:** confirm-password, `autocomplete="email" / "new-password" /
"current-password"`, show/hide toggle, inline field errors from the new 400
body, routed through `api.ts`, plus `/verify-email` and `/reset-password`
screens.

**Rate limiting (B12):** tighten the nginx `auth_limit` zone from `5r/s` to
`10r/m burst=5`, add a separate stricter zone for `/register`,
`/forgot-password`, and `/resend-verification`. Add an application-level
per-account failed-login counter with exponential backoff, because an IP limit
alone does not stop a distributed attack on one account.

---

## Verification

Per PR, the `ci.yml` gates that already protect `main`:

- `cd backend && ./gradlew build`
- `cd web && npm run lint && npm run build`
- `cd android && ./gradlew assembleDebug testDebugUnitTest`

Plus, specific to this work:

- New backend tests for every validation rule and both enumeration defences.
- The PR 3 migration rehearsed against a **restored copy** of the production
  dump before it ever runs on the live database.
- Manual end-to-end: register on web → verify email → log in on Android with
  the same account → confirm each account sees **only** its own clients.
- Confirm `api.ts`'s `localStorage` fallback path still behaves after the
  auth changes (`CLAUDE.md` calls this out explicitly — a change to one path
  without the other silently breaks demo mode).

---

## Deployment notes for PR 2

**Everyone is logged out on deploy.** Existing tokens have no `exp` claim and
no matching row in `refresh_tokens`, so every current session ends the moment
this ships. Say so in the release notes. No data is affected.

`refresh_tokens` is a **new** table, so `SchemaUtils.create` handles it and no
hand-written migration is needed. That changes in PR 3, which alters existing
tables.

New environment variables, both optional with sane defaults —
`ACCESS_TOKEN_MINUTES` (default 15) and `REFRESH_TOKEN_DAYS` (default 30).
Added to `.env.example`.

**CORS changed in development only.** Credentialed requests cannot use a
wildcard origin, so the dev branch now lists `127.0.0.1`/`localhost` on ports
5173–5174 explicitly instead of `anyHost()`. A dev server on any other port
must be added to that list. Production CORS is untouched.

**The cookie is scoped to `path=/api/auth`.** It is only ever needed by the
refresh and logout endpoints, so it is not attached to ordinary API calls.

### Known follow-ups from PR 2

- Android's `SyncWorker` runs in the background and will now hit refresh on
  expiry like any other caller. Worth watching once real devices exercise it.
- No integration test covers refresh-token rotation or reuse detection; the
  backend has no test harness for routes yet, only the pure-unit
  `ValidationTest`. Worth adding alongside PR 3.

## Deliberately out of scope

Social login, 2FA/TOTP, CAPTCHA on registration, and account deletion / GDPR
export. Worth revisiting once real users exist — an app holding client photos
and personal contact details will eventually need the last one.
