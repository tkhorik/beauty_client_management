import { createContext } from 'react';
import type { LanguagePreference, SupportedLocale } from './store';

export type LocaleContextValue = {
  preference: LanguagePreference;
  locale: SupportedLocale;
  setPreference: (preference: LanguagePreference) => void;
  applyAccountPreference: (preference: LanguagePreference) => void;
  restoreGuestPreference: () => void;
  formatDate: (value: Date | string, options?: Intl.DateTimeFormatOptions) => string;
};

export const LocaleContext = createContext<LocaleContextValue | null>(null);
