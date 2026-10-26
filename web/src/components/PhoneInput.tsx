import { useAppTranslation } from '../i18n/useLocale';
import React, { useLayoutEffect, useRef } from 'react';
import { formatPhone, maskedLength, phoneDigits } from './phone';

interface PhoneInputProps {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  /** Already translated; shown under the field and announced to screen readers. */
  error?: string;
}

const countDigits = (s: string) => s.replace(/\D/g, '').length;

/**
 * Required phone field that masks keystrokes as `+1 (XXX) XXX-XXXX` or
 * `+7 (XXX) XXX-XX-XX`. Validation is the parent's job (`phoneIssue`), so the
 * form decides when an empty or partial number becomes an error.
 *
 * A value that does not fit the mask (a number saved before validation
 * existed) is shown as-is until the user edits it.
 */
export const PhoneInput: React.FC<PhoneInputProps> = ({ id, label, value, onChange, error }) => {
  const { t } = useAppTranslation();
  const inputRef = useRef<HTMLInputElement>(null);
  // Digits that should sit before the caret once the masked value renders.
  const pendingCaret = useRef<number | null>(null);

  useLayoutEffect(() => {
    const input = inputRef.current;
    const digitsBefore = pendingCaret.current;
    if (!input || digitsBefore === null || document.activeElement !== input) return;
    pendingCaret.current = null;
    let pos = 0;
    for (let seen = 0; pos < value.length && seen < digitsBefore; pos++) {
      if (/\d/.test(value[pos])) seen++;
    }
    input.setSelectionRange(pos, pos);
  }, [value]);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    let raw = e.target.value;
    let caret = e.target.selectionStart ?? raw.length;
    // Backspace that removed only a separator would be undone by the mask;
    // delete the digit in front of it instead.
    if ((e.nativeEvent as InputEvent).inputType === 'deleteContentBackward' && countDigits(raw) === countDigits(value)) {
      const before = raw.slice(0, caret).replace(/\d(\D*)$/, '$1');
      raw = before + raw.slice(caret);
      caret = before.length;
    }
    pendingCaret.current = Math.min(phoneDigits(raw.slice(0, caret)).length, countDigits(formatPhone(raw)));
    onChange(formatPhone(raw));
  };

  const errorId = `${id}-error`;
  return (
    <div>
      <label htmlFor={id} style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>{label}</label>
      <input
        ref={inputRef}
        id={id}
        type="tel"
        inputMode="tel"
        autoComplete="tel"
        className="input-field"
        placeholder={t('phone.placeholder')}
        aria-required="true"
        aria-invalid={!!error}
        aria-describedby={error ? errorId : undefined}
        maxLength={Math.max(maskedLength(value), value.length)}
        value={value}
        onChange={handleChange}
      />
      {error && <div id={errorId} role="alert" style={{ color: 'var(--danger)', fontSize: '12px', marginTop: '6px' }}>{error}</div>}
    </div>
  );
};
