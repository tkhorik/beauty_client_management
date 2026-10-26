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


export function OrgProvider({ children }: { children: ReactNode }) {
  const { token } = useAuth();
  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [currentId, setCurrentId] = useState<string | null>(getActiveOrgId());
  const [loading, setLoading] = useState(Boolean(token));
  const [offline, setOffline] = useState(false);

  // Signing in or out changes what there is to load. Reset during render, so
  // the gate never paints the previous account's list, or an empty one, before
  // the effect below has started the request.
  const [loadedToken, setLoadedToken] = useState(token);
  if (loadedToken !== token) {
    setLoadedToken(token);
    setLoading(Boolean(token));
    if (!token) setOrganizations([]);
  }

  // Fetching and showing are split so the effect below can apply a result
  // from its own callbacks, and drop it if the account changed meanwhile.
  const showOrganizations = useCallback((list: Organization[]) => {
    setOrganizations(list);
    setOffline(false);

    const active = list.filter(o => o.status === 'ACTIVE');
    const stored = getActiveOrgId();

    // Re-validate the stored choice against what the server just said. A user
    // removed from an organization since their last visit still has its id in
    // localStorage, and silently keeping it selected would leave the app
    // firing requests that all come back 403 with no explanation.
    const stillValid = stored && active.some(o => o.id === stored);
    const next = stillValid ? stored : (active[0]?.id ?? null);

    setActiveOrgId(next);
    setCurrentId(next);
  }, []);

  const showLoadFailure = useCallback((err: unknown) => {
    if (err instanceof ApiError) {
      // A real answer from a reachable server: the user genuinely has no
      // organizations, or is not allowed to ask. Show the onboarding screen,
      // not the demo.
      setOrganizations([]);
      clearActiveOrgId();
      setCurrentId(null);
      setOffline(false);
    } else {
      // Network failure. Mirror the mock-data fallback in `api.ts` so the
      // offline demo keeps working — but label it, so nobody mistakes it for
      // their real data.
      setOrganizations([DEMO_ORG]);
      setActiveOrgId(DEMO_ORG.id);
      setCurrentId(DEMO_ORG.id);
      setOffline(true);
    }
  }, []);

  const load = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      showOrganizations(await api.getOrganizations());
    } catch (err) {
      showLoadFailure(err);
    } finally {
      setLoading(false);
    }
  }, [token, showOrganizations, showLoadFailure]);

  // `loading` was already raised by the token reset above.
  useEffect(() => {
    if (!token) return;
    let active = true;
    void api.getOrganizations().then(
      list => { if (active) showOrganizations(list); },
      err => { if (active) showLoadFailure(err); },
    ).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [token, showOrganizations, showLoadFailure]);

  // Removed or revoked while this tab was open: the next scoped request comes
  // back NOT_A_MEMBER. Re-reading the list drops that organization (see the
  // re-validation in `load`). Several requests usually fail together, so the
  // reloads collapse into one.
  const reloading = useRef(false);
  useEffect(() => {
    const handler = () => {
      if (reloading.current) return;
      reloading.current = true;
      void load().finally(() => { reloading.current = false; });
    };
    window.addEventListener(MEMBERSHIP_LOST_EVENT, handler);
    return () => window.removeEventListener(MEMBERSHIP_LOST_EVENT, handler);
  }, [load]);

  const select = useCallback((orgId: string) => {
    // Written to the module-level store first: `api.ts` reads it synchronously
    // on every request, so the switch applies to the very next call rather than
    // waiting for React to re-render.
    setActiveOrgId(orgId);
    setCurrentId(orgId);
  }, []);

  const activeOrganizations = organizations.filter(o => o.status === 'ACTIVE');
  const current = activeOrganizations.find(o => o.id === currentId) ?? null;

  return (
    <OrgContext.Provider
      value={{ organizations, activeOrganizations, current, loading, offline, select, refresh: load }}
    >
      {children}
    </OrgContext.Provider>
  );
}
