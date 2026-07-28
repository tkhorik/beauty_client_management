# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# Stage 1 — build the static bundle with Node.
# ---------------------------------------------------------------------------
# The repo's README requires Node 26+. Change this tag if you move versions.
ARG NODE_IMAGE=node:26-alpine
FROM ${NODE_IMAGE} AS build

WORKDIR /app

# npm ci (not npm install) installs exactly what package-lock.json pins, so the
# CI build is reproducible and cannot silently pick up a new transitive version.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Vite inlines env vars at BUILD time. The default `/api` means the browser
# calls the same origin it loaded the app from, which the edge Nginx proxies to
# the backend — so no CORS and no hostname baked into the bundle.
# NEVER pass a secret here: it ends up in the public JavaScript.
ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

RUN npm run build

# ---------------------------------------------------------------------------
# Stage 2 — serve the static files with Nginx. No Node in the final image.
# ---------------------------------------------------------------------------
FROM nginx:1.27-alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q --spider http://127.0.0.1/ || exit 1
