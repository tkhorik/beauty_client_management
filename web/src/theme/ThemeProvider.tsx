import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useState, type ReactNode } from 'react';

export type ThemePreference = 'system' | 'light' | 'dark';
export const THEME_STORAGE_KEY = 'beauty:theme-preference';
export const THEME_QUERY = '(prefers-color-scheme: dark)';
export function parseThemePreference(value: unknown): ThemePreference {
  return value === 'light' || value === 'dark' ? value : 'system';
}
export function readThemePreference(): ThemePreference {
  try { return parseThemePreference(localStorage.getItem(THEME_STORAGE_KEY)); }
  catch { return 'system'; }
}
function systemIsDark() { return window.matchMedia?.(THEME_QUERY).matches ?? false; }

const ThemeContext = createContext({
  preference: 'system' as ThemePreference, resolved: 'light' as 'light' | 'dark', setPreference: (_next: ThemePreference) => {},
});

/** Device preference, deliberately independent of authentication and account language. */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [preference, setPreferenceState] = useState(readThemePreference);
  const [systemDark, setSystemDark] = useState(systemIsDark);
  const resolved: 'light' | 'dark' = preference === 'system' ? (systemDark ? 'dark' : 'light') : preference;
  const setPreference = useCallback((next: ThemePreference) => {
    const valid = parseThemePreference(next);
    try { localStorage.setItem(THEME_STORAGE_KEY, valid); } catch { /* In-memory choice still works. */ }
    setPreferenceState(valid);
  }, []);

  useLayoutEffect(() => {
    document.documentElement.dataset.theme = resolved;
    document.documentElement.style.colorScheme = resolved;
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', resolved === 'dark' ? '#18211d' : '#f8f7f2');
  }, [resolved]);
  useEffect(() => {
    if (preference !== 'system' || !window.matchMedia) return;
    const query = window.matchMedia(THEME_QUERY);
    const update = () => setSystemDark(query.matches);
    update();
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, [preference]);
  useEffect(() => {
    const sync = (event: StorageEvent) => {
      if (event.storageArea && event.storageArea !== window.localStorage) return;
      if (event.key === THEME_STORAGE_KEY || event.key === null) setPreferenceState(parseThemePreference(event.newValue));
    };
    window.addEventListener('storage', sync);
    return () => window.removeEventListener('storage', sync);
  }, []);
  const value = useMemo(() => ({ preference, resolved, setPreference }), [preference, resolved, setPreference]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}
export function useTheme() { return useContext(ThemeContext); }
