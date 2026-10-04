import { useAppTranslation, useLocale } from '../i18n/LocaleProvider';
import type { LanguagePreference } from '../i18n/store';

export function LanguageSelector({ compact = false }: { compact?: boolean }) {
  const { preference, setPreference } = useLocale();
  const { t } = useAppTranslation();
  return (
    <label style={{ display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--text-muted)', fontSize: compact ? '12px' : '13px' }}>
      {!compact && <span>{t('language.label')}</span>}
      <select aria-label={t('language.label')} value={preference} onChange={event => setPreference(event.target.value as LanguagePreference)} className="input-field" style={{ width: compact ? 'auto' : '100%', padding: compact ? '5px 8px' : undefined }}>
        <option value="system">{t('language.system')}</option>
        <option value="en">{t('language.en')}</option>
        <option value="ru">{t('language.ru')}</option>
      </select>
    </label>
  );
}
