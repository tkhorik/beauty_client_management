import React, { useState } from 'react';
import type { Client } from '../types';
import { api } from '../services/api';
import { X, Plus, Trash2, Edit2 } from 'lucide-react';

interface EditClientModalProps {
  client: Client;
  onClose: () => void;
  onSuccess: () => void;
}

export const EditClientModal: React.FC<EditClientModalProps> = ({ client, onClose, onSuccess }) => {
  const [name, setName] = useState(client.name);
  const [phone, setPhone] = useState(client.phone);
  const [email, setEmail] = useState(client.email ?? '');
  const [tagInput, setTagInput] = useState('');
  const [tags, setTags] = useState<string[]>([...client.tags]);

  // Convert customFields object to editable array
  const initCustomFields = () =>
    Object.entries(client.customFields ?? {}).map(([key, value]) => ({
      key,
      value: typeof value === 'object' ? JSON.stringify(value) : String(value),
    }));

  const [customFields, setCustomFields] = useState<Array<{ key: string; value: string }>>(initCustomFields);
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
      setError('Please fill in Client Name and Phone Number.');
      return;
    }
    setError('');

    const fieldsMap: Record<string, string> = {};
    customFields.forEach(item => {
      if (item.key.trim()) {
        fieldsMap[item.key.trim()] = item.value.trim();
      }
    });

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
      setError('Failed to save changes. Please try again.');
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
            <Edit2 size={20} /> Edit Client Profile
          </h2>
          <button onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}>
            <X size={22} />
          </button>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} style={{ padding: '24px', flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '18px' }}>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '14px' }}>
            <div>
              <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>Full Name *</label>
              <input
                type="text"
                required
                className="input-field"
                placeholder="e.g. Victoria Sterling"
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </div>

            <div>
              <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>Phone Number *</label>
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
            <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>Email Address (Optional)</label>
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
            <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>Primary Client Tags</label>
            <div style={{ display: 'flex', gap: '8px', marginBottom: '10px' }}>
              <input
                type="text"
                className="input-field"
                placeholder="Add tag (e.g. Sensitive Skin, Lash Extensions)..."
                value={tagInput}
                onChange={(e) => setTagInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); handleAddTag(); } }}
              />
              <button type="button" className="btn-secondary" onClick={handleAddTag}>Add</button>
            </div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
              {tags.map(t => (
                <span key={t} className="tag-badge" style={{ padding: '4px 10px' }}>
                  {t}
                  <X size={12} style={{ cursor: 'pointer', marginLeft: '4px' }} onClick={() => handleRemoveTag(t)} />
                </span>
              ))}
            </div>
          </div>

          {/* Dynamic Custom Fields */}
          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
              <label style={{ fontSize: '12px', color: 'var(--rose-gold-primary)', fontWeight: 600 }}>Custom Dynamic Client Attributes</label>
              <button type="button" className="btn-secondary" style={{ padding: '4px 10px', fontSize: '11px' }} onClick={handleAddField}>
                <Plus size={12} /> Add Field
              </button>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              {customFields.map((field, idx) => (
                <div key={idx} style={{ display: 'flex', gap: '10px' }}>
                  <input
                    type="text"
                    placeholder="Attribute (e.g. Skin Type)"
                    className="input-field"
                    value={field.key}
                    onChange={(e) => handleFieldChange(idx, 'key', e.target.value)}
                  />
                  <input
                    type="text"
                    placeholder="Value (e.g. Combination)"
                    className="input-field"
                    value={field.value}
                    onChange={(e) => handleFieldChange(idx, 'value', e.target.value)}
                  />
                  <button type="button" onClick={() => handleRemoveField(idx)} style={{ background: 'none', border: 'none', color: '#f87171', cursor: 'pointer' }}>
                    <Trash2 size={16} />
                  </button>
                </div>
              ))}
            </div>
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
            <button type="button" className="btn-secondary" onClick={onClose}>Cancel</button>
            <button type="submit" className="btn-rose" disabled={isSubmitting}>
              {isSubmitting ? 'Saving...' : 'Save Changes'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
