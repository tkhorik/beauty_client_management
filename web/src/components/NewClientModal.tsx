import { useAppTranslation } from '../i18n/LocaleProvider';
import React, { useState } from 'react';
import { api, writeErrorMessage } from '../services/api';
import { X, Plus, Trash2, UserPlus } from 'lucide-react';

interface NewClientModalProps {
  onClose: () => void;
  onSuccess: () => void;
}

export const NewClientModal: React.FC<NewClientModalProps> = ({ onClose, onSuccess }) => {
  const { t } = useAppTranslation();
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [email, setEmail] = useState('');
  const [tagInput, setTagInput] = useState('');
  const [tags, setTags] = useState<string[]>([]);
  const [customFields, setCustomFields] = useState<Array<{ key: string; value: string }>>([]);
  const [isSubmitting, setIsSubmitting] = useState(false);

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

  const handleAddField = () => {
    setCustomFields([...customFields, { key: '', value: '' }]);
  };

  const handleRemoveField = (index: number) => {
    setCustomFields(customFields.filter((_, i) => i !== index));
  };

  const handleFieldChange = (index: number, field: 'key' | 'value', val: string) => {
    const updated = [...customFields];
    updated[index][field] = val;
    setCustomFields(updated);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim() || !phone.trim()) {
      alert(t('newClientModal.pleaseFillInClientNameAndPhoneNumber'));
      return;
    }

    const fieldsMap: Record<string, string> = {};
    customFields.forEach(item => {
      if (item.key.trim()) {
        fieldsMap[item.key.trim()] = item.value.trim();
      }
    });

    setIsSubmitting(true);
    try {
      await api.createClient({
        name,
        phone,
        email: email || undefined,
        tags,
        customFields: fieldsMap
      });
      onSuccess();
      onClose();
    } catch (err) {
      alert(writeErrorMessage(err, t('newClientModal.failedToCreateClientProfile')));
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
            <UserPlus size={20} /> {t('newClientModal.createNewClientProfile')}
          </h2>
          <button aria-label={t('common.close')} onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}>
            <X size={22} />
          </button>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} style={{ padding: '24px', flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '18px' }}>
          
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '14px' }}>
            <div>
              <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>{t('newClientModal.fullNameRequired')}</label>
              <input
                type="text"
                required
                className="input-field"
                placeholder={t('newClientModal.eGVictoriaSterling')}
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </div>

            <div>
              <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>{t('newClientModal.phoneNumberRequired')}</label>
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
            <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>{t('newClientModal.emailOptional')}</label>
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
            <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>{t('newClientModal.primaryTags')}</label>
            <div style={{ display: 'flex', gap: '8px', marginBottom: '10px' }}>
              <input
                type="text"
                className="input-field"
                placeholder={t('newClientModal.addTagPlaceholder')}
                value={tagInput}
                onChange={(e) => setTagInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); handleAddTag(); } }}
              />
              <button type="button" className="btn-secondary" onClick={handleAddTag}>{t('newClientModal.add')}</button>
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
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
              <label style={{ fontSize: '12px', color: 'var(--rose-gold-primary)', fontWeight: 600 }}>{t('newClientModal.customAttributes')}</label>
              <button type="button" className="btn-secondary" style={{ padding: '4px 10px', fontSize: '11px' }} onClick={handleAddField}>
                <Plus size={12} /> {t('newClientModal.addField')}
              </button>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              {customFields.map((field, idx) => (
                <div key={idx} style={{ display: 'flex', gap: '10px' }}>
                  <input
                    type="text"
                    placeholder={t('newClientModal.attributePlaceholder')}
                    className="input-field"
                    value={field.key}
                    onChange={(e) => handleFieldChange(idx, 'key', e.target.value)}
                  />
                  <input
                    type="text"
                    placeholder={t('newClientModal.valuePlaceholder')}
                    className="input-field"
                    value={field.value}
                    onChange={(e) => handleFieldChange(idx, 'value', e.target.value)}
                  />
                  <button type="button" aria-label={t('common.remove')} onClick={() => handleRemoveField(idx)} style={{ background: 'none', border: 'none', color: '#f87171', cursor: 'pointer' }}>
                    <Trash2 size={16} />
                  </button>
                </div>
              ))}
            </div>
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px', marginTop: '12px', paddingTop: '16px', borderTop: '1px solid var(--border-color)' }}>
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
