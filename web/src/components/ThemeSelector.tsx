import { useId } from 'react';
import { SunMoon } from 'lucide-react';
import { useAppTranslation } from '../i18n/useLocale';
import { useTheme } from '../theme/useTheme';
import type { ThemePreference } from '../theme/themePreference';
import { PreferenceMenu } from './PreferenceMenu';

const OPTIONS: ThemePreference[] = ['system', 'light', 'dark'];

export function ThemeSelector({ compact = false }: { compact?: boolean }) {
  const { preference, resolved, setPreference } = useTheme();
  const { t } = useAppTranslation();
  const id = useId();
  const label = (option: ThemePreference) => t(`appearance.${option}`);
  if (compact) return (
    <PreferenceMenu
      className="theme-menu"
      options={OPTIONS}
      value={preference}
      onChange={setPreference}
      icon={<SunMoon size={15} aria-hidden="true" />}
      triggerText={label(preference)}
      label={t('appearance.label')}
      optionLabel={label}
      optionHint={option => option === 'system' ? label(resolved) : undefined}
    />
  );
  return <div className="theme-selector">
    <label htmlFor={id} className="form-label">{t('appearance.label')}</label>
    <select id={id} className="input-field" aria-describedby={`${id}-hint`} value={preference}
      onChange={event => setPreference(event.target.value as ThemePreference)}>
      {OPTIONS.map(value => <option key={value} value={value}>{label(value)}</option>)}
    </select>
    <p id={`${id}-hint`} className="theme-selector__hint">{t('appearance.description')}</p>
  </div>;
}
