import { setToken, clearToken } from './tokenStore';
import { API_BASE_URL } from '../config';
import type { UserProfile } from '../types';

/**
 * Tells the backend to deliver the refresh token as an httpOnly cookie rather
 * than in the response body, so that no script on this page can read it.
 */
export const AUTH_TRANSPORT_HEADERS: Record<string, string> = {
  'X-Auth-Transport': 'cookie',
};

/**
 * `credentials: 'include'` is required for the browser to send and accept the
 * refresh cookie. Without it the cookie is silently ignored on cross-origin
 * requests — which is exactly the dev setup (Vite on :5174, API on :8080).
 */
const AUTH_FETCH_INIT: RequestInit = {
  credentials: 'include',
  headers: { 'Content-Type': 'application/json', ...AUTH_TRANSPORT_HEADERS },
};

interface AuthResponseBody {
  token: string;
  expiresInSeconds: number;
  user: UserProfile;
}

/** What a successful login, register, refresh, or password change hands back. */
export interface SessionResult {
  token: string;
  user: UserProfile;
}

/**
 * The in-flight refresh, if any.
 *
 * When an access token expires, every queued request fails with 401 at roughly
 * the same moment. Without this, each one starts its own refresh — and since
 * refresh tokens rotate and single-use, the second request would present a
 * token the first had already spent, which the backend correctly reads as
 * token reuse and responds to by revoking the entire family. The user would be
 * logged out by their own app. Sharing one promise is not an optimisation
 * here; it is what makes rotation workable.
 */
let inFlightRefresh: Promise<SessionResult | null> | null = null;

/**
 * Exchanges the refresh cookie for a new access token. Resolves to the new
 * token and the profile it belongs to, or null when the session is over and
 * the user must sign in again.
 */
export function refreshAccessToken(): Promise<SessionResult | null> {
  if (inFlightRefresh) return inFlightRefresh;

  inFlightRefresh = (async () => {
    try {
      const res = await fetch(`${API_BASE_URL}/auth/refresh`, {
        ...AUTH_FETCH_INIT,
        method: 'POST',
        body: JSON.stringify({}),
      });

      if (!res.ok) {
        clearToken();
        return null;
      }

      const data: AuthResponseBody = await res.json();
      setToken(data.token);
      return { token: data.token, user: data.user };
    } catch {
      // Network failure is not an expired session — keep whatever token we
      // have and let the caller's own error handling deal with being offline.
      return null;
    } finally {
      inFlightRefresh = null;
    }
  })();

  return inFlightRefresh;
}

/**
 * Restores a session on page load.
 *
 * The access token lives in memory, so a reload always starts with none. The
 * refresh cookie survives, so this call is what turns "no token" back into a
 * signed-in session — profile included — without prompting for a password.
 */
export async function restoreSession(): Promise<SessionResult | null> {
  return refreshAccessToken();
}

/** Revokes the current refresh token server-side, then clears local state. */
export async function endSession(): Promise<void> {
  try {
    await fetch(`${API_BASE_URL}/auth/logout`, {
      ...AUTH_FETCH_INIT,
      method: 'POST',
      body: JSON.stringify({}),
    });
  } catch {
    // If the server cannot be reached the local token still has to go, or the
    // user stays signed in on a shared machine after clicking "log out".
  } finally {
    clearToken();
  }
}
