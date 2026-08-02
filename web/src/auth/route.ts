/**
 * The app's entire routing layer.
 *
 * There is no router library here, and that is deliberate. Only three paths
 * need to exist outside the signed-in application — the two that email links
 * land on and the form that requests one — and every other view is already
 * driven by component state in `App.tsx`. Adding `react-router` to serve three
 * static destinations would mean restructuring the app around it for no gain.
 *
 * What this does need to get right is that the reset and verification paths
 * must render *without* a session, since the person following the link is by
 * definition unable to sign in. `main.tsx` therefore dispatches on the route
 * before mounting `AuthProvider`/`OrgProvider`.
 *
 * The server side of this already works: `web/nginx.conf` falls back to
 * `index.html` for unknown paths, so `/reset-password?token=…` loads the SPA
 * rather than 404ing.
 */

import { useEffect, useState } from 'react';

/** Where the backend's reset email points. Must match `AccountMailer.link`. */
export const RESET_PASSWORD_PATH = '/reset-password';

/** Where `/api/auth/verify-email` redirects after redeeming a token. */
export const VERIFY_EMAIL_PATH = '/verify-email';

/** The request-a-link form. Also linked from the password-changed email. */
export const FORGOT_PASSWORD_PATH = '/forgot-password';

export type VerificationStatus = 'success' | 'invalid';

export type PublicRoute =
  | { name: 'app' }
  | { name: 'forgot-password' }
  | { name: 'reset-password'; token: string }
  | { name: 'verify-email'; status: VerificationStatus };

/**
 * Fired by [navigate] so mounted components re-read the location.
 *
 * `popstate` covers the back and forward buttons but is not fired by
 * `pushState` itself, so an in-app link needs this second channel.
 */
const NAVIGATION_EVENT = 'beauty:navigate';

/** Reads the current URL. Pure: no side effects, safe to call during render. */
export function readPublicRoute(): PublicRoute {
  const { pathname, search } = window.location;
  const params = new URLSearchParams(search);

  // Trailing slashes are normalised so `/reset-password/` is not a 404-shaped
  // dead end for anyone whose mail client tidied up the link.
  const path = pathname.replace(/\/+$/, '') || '/';

  switch (path) {
    case FORGOT_PASSWORD_PATH:
      return { name: 'forgot-password' };

    case RESET_PASSWORD_PATH:
      // A missing token is not an error case worth its own route: the page
      // renders the same "this link is not usable" state it would show for a
      // token the server rejects, so the two are indistinguishable to a
      // visitor who typed the path by hand.
      return { name: 'reset-password', token: params.get('token') ?? '' };

    case VERIFY_EMAIL_PATH:
      return {
        name: 'verify-email',
        status: params.get('status') === 'success' ? 'success' : 'invalid',
      };

    default:
      return { name: 'app' };
  }
}

/** Client-side navigation, without a full page load. */
export function navigate(path: string): void {
  if (window.location.pathname === path) return;
  window.history.pushState({}, '', path);
  window.dispatchEvent(new Event(NAVIGATION_EVENT));
}

/**
 * Removes the query string from the address bar without navigating.
 *
 * Called by the reset page once it has the token in memory. A reset token is a
 * credential, and leaving it in the URL leaves it in the address bar, in
 * browser history, in any bookmark the user makes, and in the `Referer` header
 * of every subsequent request the page makes to another origin. Stripping it
 * costs nothing and closes all four.
 *
 * Uses `replaceState` and deliberately does *not* fire [NAVIGATION_EVENT]:
 * re-reading the route here would hand the page an empty token and blank the
 * form the user is in the middle of filling in.
 */
export function stripQueryString(): void {
  window.history.replaceState({}, '', window.location.pathname);
}

/** Subscribes a component to the current route. */
export function usePublicRoute(): PublicRoute {
  const [route, setRoute] = useState<PublicRoute>(readPublicRoute);

  useEffect(() => {
    const reread = () => setRoute(readPublicRoute());
    window.addEventListener('popstate', reread);
    window.addEventListener(NAVIGATION_EVENT, reread);
    return () => {
      window.removeEventListener('popstate', reread);
      window.removeEventListener(NAVIGATION_EVENT, reread);
    };
  }, []);

  return route;
}
