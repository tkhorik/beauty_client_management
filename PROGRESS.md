# Configurable Android App Links

## Done
- Public JSON verification endpoint shares browser redemption and preserves redirects.
- SITE_URL-based release origin, independently configured API and development origins.
- Verified all-path manifest; transient memory-only link entry and clean main task.
- Reset/forgot/verification/creation/home routing, pending creation through authentication.
- Association verified against signed v1.4.4 certificate; release certificate guard.
- Backend build: 127 tests passed first attempt. Web lint/build passed (existing warnings).
- Android assembleDebug/testDebugUnitTest: 46 tests passed; two release origins verified.
- Missing, HTTP, path, userinfo, query, fragment and invalid-port production origins rejected.
- Reproducible origin checks added to CI.
- Standard HTTPS deployment verified: healthz returns 200 on 443; GitHub SITE_URL has no port and HTTPS_PORT=443.

## Remaining
- Finish emulator acceptance checks and verify production-origin release metadata.
- Separate PR; no automatic merge or deployment.
- After deploying association: verify a newly signed release APK on-device and Telegram external opening. Local release signing material is unavailable.
