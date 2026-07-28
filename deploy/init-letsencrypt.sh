#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# One-time TLS bootstrap. Run this ONCE on the VPS, from the repo root, after
# DNS for your domain already points at this server:
#
#     ./deploy/init-letsencrypt.sh
#
# The chicken-and-egg problem this solves: Nginx refuses to start when the
# certificate files referenced by ssl_certificate do not exist, but Certbot
# cannot complete the HTTP-01 challenge unless Nginx is already serving
# /.well-known/acme-challenge/. So we install a throwaway self-signed cert,
# start Nginx, get the real certificate, then swap it in.
#
# Everything runs inside containers, using the OpenSSL bundled in those
# images. The host's independently upgraded OpenSSL is never involved, so an
# apt/source upgrade on the host cannot break issuance or renewal.
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
  echo "ERROR: .env not found. Copy .env.example to .env and fill it in first." >&2
  exit 1
fi

# shellcheck disable=SC1091
set -a; source .env; set +a

: "${DOMAIN:?DOMAIN must be set in .env}"
: "${CERTBOT_EMAIL:?CERTBOT_EMAIL must be set in .env}"

# Set STAGING=1 to use Let's Encrypt's staging CA while you are still testing.
# The production CA allows only 5 failed attempts per domain per hour; the
# staging CA is effectively unlimited but issues untrusted certificates.
STAGING="${STAGING:-0}"
STAGING_FLAG=""
if [[ "$STAGING" == "1" ]]; then
  STAGING_FLAG="--staging"
  echo ">>> Using the Let's Encrypt STAGING environment (certificates will NOT be trusted)."
fi

# Include www.<domain> in the certificate. Set INCLUDE_WWW=0 if you have not
# created a DNS record for www — Let's Encrypt fails the whole request if any
# single requested name does not resolve to this server.
INCLUDE_WWW="${INCLUDE_WWW:-1}"
DOMAIN_ARGS="-d ${DOMAIN}"
if [[ "$INCLUDE_WWW" == "1" ]]; then
  DOMAIN_ARGS="${DOMAIN_ARGS} -d www.${DOMAIN}"
fi

CERT_PATH="/etc/letsencrypt/live/${DOMAIN}"

echo ">>> [1/5] Downloading recommended TLS parameters..."
docker compose run --rm --entrypoint sh certbot -c "
  mkdir -p /etc/letsencrypt &&
  [ -f /etc/letsencrypt/ssl-dhparams.pem ] ||
  openssl dhparam -out /etc/letsencrypt/ssl-dhparams.pem 2048
"

echo ">>> [2/5] Creating a temporary self-signed certificate for ${DOMAIN}..."
docker compose run --rm --entrypoint sh certbot -c "
  mkdir -p '${CERT_PATH}' &&
  openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
    -keyout '${CERT_PATH}/privkey.pem' \
    -out '${CERT_PATH}/fullchain.pem' \
    -subj '/CN=localhost'
"

echo ">>> [3/5] Starting the edge proxy so the ACME challenge can be served..."
docker compose up -d proxy
sleep 5

echo ">>> [4/5] Requesting the real certificate from Let's Encrypt..."
# --force-renewal is required here because a certificate (the self-signed one)
# already exists at that path.
docker compose run --rm --entrypoint sh certbot -c "
  rm -rf '${CERT_PATH}' /etc/letsencrypt/archive/${DOMAIN} /etc/letsencrypt/renewal/${DOMAIN}.conf &&
  certbot certonly --webroot -w /var/www/certbot \
    ${STAGING_FLAG} \
    --email '${CERTBOT_EMAIL}' \
    ${DOMAIN_ARGS} \
    --rsa-key-size 2048 \
    --agree-tos \
    --no-eff-email \
    --force-renewal
"

echo ">>> [5/5] Reloading Nginx with the real certificate..."
docker compose exec proxy nginx -s reload

echo
echo "Done. https://${DOMAIN} should now serve a trusted certificate."
echo "Renewal is automatic: the certbot container retries twice a day and the"
echo "proxy reloads every 6 hours. Verify with:"
echo "    docker compose run --rm certbot renew --dry-run"
