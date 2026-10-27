import { useAppTranslation } from '../i18n/useLocale';
import React, { useId, useRef, useState } from 'react';
import { useDismiss } from './useDismiss';
import { useDraftGuard } from './useDraftGuard';
import type { Client } from '../types';
import { api, ApiError, writeErrorMessage } from '../services/api';
import { translatedFieldErrors } from '../i18n/errors';
import { X, Edit2 } from 'lucide-react';
import { AttributeEditor } from './AttributeEditor';
import { duplicateAttributeKey, toAttributeRecord, toAttributeRows, type AttributeRow } from './attributes';
import { PhoneInput } from './PhoneInput';
import { phoneIssue } from './phone';
import './ClientForms.css';

interface EditClientModalProps {
  client: Client;
  onClose: () => void;
  onSuccess: () => void;
}

export const EditClientModal: React.FC<EditClientModalProps> = ({ client, onClose, onSuccess }) => {
  const { t } = useAppTranslation();
  const id = useId();
  const [name, setName] = useState(client.name);
  const [phone, setPhone] = useState(client.phone);
  const [phoneError, setPhoneError] = useState('');
  const [email, setEmail] = useState(client.email ?? '');
  const [tagInput, setTagInput] = useState('');
  const [tags, setTags] = useState<string[]>([...client.tags]);

  const [customFields, setCustomFields] = useState<AttributeRow[]>(() => toAttributeRows(client.customFields));
  const [isSubmitting, setIsSubmitting] = useState(false);
  const submitting = useRef(false);
  const draft = JSON.stringify({ name, phone, email, tags, customFields, tagInput });
  const [baseline] = useState(draft);
  const guardedClose = useDraftGuard(draft !== baseline, isSubmitting, onClose, t('webUx.discardDraft'));
  const requestClose = () => { if (!submitting.current) guardedClose(); };
  const dismiss = useDismiss(requestClose);
  const [error, setError] = useState('');

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

  const handlePhoneChange = (value: string) => {
    setPhone(value);
    // Once an error is showing, let it clear (or update) as the user fixes it.
    if (phoneError) {
      const issue = value === client.phone ? null : phoneIssue(value);
      setPhoneError(issue ? t(`apiErrors.${issue}`) : '');
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting.current) return;
    setError('');
    if (!name.trim()) {
      setError(t('editClientModal.pleaseFillInClientNameAndPhoneNumber'));
      return;
    }
    // An unchanged number is not re-checked, matching the server: clients
    // saved before validation existed must stay editable.
    const issue = phone === client.phone ? null : phoneIssue(phone);
    if (issue) {
      setPhoneError(t(`apiErrors.${issue}`));
      return;
    }
    const duplicate = duplicateAttributeKey(customFields);
    if (duplicate) {
      setError(t('attributes.duplicateKey', { key: duplicate }));
      return;
    }
    setError('');

    const fieldsMap = toAttributeRecord(customFields);

    submitting.current = true;
    setIsSubmitting(true);
    try {
      await api.updateClient(client.id, {
        name,
        phone,
        email: email || undefined,
        tags: [...new Set([...tags, tagInput.trim()].filter(Boolean))],
        customFields: fieldsMap,
      });
      onSuccess();
      onClose();
    } catch (err) {
      const serverPhoneError = err instanceof ApiError ? translatedFieldErrors(err.body).phone : undefined;
      if (serverPhoneError) setPhoneError(serverPhoneError);
      else setError(writeErrorMessage(err,t('editClientModal.failedToSaveChangesPleaseTryAgain')));
    } finally {
      submitting.current = false;
      setIsSubmitting(false);
    }
  };

  return (
    <div className="dialog-overlay" {...dismiss}>
      <div role="dialog" aria-modal="true" aria-labelledby={`${id}-title`} tabIndex={-1} className="dialog-panel client-dialog client-dialog--form">
        {/* Header */}
        <div className="dialog-header">
          <h2 id={`${id}-title`} className="dialog-title">
            <Edit2 size={20} /> {t('editClientModal.editClientProfile')}
          </h2>
          <button type="button" aria-label={t('common.close')} onClick={requestClose} disabled={isSubmitting} className="close-button">
            <X size={22} />
          </button>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} className="dialog-body dialog-form" aria-busy={isSubmitting}>
          <fieldset disabled={isSubmitting} className="dialog-form-content">

          <div className="form-grid">
            <div className="form-field">
              <label htmlFor={`${id}-name`}>{t('editClientModal.fullName')}</label>
              <input
                id={`${id}-name`}
                type="text"
                required
                className="input-field"
                placeholder={t('editClientModal.eGVictoriaSterling')}
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </div>

            <PhoneInput id={`${id}-phone`} label={t('editClientModal.phoneNumber')} value={phone} onChange={handlePhoneChange} error={phoneError} />
          </div>

          <div className="form-field">
            <label htmlFor={`${id}-email`}>{t('editClientModal.emailAddressOptional')}</label>
            <input
              id={`${id}-email`}
              type="email"
              className="input-field"
              placeholder="client@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>

          {/* Client Tags */}
          <div className="form-section">
            <label className="form-section__title" htmlFor={`${id}-tag`}>{t('editClientModal.primaryClientTags')}</label>
            <div className="tags-input">
              <input
                id={`${id}-tag`}
                type="text"
                className="input-field"
                placeholder={t('editClientModal.addTagEGSensitiveSkinLashExtensions')}
                value={tagInput}
                onChange={(e) => setTagInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); handleAddTag(); } }}
              />
              <button type="button" className="btn-secondary" onClick={handleAddTag}>{t('editClientModal.add')}</button>
            </div>
            <div className="tag-list">
              {tags.map(tag => (
                <span key={tag} className="tag-badge" style={{ padding: '4px 10px' }}>
                  {tag}
                  <button type="button" aria-label={t('common.remove')} style={{ background: 'none', border: 'none', color: 'inherit', padding: 0, cursor: 'pointer' }} onClick={() => handleRemoveTag(tag)}><X size={12} /></button>
                </span>
              ))}
            </div>
          </div>

          {/* Dynamic Custom Fields */}
          <details className="optional-details">
            <summary>{t('webUx.optionalDetails')}</summary>
          <div className="form-section">
            <p className="form-section__title">{t('editClientModal.customDynamicClientAttributes')}</p>
            <AttributeEditor rows={customFields} onChange={setCustomFields} addLabel={t('editClientModal.addField')} />
          </div>

          </details>
          </fieldset>

          <div className="dialog-footer">
            {error && <div className="form-alert" role="alert">{error}</div>}
            <button type="button" className="btn-secondary" onClick={requestClose} disabled={isSubmitting}>{t('editClientModal.cancel')}</button>
            <button type="submit" className="btn-rose" disabled={isSubmitting}>
              {isSubmitting ?t('editClientModal.saving') :t('editClientModal.saveChanges')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
