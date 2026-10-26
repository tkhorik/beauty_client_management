import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { setToken, clearToken, clearLegacyToken } from './tokenStore';
import { endSession, restoreSession } from './session';
import type { UserProfile } from '../types';
import { AuthContext } from './AuthContextValue';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(null);
  const [user, setUserState] = useState<UserProfile | null>(null);
  const [initialising, setInitialising] = useState(true);
  const [sessionGeneration, setSessionGeneration] = useState(0);

  useEffect(() => {
    let cancelled = false;

    // The access token is held in memory only, so a page load starts with
    // none. The httpOnly refresh cookie is what survives, and exchanging it
    // here is what keeps a reload from logging the user out.
    clearLegacyToken();
    restoreSession()
      .then(restored => {
        if (!cancelled) {
          setTokenState(restored?.token ?? null);
          setUserState(restored?.user ?? null);
          setSessionGeneration(value => value + 1);
        }
      })
      .finally(() => {
        if (!cancelled) setInitialising(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  function login(t: string, u: UserProfile) {
    setToken(t);
    setTokenState(t);
    setUserState(u);
    setSessionGeneration(value => value + 1);
  }

  function logout() {
    // Clear local state immediately so the UI responds at once, and revoke
    // server-side in the background. Waiting on the network would leave the
    // user staring at an unchanged screen, and an offline logout must still
    // work locally.
    clearToken();
    setTokenState(null);
    setUserState(null);
    setSessionGeneration(value => value + 1);
    void endSession();
  }

  const updateUser = useCallback((u: UserProfile) => {
    setUserState(current => {
      // A delayed profile read must not replace another account or regress a
      // language write that completed after that read was issued.
      if (!current || current.id !== u.id) return current;
      if ((current.languageRevision ?? 0) > (u.languageRevision ?? 0)) {
        return { ...u, languagePreference: current.languagePreference, languageRevision: current.languageRevision };
      }
      return u;
    });
  }, []);

  return (
    <AuthContext.Provider value={{ token, user, initialising, sessionGeneration, login, logout, updateUser }}>
      {children}
    </AuthContext.Provider>
  );
}
