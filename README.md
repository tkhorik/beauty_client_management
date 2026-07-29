# Aura Beauty Log

Beauty client and visit management monorepo with three runnable targets:

- `backend/` - Ktor REST API
- `web/` - React + Vite frontend
- `android/` - Jetpack Compose Android app

## What Testers Need

- Node.js 26+ and npm
- JDK 17
- Android Studio or the Android SDK command-line tools
- An Android emulator AVD named `Pixel_7a` or another device/emulator
- Optional: PostgreSQL on `localhost:5432` if you want the backend to use a real database

## Authentication

The app now requires login before accessing client data.

- The backend protects all `/api/clients`, `/api/visits`, and `/api/attachments` routes with JWT authentication. Requests without a valid Bearer token receive `401 Unauthorized`.
- `/api/auth/register`, `/login`, `/refresh` and `/logout` are public. `/logout-all` requires a token.
- **Registration rules**: passwords must be at least 12 characters and at most 72 bytes (BCrypt ignores anything beyond that). Emails are stored lowercase, so `Owner@x.com` and `owner@x.com` are the same account. Invalid input returns `400` with a `{"errors": {"<field>": "<message>"}}` body.
- **First run**: create an account using the "Create an account" toggle on the web login page, or with curl:

```bash
curl -s -X POST http://127.0.0.1:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"owner@example.com","password":"salon test passphrase","fullName":"Salon Owner"}'
```

### Sessions

There are two tokens, and they behave differently:

- The **access token** in the response is short-lived (15 minutes by default, `ACCESS_TOKEN_MINUTES`). Send it as `Authorization: Bearer <token>`.
- The **refresh token** lasts 30 days (`REFRESH_TOKEN_DAYS`) and is exchanged at `POST /api/auth/refresh` for a new access token. It is **single-use**: each refresh returns a replacement and invalidates the old one. Presenting a token that has already been used is treated as theft and revokes every session in that chain.

How the refresh token reaches the client depends on who is asking:

- **Browsers** send `X-Auth-Transport: cookie` and receive it in an httpOnly cookie, so page scripts cannot read it. `refreshToken` is then `null` in the JSON body.
- **Native clients and curl** send no such header and get it in the response body.

Testing the refresh path is easier with a short-lived token — see "Backend Testing" below.

> **Note for existing testers:** sessions created before this change are no longer valid. Log in again.

---

## Backend Testing

The backend is configured to try PostgreSQL first and fall back to in-memory H2 if PostgreSQL is missing. For local testing, H2 is enough. **H2 data is lost on restart** — re-register after each backend restart when using H2.

### Run the backend

```bash
cd /Users/marv/Projects/beauty_client_management/backend
JAVA_HOME=/Users/marv/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home ./gradlew run
```

What this does:

- starts the Ktor API
- listens on `http://127.0.0.1:8080`
- creates tables on startup
- uses PostgreSQL if available, otherwise falls back to H2 in memory

### Testing token refresh

A 15-minute access token is too long to wait out by hand. Start the backend
with a 1-minute one instead:

```bash
cd /Users/marv/Projects/beauty_client_management/backend
ACCESS_TOKEN_MINUTES=1 \
JAVA_HOME=/Users/marv/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home \
./gradlew run
```

Log in on the web app, wait ~70 seconds, then click around. Nothing should
happen visibly — that silence is the point. Confirm it actually refreshed by
looking for `POST /api/auth/refresh` in the browser's Network tab; otherwise
you cannot tell a working refresh from a token that simply had not expired yet.

Worth testing deliberately: open two tabs, let the token expire, then trigger
loads in both at once. Both should stay signed in. Refreshes are deduplicated
into one request precisely so that this does not look like token reuse.

### Quick backend checks

```bash
# Health check (public)
curl -s http://127.0.0.1:8080/

# Client list (requires Bearer token)
TOKEN="<paste token here>"
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/clients
```

Expected results:

- `/` returns `Beauty Client & Visit Management API v1.0.0 is running.`
- `/api/clients` without a token returns `401 Unauthorized`
- `/api/clients` with a valid token returns JSON (or `[]` on a fresh H2 start)

---

## Web Testing

The web app shows a login page on first load. After login the JWT token is stored in `localStorage` and all API calls include it automatically. If the backend is offline the app falls back to LocalStorage demo data (no login required in fallback mode).

### Run the web app

```bash
cd /Users/marv/Projects/beauty_client_management/web
npm run dev -- --host 127.0.0.1 --port 5174
```

Expected result:

- the app opens on `http://127.0.0.1:5174/`
- a login page is shown; enter the credentials created during registration
- after login the client directory loads

### Edit a client profile

Open any client card → click the detail panel → click **Edit Client**. A pre-filled modal opens. Save sends `PUT /api/clients/:id` with the Bearer token; the list and detail view refresh on success.

### Build the web app

```bash
cd /Users/marv/Projects/beauty_client_management/web
npm run build
```

---

## Android Testing

The Android app now starts on a **Login Screen**. After entering valid credentials the JWT token is stored in `EncryptedSharedPreferences` and the app navigates to the client directory. The token persists across app restarts — the login screen is only shown again after logout or on a fresh install.

The data path is:

`Android app → Ktor REST API (with Bearer token) → PostgreSQL (or local H2 fallback)`

Android never connects directly to PostgreSQL. Room is an on-device cache and offline visit-sync queue only.

### Android API behavior

- Start the backend before launching Android. The debug emulator build uses `http://10.0.2.2:8080/`, where `10.0.2.2` is the emulator alias for the host machine's loopback interface.
- On launch (after login), Android refreshes the client directory from `GET /api/clients` with a Bearer token and stores the result in Room. If the backend is unavailable, previously cached clients remain visible.
- Tapping a client card navigates to the **Edit Client** screen. Changes are sent via `PUT /api/clients/:id` and the Room cache is updated on success.
- Queued visits are uploaded by unique, network-constrained WorkManager work to `POST /api/visits`. A visit is marked synced only when the backend returns `201 Created`.
- Debug builds permit local HTTP. Release builds require an HTTPS API URL, supplied with `-PreleaseApiBaseUrl=https://api.example.com/` when building.

### Build the debug APK

```bash
cd /Users/marv/Projects/beauty_client_management/android
JAVA_HOME=/Users/marv/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home ./gradlew assembleDebug
```

What this does:

- compiles the Android app
- produces `app/build/outputs/apk/debug/app-debug.apk`

### Start the emulator

```bash
/Users/marv/Library/Android/sdk/emulator/emulator -avd Pixel_7a
```

### Check that Android can see the device

```bash
/opt/homebrew/bin/adb devices -l
```

### Install the app

```bash
/opt/homebrew/bin/adb install -r /Users/marv/Projects/beauty_client_management/android/app/build/outputs/apk/debug/app-debug.apk
```

### Launch the app

```bash
/opt/homebrew/bin/adb shell am start -n com.beauty.app/.MainActivity
```

### Confirm the app is running

```bash
/opt/homebrew/bin/adb shell pidof com.beauty.app
```

---

## Suggested Tester Order

1. Start the backend.
2. Register an account via the web login page (or curl).
3. Open the web app, log in, and verify the client directory loads.
4. Create a client, open its detail panel, and use **Edit Client** to update it.
5. Build and launch the Android app in the emulator.
6. Log in with the same credentials on Android, verify the client list loads, tap a card, and save an edit.
7. Confirm the change is reflected on both web and Android (both read from the same backend).
8. If PostgreSQL is available, rerun the backend against it for a real database check.

---

## Notes

- The backend can run without PostgreSQL because it falls back to H2. **H2 data resets on every backend restart.**
- The Android project expects its SDK path in `android/local.properties` on this machine.
- The emulator may be slow if the host machine has limited RAM.
- On an 8 GiB host, the `Pixel_7a` emulator may show a warning recommending 16 GiB of system RAM. This is an emulator image recommendation, not an application error. Click **OK** to continue.
- All auth-and-client-edit-sync changes live on branch `feature/auth-and-client-edit-sync`. The `main` branch is unchanged until an explicit commit is requested.
