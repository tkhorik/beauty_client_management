import { useId } from 'react';
import { Globe } from 'lucide-react';
import { PreferenceMenu } from './PreferenceMenu';
import { useAppTranslation, useLocale } from '../i18n/LocaleProvider';
import { resolveSystemLocale, type LanguagePreference } from '../i18n/store';

const OPTIONS: LanguagePreference[] = ['system', 'en', 'ru'];

export function LanguageSelector({ compact = false, description }: { compact?: boolean; description?: string }) {
  const { preference, setPreference } = useLocale();
  const { t } = useAppTranslation();
  const id = useId();
  if (compact) return <LanguageMenu preference={preference} onChange={setPreference} />;
  const hintId = `${id}-hint`;
  return (
    <div>
      <label htmlFor={id} style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>
        {t('language.label')}
      </label>
      <select
        id={id}
        value={preference}
        onChange={event => setPreference(event.target.value as LanguagePreference)}
        className="input-field"
        aria-describedby={description ? hintId : undefined}
      >
        {OPTIONS.map(option => (
          <option key={option} value={option}>
            {option === 'system' ? `${t('language.system')} (${t(`language.${resolveSystemLocale()}`)})` : t(`language.${option}`)}
          </option>
        ))}
      </select>
      {description && <div id={hintId} style={{ color: 'var(--text-muted)', fontSize: '12px', marginTop: '5px' }}>{description}</div>}
    </div>
  );
}

/** Header-sized globe button with a small menu; language names stay in their own language. */
function LanguageMenu({ preference, onChange }: { preference: LanguagePreference; onChange: (next: LanguagePreference) => void }) {
  const { t } = useAppTranslation();
  const { locale } = useLocale();
  const label = (option: LanguagePreference) => t(`language.${option}`);
  return (
    <PreferenceMenu
      options={OPTIONS}
      value={preference}
      onChange={onChange}
      icon={<Globe size={15} aria-hidden="true" />}
      triggerText={locale.toUpperCase()}
      label={t('language.label')}
      optionLabel={label}
      optionHint={option => option === 'system' ? label(resolveSystemLocale()) : undefined}
    />
  );
}
