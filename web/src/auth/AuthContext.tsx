import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { setToken, clearToken, clearLegacyToken } from './tokenStore';
import { endSession, restoreSession } from './session';
import type { UserProfile } from '../types';

interface AuthContextValue {
  token: string | null;
  /** The signed-in user's own profile. Null exactly when `token` is null. */
  user: UserProfile | null;
  /**
   * True until the initial refresh attempt settles. The app must wait for this
   * rather than assume a null token means "signed out" — on a reload there is
   * always a moment where the session is real but the access token has not
   * arrived yet, and rendering the login page during it would flash a login
   * form at an already-authenticated user.
   */
  initialising: boolean;
  /** Changes for every login/logout lifecycle, even for the same account id. */
  sessionGeneration: number;
  /** Called after login, register, or a password change — every endpoint that mints a brand-new session. */
  login: (token: string, user: UserProfile) => void;
  logout: () => void;
  /**
   * Updates the cached profile in place after a profile edit that does *not*
   * mint a new token (`PATCH /api/users/me`). Separate from `login` so a plain
   * name change never has to pretend it rotated the session.
   */
  updateUser: (user: UserProfile) => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

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

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
