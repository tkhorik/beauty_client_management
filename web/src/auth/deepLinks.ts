/**
 * Query-string deep links that must outlive the screen they arrive on.
 *
 * Captured once, when this module is first evaluated, for the same StrictMode
 * reason `OrganizationOnboarding` captures `?orgToken=` at module level: a
 * component that reads and strips the URL in an effect would see `null` on its
 * surviving mount. Held in memory rather than re-read from `window.location`
 * because the visitor may sign in (or sign up) first, and the link has to
 * survive that.
 *
 * - `?join=<handle>`   — an admin's "join my salon" link. Pre-fills the handle
 *                         on the registration form and the onboarding screen.
 * - `?members=<orgId>` — from the "new access request" email. Opens that
 *                         organization's members screen once signed in.
 */
const params = new URLSearchParams(window.location.search);

let joinSlug: string | null = params.get('join')?.trim().toLowerCase() || null;
let membersOrgId: string | null = params.get('members')?.trim() || null;

/** Whether the visitor arrived with an organization-creation link instead. */
export const arrivedWithCreationLink = params.has('orgToken');

export function pendingJoinSlug(): string | null {
  return joinSlug;
}

/** Forgets the join handle once a request has been filed with it. */
export function clearJoinSlug(): void {
  joinSlug = null;
}

/**
 * The organization whose members screen should open. Read-only so it is safe
 * in a render or a StrictMode-doubled initializer; clear it from the effect
 * that acts on it.
 */
export function membersDeepLink(): string | null {
  return membersOrgId;
}

export function clearMembersDeepLink(): void {
  membersOrgId = null;
}

/**
 * Removes `?join=` / `?members=` from the address bar once captured, so a
 * reload or a shared screenshot does not replay them. Left alone when an
 * `?orgToken=` is also present: that flow strips the URL itself, after
 * reading it.
 */
export function stripDeepLinkParams(): void {
  if (arrivedWithCreationLink) return;
  if (!params.has('join') && !params.has('members')) return;
  window.history.replaceState({}, '', window.location.pathname);
}
