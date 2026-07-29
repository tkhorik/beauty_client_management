import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { setToken, clearToken, clearLegacyToken } from './tokenStore';
import { endSession, restoreSession } from './session';

interface AuthContextValue {
  token: string | null;
  /**
   * True until the initial refresh attempt settles. The app must wait for this
   * rather than assume a null token means "signed out" — on a reload there is
   * always a moment where the session is real but the access token has not
   * arrived yet, and rendering the login page during it would flash a login
   * form at an already-authenticated user.
   */
  initialising: boolean;
  login: (token: string) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(null);
  const [initialising, setInitialising] = useState(true);

  useEffect(() => {
    let cancelled = false;

    // The access token is held in memory only, so a page load starts with
    // none. The httpOnly refresh cookie is what survives, and exchanging it
    // here is what keeps a reload from logging the user out.
    clearLegacyToken();
    restoreSession()
      .then(restored => {
        if (!cancelled) setTokenState(restored);
      })
      .finally(() => {
        if (!cancelled) setInitialising(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  function login(t: string) {
    setToken(t);
    setTokenState(t);
  }

  function logout() {
    // Clear local state immediately so the UI responds at once, and revoke
    // server-side in the background. Waiting on the network would leave the
    // user staring at an unchanged screen, and an offline logout must still
    // work locally.
    clearToken();
    setTokenState(null);
    void endSession();
  }

  return (
    <AuthContext.Provider value={{ token, initialising, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
