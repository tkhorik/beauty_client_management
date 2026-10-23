import { useId } from 'react';
import { SunMoon } from 'lucide-react';
import { useAppTranslation } from '../i18n/LocaleProvider';
import { useTheme, type ThemePreference } from '../theme/ThemeProvider';

export function ThemeSelector({ compact = false }: { compact?: boolean }) {
  const { preference, setPreference } = useTheme();
  const { t } = useAppTranslation();
  const id = useId();
  return <div className={compact ? 'theme-selector theme-selector--compact' : 'theme-selector'}>
    <label htmlFor={id} className={compact ? 'sr-only' : 'form-label'}>{t('appearance.label')}</label>
    {compact && <SunMoon size={17} aria-hidden="true" />}
    <select id={id} className="input-field" aria-describedby={compact ? undefined : `${id}-hint`} value={preference}
      onChange={event => setPreference(event.target.value as ThemePreference)}>
      {(['system', 'light', 'dark'] as const).map(value => <option key={value} value={value}>{t(`appearance.${value}`)}</option>)}
    </select>
    {!compact && <p id={`${id}-hint`} className="theme-selector__hint">{t('appearance.description')}</p>}
  </div>;
}
