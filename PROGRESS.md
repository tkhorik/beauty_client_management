# Configurable Android App Links

## Done
- Public JSON email verification endpoint sharing browser redemption.
- Independent website-origin build configuration and verified manifest filter.
- Activity inbox and token-free navigation for reset, verification, creation, and home links.
- Public association with certificate extracted from signed v1.4.4 APK; release certificate check.

## Remaining
- Automated security/lifecycle coverage and all CI gates.
- Build two origins and verify generated manifest/BuildConfig.
- Signed-device domain verification and Telegram external opening require deployment of association on host:443 (production currently uses :8443).
- Separate PR; no merge, tag, release, or deployment.
