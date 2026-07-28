/**
 * Single source of truth for the backend API base URL.
 *
 * Default is the relative path `/api`, which means the browser calls the same
 * origin it loaded the app from. In production the edge Nginx proxies `/api`
 * to the backend container, so there is no CORS and no hardcoded hostname.
 *
 * Override at BUILD time with a `VITE_API_BASE_URL` env var, e.g. for pointing
 * a local dev server at a remote backend:
 *   VITE_API_BASE_URL=http://127.0.0.1:8080/api npm run dev
 *
 * Note: Vite inlines env vars at build time, so this value is baked into the
 * bundle. Never put a secret in a VITE_* variable.
 */
const RAW_BASE = import.meta.env.VITE_API_BASE_URL ?? '/api';

/**
 * Resolved to an absolute URL so that `new URL(...)` call sites keep working
 * (the URL constructor rejects relative strings without a base).
 */
export const API_BASE_URL = new URL(RAW_BASE, window.location.origin).href.replace(/\/+$/, '');
