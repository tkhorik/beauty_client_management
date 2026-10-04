import { useAppTranslation } from '../i18n/LocaleProvider';
import React, { useState } from 'react';
import type { Client } from '../types';
import { api, writeErrorMessage } from '../services/api';
import { X, Edit2 } from 'lucide-react';
import { AttributeEditor } from './AttributeEditor';
import { duplicateAttributeKey, toAttributeRecord, toAttributeRows, type AttributeRow } from './attributes';

interface EditClientModalProps {
  client: Client;
  onClose: () => void;
  onSuccess: () => void;
}

export const EditClientModal: React.FC<EditClientModalProps> = ({ client, onClose, onSuccess }) => {
  const { t } = useAppTranslation();
  const [name, setName] = useState(client.name);
  const [phone, setPhone] = useState(client.phone);
  const [email, setEmail] = useState(client.email ?? '');
  const [tagInput, setTagInput] = useState('');
  const [tags, setTags] = useState<string[]>([...client.tags]);

  const [customFields, setCustomFields] = useState<AttributeRow[]>(() => toAttributeRows(client.customFields));
  const [isSubmitting, setIsSubmitting] = useState(false);
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

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim() || !phone.trim()) {
      setError(t('editClientModal.pleaseFillInClientNameAndPhoneNumber'));
      return;
    }
    const duplicate = duplicateAttributeKey(customFields);
    if (duplicate) {
      setError(t('attributes.duplicateKey', { key: duplicate }));
      return;
    }
    setError('');

    const fieldsMap = toAttributeRecord(customFields);

    setIsSubmitting(true);
    try {
      await api.updateClient(client.id, {
        name,
        phone,
        email: email || undefined,
        tags,
        customFields: fieldsMap,
      });
      onSuccess();
      onClose();
    } catch (err) {
      setError(writeErrorMessage(err,t('editClientModal.failedToSaveChangesPleaseTryAgain')));
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
      <div className="glass-panel-glow" style={{
        width: '650px',
        maxWidth: '95vw',
        maxHeight: '90vh',
        borderRadius: '20px',
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden'
      }}>
        {/* Header */}
        <div style={{
          padding: '20px 24px',
          borderBottom: '1px solid var(--border-color)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          background: 'rgba(15, 14, 19, 0.9)'
        }}>
          <h2 className="text-gradient" style={{ fontSize: '20px', display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Edit2 size={20} /> {t('editClientModal.editClientProfile')}
          </h2>
          <button aria-label={t('common.close')} onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}>
            <X size={22} />
          </button>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} style={{ padding: '24px', flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '18px' }}>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '14px' }}>
            <div>
              <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>{t('editClientModal.fullName')}</label>
              <input
                type="text"
                required
                className="input-field"
                placeholder={t('editClientModal.eGVictoriaSterling')}
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </div>

            <div>
              <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>{t('editClientModal.phoneNumber')}</label>
              <input
                type="text"
                required
                className="input-field"
                placeholder="+1 (555) 000-0000"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
              />
            </div>
          </div>

          <div>
            <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>{t('editClientModal.emailAddressOptional')}</label>
            <input
              type="email"
              className="input-field"
              placeholder="client@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>

          {/* Client Tags */}
          <div>
            <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>{t('editClientModal.primaryClientTags')}</label>
            <div style={{ display: 'flex', gap: '8px', marginBottom: '10px' }}>
              <input
                type="text"
                className="input-field"
                placeholder={t('editClientModal.addTagEGSensitiveSkinLashExtensions')}
                value={tagInput}
                onChange={(e) => setTagInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); handleAddTag(); } }}
              />
              <button type="button" className="btn-secondary" onClick={handleAddTag}>{t('editClientModal.add')}</button>
            </div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
              {tags.map(tag => (
                <span key={tag} className="tag-badge" style={{ padding: '4px 10px' }}>
                  {tag}
                  <button type="button" aria-label={t('common.remove')} style={{ background: 'none', border: 'none', color: 'inherit', padding: 0, cursor: 'pointer' }} onClick={() => handleRemoveTag(tag)}><X size={12} /></button>
                </span>
              ))}
            </div>
          </div>

          {/* Dynamic Custom Fields */}
          <div>
            <label style={{ fontSize: '12px', color: 'var(--rose-gold-primary)', fontWeight: 600, marginBottom: '8px', display: 'block' }}>{t('editClientModal.customDynamicClientAttributes')}</label>
            <AttributeEditor rows={customFields} onChange={setCustomFields} addLabel={t('editClientModal.addField')} />
          </div>

          {/* Error */}
          {error && (
            <div style={{
              padding: '10px 14px',
              borderRadius: '8px',
              background: 'rgba(220, 50, 80, 0.12)',
              border: '1px solid rgba(220, 50, 80, 0.3)',
              color: '#e87c8a',
              fontSize: '13px',
            }}>
              {error}
            </div>
          )}

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px', marginTop: '12px', paddingTop: '16px', borderTop: '1px solid var(--border-color)' }}>
            <button type="button" className="btn-secondary" onClick={onClose}>{t('editClientModal.cancel')}</button>
            <button type="submit" className="btn-rose" disabled={isSubmitting}>
              {isSubmitting ?t('editClientModal.saving') :t('editClientModal.saveChanges')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
