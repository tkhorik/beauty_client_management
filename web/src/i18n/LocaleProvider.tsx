import { pwaEn, pwaRu } from './pwa';
import { webUxEn, webUxRu } from './webUx';
import { visitUxEn, visitUxRu } from './visitUx';
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { en, ru } from './locales';
import { featureEn, featureRu } from './features';
import { apiErrorEn, apiErrorRu } from './apiErrors';
import { runtimeEn, runtimeRu } from './runtimeFeatures';
import { auditEn, auditRu } from './audit';
import { newClientEn, newClientRu } from './newClient';
import { getStoredLanguagePreference, LOCALE_CHANGED_EVENT, resolveLocale, saveLanguagePreference, setActiveLanguagePreference, type LanguagePreference } from './store';
import { LocaleContext } from './LocaleContext';

i18n.use(initReactI18next).init({
  resources: { en: { translation: { ...en, ...featureEn, ...runtimeEn, ...newClientEn, ...auditEn, ...webUxEn, ...visitUxEn, ...pwaEn, apiErrors: apiErrorEn } }, ru: { translation: { ...ru, ...featureRu, ...runtimeRu, ...newClientRu, ...auditRu, ...webUxRu, ...visitUxRu, ...pwaRu, apiErrors: apiErrorRu } } },
  lng: resolveLocale(getStoredLanguagePreference()),
  fallbackLng: 'en',
  initAsync: false,
  interpolation: { escapeValue: false },
});


export function LocaleProvider({ children }: { children: ReactNode }) {
  const [preference, setPreferenceState] = useState<LanguagePreference>(() => getStoredLanguagePreference());
  setActiveLanguagePreference(preference);
  const accountMode = useRef(false);
  const [, setSystemEpoch] = useState(0);
  const locale = resolveLocale(preference);

  const change = useCallback((next: LanguagePreference, notify: boolean) => {
    if (!accountMode.current) saveLanguagePreference(next);
    setActiveLanguagePreference(next);
    setPreferenceState(next);
    if (notify) window.dispatchEvent(new CustomEvent(LOCALE_CHANGED_EVENT, { detail: next }));
  }, []);
  const setPreference = useCallback((next: LanguagePreference) => change(next, true), [change]);
  const applyAccountPreference = useCallback((next: LanguagePreference) => {
    accountMode.current = true;
    change(next, false);
  }, [change]);
  const restoreGuestPreference = useCallback(() => {
    accountMode.current = false;
    change(getStoredLanguagePreference(), false);
  }, [change]);

  useEffect(() => {
    void i18n.changeLanguage(locale);
    document.documentElement.lang = locale;
    document.title = en.common.appName;
  }, [locale]);
  useEffect(() => {
    const onLanguageChange = () => {
      if (preference === 'system') setSystemEpoch(epoch => epoch + 1);
    };
    const onStorage = (event: StorageEvent) => {
    if (!accountMode.current && event.key === 'beauty:locale-preference') {
      const next = getStoredLanguagePreference();
      setActiveLanguagePreference(next);
      setPreferenceState(next);
    }
    };
    window.addEventListener('languagechange', onLanguageChange);
    window.addEventListener('storage', onStorage);
    return () => { window.removeEventListener('languagechange', onLanguageChange); window.removeEventListener('storage', onStorage); };
  }, [preference]);

  const value = useMemo(() => ({
    preference, locale, setPreference, applyAccountPreference, restoreGuestPreference,
    formatDate: (date: Date | string, options?: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(locale, options).format(typeof date === 'string' ? new Date(date) : date),
  }), [preference, locale, setPreference, applyAccountPreference, restoreGuestPreference]);
  return <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>;
}
