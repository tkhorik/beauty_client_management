import { createContext } from 'react';
import type { Organization } from '../types';

export interface OrgContextValue {
  organizations: Organization[];
  activeOrganizations: Organization[];
  current: Organization | null;
  loading: boolean;
  /** The organization list could not be refreshed from the real backend. */
  connectionError?: boolean;
  offline: boolean;
  select: (orgId: string) => void;
  refresh: () => Promise<void>;
}

export const OrgContext = createContext<OrgContextValue | null>(null);
