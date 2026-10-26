import { useCallback, useContext } from 'react';
import { useTranslation } from 'react-i18next';
import type { TranslationKey } from './locales';
import { LocaleContext, type LocaleContextValue } from './LocaleContext';

export function useLocale(): LocaleContextValue {
  const value = useContext(LocaleContext);
  if (!value) throw new Error('useLocale must be used inside LocaleProvider');
  return value;
}

/** A typed façade keeps feature code from inventing arbitrary catalog keys. */
export function useAppTranslation() {
  const { t: rawT } = useTranslation();
  const t = useCallback((key: TranslationKey, values?: Record<string, string | number>) => rawT(key, values), [rawT]);
  return { t };
}
