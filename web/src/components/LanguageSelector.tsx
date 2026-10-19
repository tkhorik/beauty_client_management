import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { Check, ChevronDown, Globe } from 'lucide-react';
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
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const itemRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const menuId = useId();

  useEffect(() => {
    if (!open) return;
    itemRefs.current[Math.max(0, OPTIONS.indexOf(preference))]?.focus();
    const onPointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('pointerdown', onPointerDown);
    return () => document.removeEventListener('pointerdown', onPointerDown);
  }, [open, preference]);

  const close = (refocus: boolean) => {
    setOpen(false);
    if (refocus) triggerRef.current?.focus();
  };
  const choose = (next: LanguagePreference) => {
    onChange(next);
    close(true);
  };
  const onMenuKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const items = itemRefs.current.filter((item): item is HTMLButtonElement => item !== null);
    const index = items.indexOf(document.activeElement as HTMLButtonElement);
    const move = (to: number) => { event.preventDefault(); items[(to + items.length) % items.length]?.focus(); };
    if (event.key === 'ArrowDown') move(index + 1);
    else if (event.key === 'ArrowUp') move(index - 1);
    else if (event.key === 'Home') move(0);
    else if (event.key === 'End') move(items.length - 1);
    else if (event.key === 'Escape') { event.preventDefault(); close(true); }
    else if (event.key === 'Tab') close(false);
  };

  const label = (option: LanguagePreference) => t(`language.${option}`);

  return (
    <div className="lang-menu" ref={rootRef}>
      <button
        ref={triggerRef}
        type="button"
        className="lang-menu__trigger"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label={`${t('language.label')}: ${label(preference)}`}
        onClick={() => setOpen(value => !value)}
        onKeyDown={event => { if (event.key === 'ArrowDown' && !open) { event.preventDefault(); setOpen(true); } }}
      >
        <Globe size={15} aria-hidden="true" />
        <span className="lang-menu__code">{locale.toUpperCase()}</span>
        <ChevronDown size={14} aria-hidden="true" className="lang-menu__chevron" />
      </button>
      {open && (
        <div id={menuId} role="menu" aria-label={t('language.label')} className="lang-menu__list" onKeyDown={onMenuKeyDown}>
          {OPTIONS.map((option, index) => {
            const checked = option === preference;
            return (
              <button
                key={option}
                ref={element => { itemRefs.current[index] = element; }}
                type="button"
                role="menuitemradio"
                aria-checked={checked}
                tabIndex={-1}
                className="lang-menu__item"
                onClick={() => choose(option)}
              >
                <span className="lang-menu__text">
                  <span>{label(option)}</span>
                  {option === 'system' && <span className="lang-menu__hint">{label(resolveSystemLocale())}</span>}
                </span>
                {checked ? <Check size={15} aria-hidden="true" className="lang-menu__check" /> : <span className="lang-menu__check" aria-hidden="true" />}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
