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

## Backend Testing

The backend is configured to try PostgreSQL first and fall back to in-memory H2 if PostgreSQL is missing. For local testing, H2 is enough.

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

### Quick backend checks

```bash
curl -s http://127.0.0.1:8080/
curl -s http://127.0.0.1:8080/api/clients
```

What these do:

- confirms the API process is running
- confirms the client endpoint responds

Expected result:

- `/` returns `Beauty Client & Visit Management API v1.0.0 is running.`
- `/api/clients` returns JSON, or `[]` on a fresh in-memory H2 start

## Web Testing

The web app talks to the backend first and falls back to LocalStorage demo data if the backend is offline.

### Run the web app

```bash
cd /Users/marv/Projects/beauty_client_management/web
npm run dev -- --host 127.0.0.1
```

What this does:

- starts the Vite dev server
- binds to localhost
- serves the UI in the browser

Expected result:

- the app opens on `http://127.0.0.1:5173/` or `http://127.0.0.1:5174/` if 5173 is already taken

### Build the web app

```bash
cd /Users/marv/Projects/beauty_client_management/web
npm run build
```

What this does:

- type-checks the frontend
- produces a production build in `web/dist/`

## Android Testing

The Android app is a local debug build that runs on an emulator or physical device. Its data path is:

`Android app → Ktor REST API → PostgreSQL (or local H2 fallback)`

Android never connects directly to PostgreSQL. Room is an on-device cache and offline visit-sync queue only.

### Android API behavior

- Start the backend before launching Android. The debug emulator build uses `http://10.0.2.2:8080/`, where `10.0.2.2` is the emulator alias for the host machine's loopback interface.
- On launch, Android refreshes the client directory from `GET /api/clients` and stores the result in Room. If the backend is unavailable, previously cached clients remain visible.
- Queued visits are uploaded by unique, network-constrained WorkManager work to `POST /api/visits`. A visit is marked synced only when the backend returns `201 Created`; transient failures remain queued and are retried.
- Debug builds permit local HTTP so they can reach the local backend. Release builds require an HTTPS API URL, supplied with `-PreleaseApiBaseUrl=https://api.example.com/` when building.

The current mobile UI is a client directory only. The repository supports queueing visits for later UI work, but visit-entry UI, authentication, and attachment sync are not yet implemented.

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

What this does:

- launches the local Android emulator
- boots the `Pixel_7a` virtual device

### Check that Android can see the device

```bash
/opt/homebrew/bin/adb devices -l
```

What this does:

- lists connected devices and emulators

Expected result:

- the emulator shows up as `device`

### Install the app

```bash
/opt/homebrew/bin/adb install -r /Users/marv/Projects/beauty_client_management/android/app/build/outputs/apk/debug/app-debug.apk
```

What this does:

- installs or updates the debug APK on the emulator

### Launch the app

```bash
/opt/homebrew/bin/adb shell am start -n com.beauty.app/.MainActivity
```

What this does:

- opens the app on the emulator
- shows the main client directory screen

### Confirm the app is running

```bash
/opt/homebrew/bin/adb shell pidof com.beauty.app
```

What this does:

- checks that the app process is alive

Expected result:

- a numeric process id is returned

## Suggested Tester Order

1. Start the backend.
2. Open the web app and verify it loads client data.
3. Build and launch the Android app in the emulator.
4. If PostgreSQL is available, rerun the backend against it for a real database check.

## Notes

- The backend can run without PostgreSQL because it falls back to H2.
- The Android project expects its SDK path in `android/local.properties` on this machine.
- The emulator may be slow if the host machine has limited RAM.
- On an 8 GiB host, the `Pixel_7a` emulator may show a warning recommending 16 GiB of system RAM. This is an emulator image recommendation, not an application error. The launcher uses 2 GiB for the emulator, which is sufficient for local smoke testing; click `OK` to continue.
