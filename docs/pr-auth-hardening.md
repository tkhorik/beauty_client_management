# Auth hardening: registration validation + token expiry & rotation

Implements PRs 1 and 2 of `docs/auth-hardening-plan.md`.

## Why

Three problems, in order of severity:

1. **`/api/auth/register` validated nothing.** A one-character password was
   accepted. Email was not normalised, so `Foo@x.com` and `foo@x.com` became
   two accounts — and anyone who registered with capitals could not log in
   with lowercase.
2. **JWTs never expired.** `generateJwtToken` never set an `exp` claim, so
   every token ever issued was valid forever with no revocation path. A leaked
   token was a permanent compromise, and "log out" only cleared the client's
   copy.
3. **Android had no registration at all** — no `register()` on the API, no UI,
   no route. An Android-only user could not create an account.

## What changed

### Backend

- New `validation/Validation.kt`: email normalisation and format checks,
  12-character password minimum, and a 72-**byte** maximum. That upper bound
  matters — BCrypt silently discards everything past 72 bytes, so long
  passphrases were being accepted and quietly truncated.
- `AuthRoutes.kt`: validates before touching the database and returns per-field
  400s; catches the unique-constraint violation so a concurrent duplicate
  registration returns 409 instead of a generic 500; normalises email on login
  too; always runs a BCrypt comparison (against a fixed dummy hash when the
  account is absent) to close the login timing oracle.
- New `RefreshTokensTable` and `auth/RefreshTokenService.kt`: 256-bit tokens
  from `SecureRandom`, stored as SHA-256 hashes, rotated on every use, with
  family-wide revocation on reuse.
- `generateJwtToken` now **requires** an expiry parameter rather than
  defaulting one. Making it mandatory is what keeps the original bug fixed.
- New routes: `POST /api/auth/refresh`, `/logout`, and `/logout-all`
  (authenticated — evicting a stolen session should not require holding the
  stolen token).
- New `call.userId()` helper, needed by the ownership work in PR 3.
- First backend tests: `ValidationTest` (14 cases).

### Web

- Access token moved out of `localStorage` into memory; the refresh token is
  delivered in an httpOnly, `SameSite=Strict` cookie scoped to `/api/auth`, so
  no script on the page can read it.
- `authFetch` refreshes and retries once on 401, through a **single shared
  promise**. Without that, several requests expiring together would each start
  their own refresh; the second would present a token the first had already
  spent, the backend would correctly read that as theft, and the user would be
  logged out by their own app.
- Registration form: confirm-password, `autocomplete` attributes, show/hide
  toggle, inline per-field errors.

### Android

- Registration flow: `register()` on the API, `RegisterScreen`, nav route, and
  `AuthValidation` mirroring the backend rules.
- Refresh token in `EncryptedSharedPreferences`, wired into Ktor's
  `bearer { refreshTokens { } }`. The refresh call uses a **separate client
  with no Auth plugin**, so a 401 from the refresh endpoint cannot recurse back
  into the refresh block.
- Logout now revokes server-side instead of only clearing local storage.

## Deploying this logs every user out

Existing tokens have no `exp` claim and no row in `refresh_tokens`, so all
current sessions end when this ships. No data is affected.

## Required before merge

Email normalisation will strand any account stored with capitals. Check first:

```sql
SELECT lower(email), count(*) FROM users GROUP BY 1 HAVING count(*) > 1;
```

- Empty result → `UPDATE users SET email = lower(email);` is safe.
- Rows returned → those duplicate accounts must be merged by hand first, or
  the unique index will reject the update.

## Notes for the reviewer

- `refresh_tokens` is a **new** table, so `SchemaUtils.create` handles it and
  no hand-written migration is needed. That changes in PR 3, which alters
  existing tables.
- New optional env vars, both defaulted: `ACCESS_TOKEN_MINUTES` (15) and
  `REFRESH_TOKEN_DAYS` (30). Added to `.env.example`.
- **Development CORS changed.** Credentialed requests cannot use a wildcard
  origin, so the dev branch lists `127.0.0.1`/`localhost` on ports 5173–5174
  explicitly instead of `anyHost()`. Production CORS is untouched. Note the
  Vite dev proxy makes local dev same-origin anyway, so this rarely applies.
- No integration test covers refresh rotation or reuse detection — the backend
  has no route-test harness yet, only pure unit tests. Worth adding with PR 3.

## Still open (PR 3, the important one)

`clients` and `visits` still have no `user_id`. Registration is open on a
public domain, so **any stranger who signs up can still read and delete every
client record**. That is the actual data exposure, and it is not fixed here.
