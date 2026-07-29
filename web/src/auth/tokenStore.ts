/**
 * Holds the short-lived access token.
 *
 * Deliberately in memory, not `localStorage`. Anything in `localStorage` is
 * readable by any script on the page, so a single XSS bug hands an attacker a
 * usable credential. Keeping it in a module variable does not make XSS
 * harmless — a script running on the page can still call the API directly —
 * but it does mean the token cannot be silently exfiltrated and replayed
 * later from somewhere else.
 *
 * The long-lived refresh token is never visible to this code at all: it lives
 * in an httpOnly cookie that the browser attaches to `/api/auth` requests and
 * JavaScript cannot read. That is what makes it safe to survive a reload while
 * the access token does not.
 */

let accessToken: string | null = null;

/** Legacy key from when the JWT was persisted. Cleared on load — see below. */
const LEGACY_KEY = 'beauty_token';

/**
 * Existing sessions have a never-expiring JWT sitting in `localStorage`. It is
 * useless now (the backend issues short-lived tokens), and leaving it behind
 * would keep a long-lived credential on disk for no reason.
 */
export function clearLegacyToken(): void {
  try {
    localStorage.removeItem(LEGACY_KEY);
  } catch {
    // Storage can throw in private browsing modes. Nothing to recover from.
  }
}

export const getToken = (): string | null => accessToken;

export const setToken = (t: string): void => {
  accessToken = t;
};

export const clearToken = (): void => {
  accessToken = null;
};
