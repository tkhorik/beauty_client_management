import { useAppTranslation } from '../i18n/LocaleProvider';
import React, { useId, useRef, useState } from 'react';
import { useDismiss } from './useDismiss';
import { useDraftGuard } from './useDraftGuard';
import { api, ApiError, writeErrorMessage } from '../services/api';
import { translatedFieldErrors } from '../i18n/errors';
import { X, UserPlus } from 'lucide-react';
import { AttributeEditor } from './AttributeEditor';
import { duplicateAttributeKey, toAttributeRecord, type AttributeRow } from './attributes';
import { PhoneInput } from './PhoneInput';
import { phoneIssue } from './phone';
import './ClientForms.css';

interface NewClientModalProps {
  onClose: () => void;
  onSuccess: () => void;
}

/** Pragmatic shape check: something@domain.tld, no whitespace. The server does not validate client emails. */
export const EMAIL_PATTERN = /^[^\s@]+@[^\s@.]+(\.[^\s@.]+)*\.[^\s@.]{2,}$/;

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
  const submitting = useRef(false);
  const draft = JSON.stringify({ name, phone, email, tags, customFields, tagInput });
  const baseline = useRef(draft);
  const guardedClose = useDraftGuard(draft !== baseline.current, isSubmitting, onClose, t('webUx.discardDraft'));
  const requestClose = () => { if (!submitting.current) guardedClose(); };
  const dismiss = useDismiss(requestClose);
  const [error, setError] = useState('');


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
    if (submitting.current) return;
    setError('');
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
      setError(t('attributes.duplicateKey', { key: duplicate }));
      return;
    }

    const fieldsMap = toAttributeRecord(customFields);

    submitting.current = true;
    setIsSubmitting(true);
    try {
      await api.createClient({
        name: name.trim(),
        phone,
        email: email.trim() || undefined,
        tags: [...new Set([...tags, tagInput.trim()].filter(Boolean))],
        customFields: fieldsMap
      });
      onSuccess();
      onClose();
    } catch (err) {
      const serverPhoneError = err instanceof ApiError ? translatedFieldErrors(err.body).phone : undefined;
      if (serverPhoneError) setPhoneError(serverPhoneError);
      else setError(writeErrorMessage(err, t('newClientModal.failedToCreateClientProfile')));
    } finally {
      submitting.current = false;
      setIsSubmitting(false);
    }
  };

  return (
    <div className="dialog-overlay" {...dismiss}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={`${id}-title`}
        tabIndex={-1}
        className="dialog-panel client-dialog client-dialog--form"
      >
        {/* Header */}
        <div className="dialog-header">
          <h2 id={`${id}-title`} className="dialog-title">
            <UserPlus size={20} aria-hidden="true" /> {t('newClientModal.createNewClientProfile')}
          </h2>
          <button type="button" aria-label={t('common.close')} onClick={requestClose} disabled={isSubmitting} className="close-button">
            <X size={22} />
          </button>
        </div>

        {/* Form: noValidate so our inline errors replace the browser's tooltips. */}
        <form noValidate onSubmit={handleSubmit} className="dialog-body dialog-form" aria-busy={isSubmitting}>
          <fieldset disabled={isSubmitting} className="dialog-form-content">

          <div className="form-grid">
            <div className="form-field">
              <label htmlFor={`${id}-name`}>{t('newClientModal.fullNameRequired')}</label>
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
              {nameError && <div id={`${id}-name-error`} role="alert" className="field-error">{nameError}</div>}
            </div>

            <PhoneInput id={`${id}-phone`} label={t('newClientModal.phoneNumberRequired')} value={phone} onChange={handlePhoneChange} error={phoneError} />
          </div>

          <div className="form-field">
            <label htmlFor={`${id}-email`}>{t('newClientModal.emailOptional')}</label>
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
            {emailError && <div id={`${id}-email-error`} role="alert" className="field-error">{emailError}</div>}
          </div>

          {/* Client Tags */}
          <div className="form-section">
            <label className="form-section__title" htmlFor={`${id}-tag`}>{t('newClientModal.primaryTags')}</label>
            <div className="tags-input">
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
              <ul aria-label={t('newClientModal.primaryTags')} className="tag-list">
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
          <details className="optional-details">
            <summary>{t('webUx.optionalDetails')}</summary>
          <fieldset className="form-section" style={{ minWidth: 0 }}>
            <legend>{t('newClientModal.customAttributes')}</legend>
            <AttributeEditor rows={customFields} onChange={setCustomFields} addLabel={t('newClientModal.addField')} />
          </fieldset>

          </details>
          </fieldset>

          <div className="dialog-footer">
            {error && <div className="form-alert" role="alert">{error}</div>}
            <button type="button" className="btn-secondary" onClick={requestClose} disabled={isSubmitting}>{t('newClientModal.cancel')}</button>
            <button type="submit" className="btn-rose" disabled={isSubmitting}>
              {isSubmitting ? t('newClientModal.creating') : t('newClientModal.createClientProfile')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
