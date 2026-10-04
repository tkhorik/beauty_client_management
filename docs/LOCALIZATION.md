# Localization: English and Russian

## Contract

The account preference is `system`, `en`, or `ru`. System is not converted into a
fixed language: each client resolves its own ordered device/browser language list,
selecting the first supported language and falling back to English. Language does
not change dates stored on the server, timezones, identifiers, or user content.

- `UserDto`: `languagePreference` and `languageRevision` are always serialized,
  including `system` and revision `0`.
- `PUT /api/users/me/language`: authenticated, organization-independent, available
  to unverified accounts. Request: `{ "preference": "ru", "expectedRevision": 0 }`.
- Success: `{ "preference": "ru", "revision": 1 }`. A stale revision returns 409
  with current values. Repeating the current preference succeeds without another
  revision, making a lost response safe to retry.
- Registration accepts optional `languagePreference`, default `system`.
- Explicit local changes apply immediately; synchronize on login, focus/resume,
  and reconnect. No polling. Pending edits belong to the authenticated account,
  never to whichever account signs in next. A newer server choice wins a conflict.
- Android Settings changes are detected at the next app start/resume. Programmatic
  locale application must not create a server write or recreation loop.

## Translation ownership

Web uses typed i18next catalogs and React hooks. Android uses native string and
plural resources, AppCompat per-app locales, and `localeConfig` for system settings.
Use complete messages with placeholders; do not concatenate translated fragments.
Use Russian plural forms, including counts 1, 2, 5, 11, 21, 22, and 25.

Add every application-owned label, tooltip, accessibility description, validation
message, and status to both catalogs. Brand names, entered notes/names/tags/custom
fields, filenames, and external release notes remain unchanged. Keep protocol enum
values unchanged and translate their display labels only.

Client errors use server `code` and `fieldErrors[field] = {code, args}`. The server
retains legacy English `error`/`errors` for older clients. Never identify an error
by matching its English text; unknown codes get a translated contextual fallback.

## Emails

Verification, password-reset, and password-changed mail has English/Russian subject,
plain text, and HTML. Explicit account preference wins. System uses the requesting
client's `Accept-Language`; unsupported/missing values fall back to English. Resolve
language before asynchronous dispatch. Preserve HTML escaping, token handling,
trusted SITE_URL origins, and indistinguishable forgot-password responses.

## Deployment

1. Back up the database using the normal operational process.
2. Apply `backend/migrations/007_account_language.sql` once, before the backend
   upgrade. This adds columns and constraints without deleting account data.
3. Deploy backend support first; older clients keep working with the additive API.
4. Release the localized web and Android clients.

Rolling back the backend does not require dropping the added columns. No deployment
or production migration is performed by the implementation task.

## Checks

Run backend `./gradlew build`, web `npm test && npm run lint && npm run build`, and
Android `./gradlew assembleDebug testDebugUnitTest`, plus the existing release-origin
App Links checks in CI. The migration test exercises existing-row preservation using
H2 PostgreSQL mode; it does not substitute for applying the migration to staging
PostgreSQL before production deployment.

Manually exercise English/Russian/System on web and Android, external Android Settings
changes while the app is stopped, offline edits with conflicts, login/logout/account
switches, multiple browser tabs, activity recreation with drafts, and narrow/large-font
layouts. System Settings language selection is available on Android 13 and newer;
older supported versions use the in-app picker.
