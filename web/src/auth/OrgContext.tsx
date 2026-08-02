import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import type { Organization } from '../types';
import { api, ApiError } from '../services/api';
import { getActiveOrgId, setActiveOrgId, clearActiveOrgId } from './orgStore';
import { useAuth } from './AuthContext';

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

interface OrgContextValue {
  /** Every organization the user belongs to or has asked to belong to. */
  organizations: Organization[];
  /** Only the ones that actually grant access. */
  activeOrganizations: Organization[];
  /** The one the app is currently working in, or null if none is chosen. */
  current: Organization | null;
  loading: boolean;
  /** True when the organization list came from the offline fallback rather than the API. */
  offline: boolean;
  /** Switches organization. Takes effect on the next API call. */
  select: (orgId: string) => void;
  /** Re-reads the list — call after creating, joining, or being approved. */
  refresh: () => Promise<void>;
}

const OrgContext = createContext<OrgContextValue | null>(null);

export function OrgProvider({ children }: { children: ReactNode }) {
  const { token } = useAuth();
  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [currentId, setCurrentId] = useState<string | null>(getActiveOrgId());
  const [loading, setLoading] = useState(true);
  const [offline, setOffline] = useState(false);

  const load = useCallback(async () => {
    if (!token) {
      setOrganizations([]);
      setLoading(false);
      return;
    }

    setLoading(true);
    try {
      const list = await api.getOrganizations();
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
    } catch (err) {
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
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    void load();
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

export function useOrg(): OrgContextValue {
  const ctx = useContext(OrgContext);
  if (!ctx) throw new Error('useOrg must be used inside OrgProvider');
  return ctx;
}
