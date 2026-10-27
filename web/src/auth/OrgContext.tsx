import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import type { Organization } from '../types';
import { api, ApiError, MEMBERSHIP_LOST_EVENT } from '../services/api';
import { getActiveOrgId, setActiveOrgId, clearActiveOrgId } from './orgStore';
import { useAuth } from './useAuth';
import { OrgContext } from './OrgContextValue';

/**
 * The synthetic organization used when the backend cannot be reached.
 *
 * `services/api.ts` deliberately falls back to `localStorage` mock data when
 * the network fails, which is the app's demo/offline mode. That mode needs
 * *something* in this slot or the UI would sit on an organization picker
 * forever. This is that something, and it is marked so the UI can say plainly
 * that it is offline rather than implying the user has a real salon called
 * "Demo".
 *
 * It grants nothing. Every real authorization decision happens on the server,
 * which is unreachable in exactly the situation where this object exists.
 */
const DEMO_ORG: Organization = {
  id: 'demo-offline',
  name: 'Demo (offline)',
  slug: 'demo-offline',
  role: 'ORG_ADMIN',
  status: 'ACTIVE',
};

// Keep this independently explicit rather than treating every unavailable
// network as a demo. In production a synthetic organization is both confusing
// and unsafe: it can make a real account look like it owns made-up data.
const DEMO_MODE = import.meta.env.VITE_DEMO_MODE === 'true';

function isRetryableOrganizationFailure(error: unknown): boolean {
  return !(error instanceof ApiError)
    || error.status === 408
    || error.status === 429
    || error.status >= 500;
}


export function OrgProvider({ children }: { children: ReactNode }) {
  const { token } = useAuth();
  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [currentId, setCurrentId] = useState<string | null>(getActiveOrgId());
  const [loading, setLoading] = useState(Boolean(token));
  const [offline, setOffline] = useState(false);
  const [connectionError, setConnectionError] = useState(false);

  // Reset before children render: a loading gate may deliberately preserve a
  // current organization during refresh, but never across account changes.
  // Object identity also distinguishes A -> B -> A from the original A session.
  const [account, setAccount] = useState({ token });
  const activeAccount = useRef<typeof account | null>(null);
  if (account.token !== token) {
    setAccount({ token });
    setOrganizations([]);
    setCurrentId(null);
    setLoading(Boolean(token));
    setOffline(false);
    setConnectionError(false);
  }

  // Fetching and showing stay separate so initial effects can cancel their
  // own results without synchronously setting state from an effect.
  const showOrganizations = useCallback((list: Organization[]) => {
    setOrganizations(list);
    setOffline(false);
    setConnectionError(false);

    const active = list.filter(o => o.status === 'ACTIVE');
    const stored = getActiveOrgId();
    // Re-validate the stored selection against the current account's list.
    const stillValid = stored && active.some(o => o.id === stored);
    const next = stillValid ? stored : (active[0]?.id ?? null);

    setActiveOrgId(next);
    setCurrentId(next);
  }, []);

  const showLoadFailure = useCallback((err: unknown) => {
    if (!isRetryableOrganizationFailure(err)) {
      // Definitive refusal must not retain an organization as usable.
      setOrganizations([]);
      clearActiveOrgId();
      setCurrentId(null);
      setOffline(false);
      setConnectionError(false);
    } else if (DEMO_MODE && !(err instanceof ApiError)) {
      // Only the explicit demo build may substitute mock data for a network
      // failure. A real HTTP refusal never becomes a successful demo request.
      setOrganizations([DEMO_ORG]);
      setActiveOrgId(DEMO_ORG.id);
      setCurrentId(DEMO_ORG.id);
      setOffline(true);
      setConnectionError(false);
    } else {
      // Keep a verified organization and its open forms on transient failure.
      // An initial failure still has no current organization and is retryable.
      setOffline(false);
      setConnectionError(true);
    }
  }, []);

  const load = useCallback(async () => {
    if (!account.token || activeAccount.current !== account) return;
    setLoading(true);
    try {
      const list = await api.getOrganizations();
      if (activeAccount.current === account) showOrganizations(list);
    } catch (err) {
      if (activeAccount.current === account) showLoadFailure(err);
    } finally {
      if (activeAccount.current === account) setLoading(false);
    }
  }, [account, showOrganizations, showLoadFailure]);

  // `loading` was already raised by the account reset above. Cancellation
  // covers the initial request; account identity also guards manual refreshes.
  useEffect(() => {
    activeAccount.current = account;
    let active = true;
    if (account.token) {
      void api.getOrganizations().then(
        list => { if (active) showOrganizations(list); },
        err => { if (active) showLoadFailure(err); },
      ).finally(() => { if (active) setLoading(false); });
    }
    return () => {
      active = false;
      activeAccount.current = null;
    };
  }, [account, showOrganizations, showLoadFailure]);

  // Collapse concurrent membership-loss notifications within this account.
  // An old account's unfinished refresh must not block the next account.
  useEffect(() => {
    let reloading = false;
    const handler = () => {
      if (reloading) return;
      reloading = true;
      void load().finally(() => { reloading = false; });
    };
    window.addEventListener(MEMBERSHIP_LOST_EVENT, handler);
    return () => window.removeEventListener(MEMBERSHIP_LOST_EVENT, handler);
  }, [load]);

  const select = useCallback((orgId: string) => {
    if (!account.token || activeAccount.current !== account) return;
    // API calls read this store synchronously, before the next React render.
    setActiveOrgId(orgId);
    setCurrentId(orgId);
  }, [account]);

  const activeOrganizations = organizations.filter(o => o.status === 'ACTIVE');
  const current = activeOrganizations.find(o => o.id === currentId) ?? null;

  return (
    <OrgContext.Provider
      value={{ organizations, activeOrganizations, current, loading, connectionError, offline, select, refresh: load }}
    >
      {children}
    </OrgContext.Provider>
  );
}
