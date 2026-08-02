/**
 * Holds the organization the UI is currently working in.
 *
 * Unlike the access token this *is* persisted to `localStorage`, and the
 * difference is deliberate. An organization id is not a credential: knowing one
 * grants nothing, because the backend re-checks membership against the database
 * on every single request. What it buys is that a reload, or a second tab,
 * lands back in the salon the user was looking at instead of dumping them on a
 * picker. The security question — "may this person see this organization?" — is
 * answered on the server, never here.
 *
 * Kept as a module variable *and* in storage, with `api.ts` reading the module
 * variable synchronously on every request, so a switch takes effect on the very
 * next call without waiting for React state to propagate.
 */

const STORAGE_KEY = 'beauty_active_org';

let activeOrgId: string | null = readStored();

function readStored(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    // Storage throws in some private-browsing modes. The app still works; the
    // user just re-picks their organization after a reload.
    return null;
  }
}

export function getActiveOrgId(): string | null {
  return activeOrgId;
}

export function setActiveOrgId(id: string | null): void {
  activeOrgId = id;
  try {
    if (id) localStorage.setItem(STORAGE_KEY, id);
    else localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Non-fatal — see readStored.
  }
}

export function clearActiveOrgId(): void {
  setActiveOrgId(null);
}
