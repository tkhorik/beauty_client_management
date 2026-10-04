export type LanguagePreference = 'system' | 'en' | 'ru';
export type SupportedLocale = Exclude<LanguagePreference, 'system'>;

const KEY = 'beauty:locale-preference';
export const LOCALE_CHANGED_EVENT = 'beauty:locale-changed';
let activePreference: LanguagePreference | null = null;

/** Effective UI choice, independent of the guest preference stored on disk. */
export function setActiveLanguagePreference(preference: LanguagePreference): void {
  activePreference = preference;
}

export function isLanguagePreference(value: unknown): value is LanguagePreference {
  return value === 'system' || value === 'en' || value === 'ru';
}

export function getStoredLanguagePreference(): LanguagePreference {
  try {
    const stored = typeof localStorage === 'undefined' ? null : localStorage.getItem(KEY);
    return isLanguagePreference(stored) ? stored : 'system';
  } catch { return 'system'; }
}

export function saveLanguagePreference(preference: LanguagePreference): void {
  try { localStorage.setItem(KEY, preference); } catch { /* UI preference remains active in memory. */ }
}

/** Browser language lists can contain regional forms such as ru-RU. */
export function resolveSystemLocale(languages: readonly string[] = typeof navigator === 'undefined' ? [] : navigator.languages): SupportedLocale {
  // `navigator.languages` is ordered by user preference.  Russian must not
  // win merely because it appears after a preferred English locale.
  const firstSupported = languages.map(language => language.toLowerCase().split('-')[0]).find(language => language === 'en' || language === 'ru');
  return firstSupported === 'ru' ? 'ru' : 'en';
}

export function resolveLocale(preference: LanguagePreference): SupportedLocale {
  return preference === 'system' ? resolveSystemLocale() : preference;
}

export function effectiveAcceptLanguage(preference = activePreference ?? getStoredLanguagePreference()): string {
  return preference === 'system' ? (typeof navigator === 'undefined' ? 'en' : navigator.languages.join(',') || navigator.language || 'en') : preference;
}
