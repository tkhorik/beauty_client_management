# Aura Beauty Log — Production Deployment Runbook

Target architecture:

```
                     Internet
                        │  :443 (TLS)
                        ▼
        ┌───────────────────────────────┐
        │  proxy  (nginx:1.27-alpine)   │  ← only container with published ports
        │  TLS termination, redirects   │
        └───────┬───────────────┬───────┘
       /        │               │  /api/  /uploads/  /healthz
                ▼               ▼
        ┌──────────────┐  ┌──────────────┐
        │ web (nginx)  │  │ backend      │
        │ static SPA   │  │ Ktor, JDK 17 │
        └──────────────┘  └──────┬───────┘
                                 │ internal network only
                                 ▼
                          ┌──────────────┐      ┌──────────┐
                          │ db postgres15│      │ certbot  │
                          └──────────────┘      └──────────┘

  Android app ──────── HTTPS ────────► https://<domain>/api/...
```

Two rules the whole design rests on:

1. **Only the proxy is exposed.** Postgres and the backend have no `ports:` mapping at all, so no firewall misconfiguration can accidentally expose the database to the internet.
2. **Images are built in CI, never on the VPS.** The server only pulls and restarts. Deploys take seconds instead of minutes, a broken build never reaches production, and rolling back is just deploying an older tag.

---

## 0. What changed in the application code

These were fixed as part of this setup — they would each have broken production:

| Problem | Where | Fix |
|---|---|---|
| API URL hardcoded to `http://localhost:8080/api` | `web/src/services/api.ts`, `web/src/components/LoginPage.tsx` | Both now import `API_BASE_URL` from `web/src/config.ts`, which defaults to the relative `/api`. A remote user's browser was calling *their own* machine. |
| JWT secret and DB password committed to git | `backend/src/main/resources/application.conf` | All values now come from environment variables; the file holds dev-only defaults. **Rotate the old secret — it is in your git history.** |
| Silent fallback to in-memory H2 when Postgres is unreachable | `backend/.../DatabaseFactory.kt` | Disabled whenever `APP_ENV=production`. Previously the API would report healthy while discarding every write. |
| `anyHost()` CORS | `backend/.../Routing.kt` | Production allows only origins in `ALLOWED_ORIGINS` (empty by default — the app is same-origin). |
| No connection pool | `backend/.../DatabaseFactory.kt` | HikariCP added. Exposed otherwise opens a new JDBC connection per transaction. |
| Uploaded filenames used unsanitised | `backend/.../AttachmentRoutes.kt` | Path components stripped; a filename like `../../app.jar` could previously write outside the upload directory. |
| Exception messages returned to clients | `backend/.../Routing.kt` | Production returns a generic message and logs the detail. |

The app now **refuses to start** in production if `JWT_SECRET` is still the development value, is under 32 characters, if `DB_PASSWORD` is blank or default, or if `DB_URL` is not PostgreSQL. A container that won't boot is far better than one quietly running insecurely.

---

## 1. Prepare the VPS (once)

Assumes Ubuntu 22.04 or 24.04 and a fresh root login.

### 1.1 Create a non-root user and lock down SSH

```bash
adduser deploy
usermod -aG sudo deploy
rsync --archive --chown=deploy:deploy ~/.ssh /home/deploy
```

Then, in `/etc/ssh/sshd_config`:

```
PermitRootLogin no
PasswordAuthentication no
PubkeyAuthentication yes
```

```bash
systemctl restart ssh
```

Keep your current session open until you have confirmed you can log in as `deploy` in a second terminal.

### 1.2 Firewall

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status verbose
```

Do **not** open 5432 or 8080. Docker publishes ports by writing directly to iptables and can bypass UFW rules, which is exactly why this compose file publishes nothing except the proxy.

### 1.3 Automatic security updates

```bash
sudo apt update && sudo apt install -y unattended-upgrades
sudo dpkg-reconfigure --priority=low unattended-upgrades
```

### 1.4 Docker Engine + Compose plugin

Use Docker's own repository, not the Ubuntu package — the distro version is old and ships the deprecated `docker-compose` v1.

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | \
  sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io \
  docker-buildx-plugin docker-compose-plugin

sudo usermod -aG docker deploy   # log out and back in for this to take effect
docker compose version
```

### 1.5 Swap (recommended on 1–2 GB VPS instances)

The JVM plus Postgres on a 1 GB box will OOM without it:

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

---

## 2. DNS

At your registrar, create:

| Type | Name | Value |
|---|---|---|
| A | `@` | your VPS IPv4 |
| A | `www` | your VPS IPv4 |
| AAAA | `@` | your VPS IPv6 (if you have one) |

Wait until `dig +short yourdomain.com` returns the VPS IP before running Certbot. Let's Encrypt allows only **5 failed validation attempts per hostname per hour**, so verifying DNS first saves you an hour of waiting.

If you skip the `www` record, run the bootstrap script with `INCLUDE_WWW=0` — Let's Encrypt fails the *entire* request if any single requested name does not resolve.

---

## 3. First deployment

```bash
sudo mkdir -p /srv/aura && sudo chown deploy:deploy /srv/aura
cd /srv/aura
git clone https://github.com/YOUR_USER/beauty_client_management.git .

cp .env.example .env
chmod 600 .env
nano .env
```

Generate real secrets:

```bash
openssl rand -base64 32   # POSTGRES_PASSWORD
openssl rand -base64 48   # JWT_SECRET
```

Then bootstrap TLS and start everything:

```bash
chmod +x deploy/init-letsencrypt.sh

# Dry run against the Let's Encrypt staging CA first — unlimited retries,
# untrusted certificate. Recommended for the first attempt.
STAGING=1 ./deploy/init-letsencrypt.sh

# Once that succeeds, get the real certificate:
./deploy/init-letsencrypt.sh

docker compose up -d
docker compose ps
docker compose logs -f backend
```

Verify:

```bash
curl -I  https://yourdomain.com/           # 200, SPA
curl -sf https://yourdomain.com/healthz    # {"status":"ok"}
curl -I  http://yourdomain.com/            # 301 to https
```

Create the first account:

```bash
curl -s -X POST https://yourdomain.com/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"owner@example.com","password":"a-strong-password","fullName":"Salon Owner"}'
```

> Because the JWT issuer/audience changed from the old `http://0.0.0.0:8080/` values, any tokens issued by the old local build are no longer valid. Everyone logs in once more; nothing else is affected.

---

## 4. HTTPS and your independently-managed OpenSSL

This is the part your setup specifically needs care with.

**The problem with host-installed Certbot.** Ubuntu's `certbot` package (and the `python3-certbot-nginx` plugin) links against the system `libssl`/`python3-cryptography`. When OpenSSL is upgraded or replaced outside of `apt` — compiled from source, installed to `/usr/local/ssl`, or pinned to a different ABI — the Python `cryptography` wheel that Certbot depends on is built against the *old* ABI. The typical failure is silent: renewal runs from a systemd timer at 3am, throws `ImportError: libssl.so.3: version 'OPENSSL_3.2.0' not found`, and you discover it 60 days later when the certificate expires and the Android app stops trusting the API.

**The fix used here: Certbot runs in a container.** The `certbot/certbot` image ships its own Python, its own `cryptography` wheel and its own OpenSSL, all pinned together by the image author. It shares nothing with the host except two Docker volumes holding the certificate files. Consequences:

- Upgrading, downgrading or rebuilding OpenSSL on the host **cannot** affect issuance or renewal.
- Nginx also runs in a container (`nginx:1.27-alpine`), so the TLS *termination* stack is likewise independent of the host's OpenSSL. Your custom host OpenSSL is only used by things you run directly on the host — `openssl s_client`, `curl`, package management.
- Renewal is a loop inside the certbot container (`certbot renew` every 12h), plus an `nginx -s reload` every 6h in the proxy. No cron, no systemd timer, no host Python.

The one thing to be aware of: **`openssl` on your host may report a different version than what actually terminates your TLS.** To inspect what the *server* really negotiates, ask it over the network rather than trusting the local binary:

```bash
# What the containerised Nginx actually offers
docker compose exec proxy nginx -V 2>&1 | grep -i openssl
echo | openssl s_client -connect yourdomain.com:443 -tls1_3 2>/dev/null | grep -E 'Protocol|Cipher'
```

Test renewal without consuming quota:

```bash
docker compose run --rm certbot renew --dry-run
```

Check the expiry date any time:

```bash
docker compose run --rm --entrypoint certbot certbot certificates
```

If you ever want to bypass Let's Encrypt entirely, the `certbot_conf` volume just needs `fullchain.pem` and `privkey.pem` under `/etc/letsencrypt/live/<domain>/` — any CA's files work.

---

## 5. GitHub configuration

### 5.1 Branch protection on `main`

**Settings → Branches → Add rule**, branch name pattern `main`:

- ✅ Require a pull request before merging (1 approval)
- ✅ Require status checks to pass — select `Backend (Ktor / JDK 17)`, `Web (React / Vite)`, `Android (Compose)` from `ci.yml`
- ✅ Require branches to be up to date before merging
- ✅ Do not allow bypassing the above settings

With this in place, the only way a commit reaches `main` is an approved, green pull request — which is precisely why `deploy.yml` can safely trigger on `push: branches: [main]`. That fires exactly once per merged PR. The alternative, `pull_request: types: [closed]`, also fires on *rejected* PRs and needs an `if: github.event.pull_request.merged == true` guard that is easy to forget.

### 5.2 Deploy key for the VPS

On the VPS, generate a key pair whose private half lives only in GitHub Secrets:

```bash
ssh-keygen -t ed25519 -C "github-actions-deploy" -f ~/.ssh/gh_deploy -N ""
cat ~/.ssh/gh_deploy.pub >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
cat ~/.ssh/gh_deploy        # copy this entire private key into VPS_SSH_KEY
rm ~/.ssh/gh_deploy         # remove the private key from the server
```

Optionally restrict what that key may do by prefixing the line in `authorized_keys` with `from="140.82.0.0/16,143.55.64.0/20"` (GitHub's Actions ranges change; check https://api.github.com/meta).

### 5.3 Secrets

**Settings → Secrets and variables → Actions → New repository secret:**

| Secret | Value |
|---|---|
| `VPS_HOST` | VPS IP or hostname |
| `VPS_USER` | `deploy` |
| `VPS_SSH_KEY` | contents of the private key from 5.2 |
| `VPS_SSH_PORT` | `22` (or your custom port) |
| `VPS_APP_DIR` | `/srv/aura` |
| `GHCR_PULL_TOKEN` | a classic PAT with **only** `read:packages` |

**Variables** tab:

| Variable | Value |
|---|---|
| `DOMAIN` | `yourdomain.com` |
| `SITE_URL` | `https://yourdomain.com` — canonical public origin, **no trailing slash** |

`SITE_URL` is what CI uses for the post-deploy health check, the `production`
environment link, and the API base URL baked into the Android release APK. If
HTTPS is published on a non-standard host port (see `HTTPS_PORT` in `.env`),
put the port in `SITE_URL` too: `https://yourdomain.com:8443`. It is kept
separate from `HTTPS_PORT` deliberately — `HTTPS_PORT` is a server-side port
binding, `SITE_URL` is the address clients use, and conflating them means the
origin string gets reassembled in four places instead of stated once. If
`SITE_URL` is unset, CI falls back to `https://${DOMAIN}`.

`SITE_URL` must be the origin **the web app is served from**, not just any
public address for the API. The password-reset email links to
`$SITE_URL/reset-password?token=…`, which is a page in the SPA — the backend
never sees that request. Get the origin or the port wrong and every reset link
lands on a 404 or on someone else's site, while the endpoint itself looks
perfectly healthy. The same applies to `/verify-email`, where
`GET /api/auth/verify-email` redirects after redeeming its token.

Both paths rely on the SPA fallback in `web/nginx.conf`
(`try_files $uri $uri/ /index.html`); if that is ever narrowed, the reset links
break with it.

Android releases additionally need these **secrets** (see §7.1):
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`.

`GHCR_PULL_TOKEN` is read-only on purpose: if the VPS is ever compromised, the attacker gets the ability to pull your images, not to push a malicious one that then gets deployed. (If you make the GHCR packages public you can drop this secret and the `docker login` line entirely.)

### 5.4 Environment

**Settings → Environments → New environment → `production`.** Add required reviewers if you want a human to approve each deploy. The deploy job already references it.

---

## 6. Day-two operations

### Deploy
Merge a PR into `main`. That's it. Watch it in the Actions tab.

**Except when the change ships a migration.** There is no Flyway or Liquibase
here, and `SchemaUtils.create` only creates whole missing tables — it never
alters an existing one. Files in `backend/migrations/` are run **by hand, on the
VPS, before** merging the version that expects them. Deploying first means the
new code meets the old schema and 500s on every affected route.

```bash
cd /srv/aura
docker compose exec -T db psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  < backend/migrations/00X_whatever.sql
```

Current migrations, in order:

| File | Adds | Destructive? |
|---|---|---|
| `001_email_verification.sql` | `users.email_verified_at`, backfills existing accounts as verified | No |
| `002_multi_tenant_rbac.sql` | `organizations`, `user_organizations`, `users.global_role`, `organization_id`/`created_by` on `clients` and `visits` | **Yes** |

`002` is destructive by design: `organization_id` is `NOT NULL` with no backfill,
because there is no correct organization to guess for a pre-existing row, so it
**deletes all existing clients, visits and attachments**. That was the accepted
trade for this cutover. If you are reading this with data you care about, do not
run it as-is — take a `pg_dump` first, then rewrite it to create one
organization, `UPDATE` the rows to point at it, and add the constraint last.

After `002`, existing users belong to no organization and see the onboarding
screen. They create one or ask to join by handle; there is no automatic
migration of who belongs where. Set `SUPER_ADMIN_EMAILS` in `.env` only if you
actually need an account with cross-organization access — normal operation
doesn't.

### Roll back
**Actions → Deploy to production → Run workflow**, and enter the commit SHA of the last known-good deploy in `image_tag`. Every build is tagged by SHA, so rollback is a 30-second image swap, not a revert-and-rebuild.

### Logs
```bash
docker compose logs -f backend
docker compose logs --tail=100 proxy
```

### Database backups

Nothing else in this stack is irreplaceable — the database is. Add a nightly dump:

```bash
sudo tee /etc/cron.daily/aura-backup >/dev/null <<'EOF'
#!/bin/sh
set -e
cd /srv/aura
mkdir -p /srv/backups
. ./.env
docker compose exec -T db pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" \
  | gzip > "/srv/backups/aura-$(date +%F).sql.gz"
find /srv/backups -name 'aura-*.sql.gz' -mtime +14 -delete
EOF
sudo chmod +x /etc/cron.daily/aura-backup
```

A backup you have never restored is a hypothesis, not a backup. Test it:

```bash
gunzip -c /srv/backups/aura-2026-07-27.sql.gz | \
  docker compose exec -T db psql -U aura -d beautydb_restore_test
```

Also copy the dumps off the VPS (`rclone`, `scp`, object storage) — a backup on the same disk as the database does not survive the failure mode you are actually worried about.

Uploaded photos live in the `uploads` Docker volume. Back that up too:

```bash
docker run --rm -v aura_uploads:/data -v /srv/backups:/backup alpine \
  tar czf /backup/uploads-$(date +%F).tar.gz -C /data .
```

### Disk space
```bash
df -h && docker system df
docker image prune -a --filter "until=336h"
```

---

## 7. Android app: pointing at the production backend

The data path is unchanged and correct as designed:

```
Android app ──HTTPS + Bearer token──► Ktor API ──internal network──► PostgreSQL
```

Android must **never** talk to PostgreSQL directly. It would require exposing port 5432 to the internet, shipping database credentials inside an APK (trivially extractable with `apktool`), and giving every phone unrestricted read/write on every table. Room stays what it is: an on-device cache and offline sync queue.

### 7.1 The base URL

`android/app/build.gradle.kts` already does the right thing — debug and release builds get different `BuildConfig.API_BASE_URL` values, and the release default is deliberately invalid so a misconfigured build fails loudly rather than silently pointing at localhost:

```kotlin
debug {
    buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
}
release {
    val releaseApiBaseUrl = providers.gradleProperty("releaseApiBaseUrl")
        .orElse("https://api.example.invalid/")
        .get()
    buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
}
```

`AppContainer.buildClient()` feeds that into `defaultRequest { url(BuildConfig.API_BASE_URL) }`, and `BeautyApi` issues relative paths (`api/clients`, `api/auth/login`). So the value must be the **origin with a trailing slash and no `/api` suffix**:

```
https://yourdomain.com/
```

A missing trailing slash is the classic bug here: Ktor resolves `api/clients` against `https://yourdomain.com` as `https://yourdomain.com/api/clients` only when the base ends in `/`. Without it you can end up one path segment short.

### 7.2 Build the release APK

```bash
cd android
./gradlew assembleRelease -PreleaseApiBaseUrl=https://yourdomain.com/
```

For Play Store distribution build a bundle instead, and sign it:

```bash
./gradlew bundleRelease -PreleaseApiBaseUrl=https://yourdomain.com/
```

Signing config (keep `keystore.properties` out of git — it is already in `.gitignore`):

```kotlin
// android/app/build.gradle.kts
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile") ?: "unused.jks")
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true   // enable once you have verified ProGuard rules
        }
    }
}
```

### 7.3 Cleartext traffic

`app/src/debug/AndroidManifest.xml` sets `usesCleartextTraffic="true"`, and the main manifest does not. That is exactly right: the emulator can reach `http://10.0.2.2:8080`, while release builds are blocked from plain HTTP by Android's default since API 28. **Do not add `usesCleartextTraffic` to the main manifest** to "fix" a connection problem — if a release build cannot connect, the cause is DNS, the certificate, or the base URL, and disabling TLS enforcement would send bearer tokens over the network in the clear.

If you later need a stricter posture, add certificate pinning via a `network_security_config.xml`. Be careful: a pinned certificate that you then rotate bricks every installed app until users update. Pin to the CA or include a backup pin.

### 7.4 Verify from a device

```bash
adb install app/build/outputs/apk/release/app-release.apk
adb logcat | grep -i ktor
```

Expected: login succeeds, `GET /api/clients` returns 200, and the Room cache populates. If you get `SSLHandshakeException`, the certificate chain is incomplete — confirm Nginx is serving `fullchain.pem` (not `cert.pem`), which the config above already does.

---

## 8. Once this is stable, consider

- **Flyway or Liquibase migrations.** `SchemaUtils.create` only ever creates missing tables; it never alters an existing column. The first time you change a model in production you will need real migrations.
- **A staging environment.** A second VPS (or a second compose project on the same box with a different domain) that deploys from a `develop` branch, so the Selenium tests have somewhere realistic to run.
- **Backups off-site**, as noted above.
- **Uptime monitoring** hitting `/healthz` — UptimeRobot or Healthchecks.io, free tier is enough.
- **Log rotation.** Add to `docker-compose.yml` under each service, or globally in `/etc/docker/daemon.json`:
  ```json
  { "log-driver": "json-file", "log-opts": { "max-size": "10m", "max-file": "3" } }
  ```
- **Rotate the JWT secret and DB password that are in your git history**, if that repository is or ever becomes public.
