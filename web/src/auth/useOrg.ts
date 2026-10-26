import { useContext } from 'react';
import { OrgContext, type OrgContextValue } from './OrgContextValue';

export function useOrg(): OrgContextValue {
  const ctx = useContext(OrgContext);
  if (!ctx) throw new Error('useOrg must be used inside OrgProvider');
  return ctx;
}
