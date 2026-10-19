import { useAppTranslation } from '../i18n/LocaleProvider';
import React, { useEffect, useId, useRef, useState } from 'react';
import { api, ApiError, writeErrorMessage } from '../services/api';
import { translatedFieldErrors } from '../i18n/errors';
import { X, UserPlus } from 'lucide-react';
import { AttributeEditor } from './AttributeEditor';
import { duplicateAttributeKey, toAttributeRecord, type AttributeRow } from './attributes';
import { PhoneInput } from './PhoneInput';
import { phoneIssue } from './phone';

interface NewClientModalProps {
  onClose: () => void;
  onSuccess: () => void;
}

/** Pragmatic shape check: something@domain.tld, no whitespace. The server does not validate client emails. */
export const EMAIL_PATTERN = /^[^\s@]+@[^\s@.]+(\.[^\s@.]+)*\.[^\s@.]{2,}$/;

const labelStyle: React.CSSProperties = { fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' };
const errorStyle: React.CSSProperties = { color: '#e87c8a', fontSize: '12px', marginTop: '6px' };

export const NewClientModal: React.FC<NewClientModalProps> = ({ onClose, onSuccess }) => {
  const { t } = useAppTranslation();
  const id = useId();
  const nameRef = useRef<HTMLInputElement>(null);
  const emailRef = useRef<HTMLInputElement>(null);
  const [name, setName] = useState('');
  const [nameError, setNameError] = useState('');
  const [phone, setPhone] = useState('');
  const [phoneError, setPhoneError] = useState('');
  const [email, setEmail] = useState('');
  const [emailError, setEmailError] = useState('');
  const [tagInput, setTagInput] = useState('');
  const [tags, setTags] = useState<string[]>([]);
  const [customFields, setCustomFields] = useState<AttributeRow[]>([]);
  const [isSubmitting, setIsSubmitting] = useState(false);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const nameIssue = (value: string) => (value.trim() ? '' : t('newClientModal.nameRequired'));
  const emailIssue = (value: string) => (!value.trim() || EMAIL_PATTERN.test(value.trim()) ? '' : t('newClientModal.emailInvalid'));

  const handleAddTag = () => {
    if (!tagInput.trim()) return;
    if (!tags.includes(tagInput.trim())) {
      setTags([...tags, tagInput.trim()]);
    }
    setTagInput('');
  };

  const handleRemoveTag = (t: string) => {
    setTags(tags.filter(item => item !== t));
  };

  // Once an error is showing, let it clear (or update) as the user fixes it.
  const handleNameChange = (value: string) => {
    setName(value);
    if (nameError) setNameError(nameIssue(value));
  };

  const handlePhoneChange = (value: string) => {
    setPhone(value);
    if (phoneError) {
      const issue = phoneIssue(value);
      setPhoneError(issue ? t(`apiErrors.${issue}`) : '');
    }
  };

  const handleEmailChange = (value: string) => {
    setEmail(value);
    if (emailError) setEmailError(emailIssue(value));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const nextNameError = nameIssue(name);
    const issue = phoneIssue(phone);
    const nextPhoneError = issue ? t(`apiErrors.${issue}`) : '';
    const nextEmailError = emailIssue(email);
    setNameError(nextNameError);
    setPhoneError(nextPhoneError);
    setEmailError(nextEmailError);
    if (nextNameError || nextPhoneError || nextEmailError) {
      if (nextNameError) nameRef.current?.focus();
      else if (nextPhoneError) document.getElementById(`${id}-phone`)?.focus();
      else emailRef.current?.focus();
      return;
    }
    const duplicate = duplicateAttributeKey(customFields);
    if (duplicate) {
      alert(t('attributes.duplicateKey', { key: duplicate }));
      return;
    }

    const fieldsMap = toAttributeRecord(customFields);

    setIsSubmitting(true);
    try {
      await api.createClient({
        name: name.trim(),
        phone,
        email: email.trim() || undefined,
        tags,
        customFields: fieldsMap
      });
      onSuccess();
      onClose();
    } catch (err) {
      const serverPhoneError = err instanceof ApiError ? translatedFieldErrors(err.body).phone : undefined;
      if (serverPhoneError) setPhoneError(serverPhoneError);
      else alert(writeErrorMessage(err, t('newClientModal.failedToCreateClientProfile')));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div style={{
      position: 'fixed',
      inset: 0,
      background: 'rgba(0,0,0,0.85)',
      backdropFilter: 'blur(10px)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      zIndex: 1000,
      padding: '20px'
    }}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={`${id}-title`}
        className="glass-panel-glow"
        style={{
          width: '650px',
          maxWidth: '95vw',
          maxHeight: '90vh',
          borderRadius: '20px',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden'
        }}
      >
        {/* Header */}
        <div style={{
          padding: '20px 24px',
          borderBottom: '1px solid var(--border-color)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          background: 'rgba(15, 14, 19, 0.9)'
        }}>
          <h2 id={`${id}-title`} className="text-gradient" style={{ fontSize: '20px', display: 'flex', alignItems: 'center', gap: '8px' }}>
            <UserPlus size={20} aria-hidden="true" /> {t('newClientModal.createNewClientProfile')}
          </h2>
          <button type="button" aria-label={t('common.close')} onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}>
            <X size={22} />
          </button>
        </div>

        {/* Form: noValidate so our inline errors replace the browser's tooltips. */}
        <form noValidate onSubmit={handleSubmit} style={{ padding: '24px', flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '18px' }}>

          <div className="new-client-row">
            <div>
              <label htmlFor={`${id}-name`} style={labelStyle}>{t('newClientModal.fullNameRequired')}</label>
              <input
                ref={nameRef}
                id={`${id}-name`}
                type="text"
                autoComplete="name"
                className="input-field"
                placeholder={t('newClientModal.eGVictoriaSterling')}
                aria-required="true"
                aria-invalid={!!nameError}
                aria-describedby={nameError ? `${id}-name-error` : undefined}
                value={name}
                onChange={(e) => handleNameChange(e.target.value)}
              />
              {nameError && <div id={`${id}-name-error`} role="alert" style={errorStyle}>{nameError}</div>}
            </div>

            <PhoneInput id={`${id}-phone`} label={t('newClientModal.phoneNumberRequired')} value={phone} onChange={handlePhoneChange} error={phoneError} />
          </div>

          <div>
            <label htmlFor={`${id}-email`} style={labelStyle}>{t('newClientModal.emailOptional')}</label>
            <input
              ref={emailRef}
              id={`${id}-email`}
              type="email"
              inputMode="email"
              autoComplete="email"
              className="input-field"
              placeholder="client@example.com"
              aria-invalid={!!emailError}
              aria-describedby={emailError ? `${id}-email-error` : undefined}
              value={email}
              onChange={(e) => handleEmailChange(e.target.value)}
              onBlur={() => setEmailError(emailIssue(email))}
            />
            {emailError && <div id={`${id}-email-error`} role="alert" style={errorStyle}>{emailError}</div>}
          </div>

          {/* Client Tags */}
          <div>
            <label htmlFor={`${id}-tag`} style={labelStyle}>{t('newClientModal.primaryTags')}</label>
            <div style={{ display: 'flex', gap: '8px', marginBottom: '10px' }}>
              <input
                id={`${id}-tag`}
                type="text"
                className="input-field"
                placeholder={t('newClientModal.addTagPlaceholder')}
                value={tagInput}
                onChange={(e) => setTagInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); handleAddTag(); } }}
              />
              <button type="button" className="btn-secondary" onClick={handleAddTag}>{t('newClientModal.add')}</button>
            </div>
            {tags.length > 0 && (
              <ul aria-label={t('newClientModal.primaryTags')} style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', listStyle: 'none', margin: 0, padding: 0 }}>
                {tags.map(tag => (
                  <li key={tag} className="tag-badge" style={{ padding: '4px 10px' }}>
                    {tag}
                    <button type="button" aria-label={t('newClientModal.removeTag', { tag })} style={{ background: 'none', border: 'none', color: 'inherit', padding: 0, cursor: 'pointer' }} onClick={() => handleRemoveTag(tag)}><X size={12} /></button>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {/* Dynamic Custom Fields */}
          <fieldset style={{ border: 'none', margin: 0, padding: 0, minWidth: 0 }}>
            <legend style={{ fontSize: '12px', color: 'var(--rose-gold-primary)', fontWeight: 600, marginBottom: '8px', padding: 0 }}>{t('newClientModal.customAttributes')}</legend>
            <AttributeEditor rows={customFields} onChange={setCustomFields} addLabel={t('newClientModal.addField')} />
          </fieldset>

          <div style={{ display: 'flex', justifyContent: 'flex-end', flexWrap: 'wrap', gap: '12px', marginTop: '12px', paddingTop: '16px', borderTop: '1px solid var(--border-color)' }}>
            <button type="button" className="btn-secondary" onClick={onClose}>{t('newClientModal.cancel')}</button>
            <button type="submit" className="btn-rose" disabled={isSubmitting}>
              {isSubmitting ? t('newClientModal.creating') : t('newClientModal.createClientProfile')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
