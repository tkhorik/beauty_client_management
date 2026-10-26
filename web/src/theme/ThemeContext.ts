import { createContext } from 'react';
import type { ThemePreference } from './themePreference';

export const ThemeContext = createContext({
  preference: 'system' as ThemePreference, resolved: 'light' as 'light' | 'dark', setPreference: (_next: ThemePreference) => {},
});
