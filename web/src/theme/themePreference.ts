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

export function systemIsDark() { return window.matchMedia?.(THEME_QUERY).matches ?? false; }
