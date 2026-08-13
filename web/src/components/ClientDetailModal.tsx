import React, { useState } from 'react';
import type { Client, Visit, Attachment } from '../types';
import { X, Calendar, Clock, Plus, Trash2, Edit3, Edit2, Camera, FileText, Sliders } from 'lucide-react';
import { api, writeErrorMessage } from '../services/api';
import { EditClientModal } from './EditClientModal';

interface ClientDetailModalProps {
  client: Client;
  visits: Visit[];
  onClose: () => void;
  onRefresh: () => void;
  onOpenNewVisit: (client: Client) => void;
  onOpenPhotoCompare: (attachments: Attachment[]) => void;
}

export const ClientDetailModal: React.FC<ClientDetailModalProps> = ({
  client,
  visits,
  onClose,
  onRefresh,
  onOpenNewVisit,
  onOpenPhotoCompare
}) => {
  const [customFields, setCustomFields] = useState<Record<string, string | number | boolean>>(client.customFields || {});
  const [newKey, setNewKey] = useState('');
  const [newValue, setNewValue] = useState('');
  const [isEditingFields, setIsEditingFields] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [isEditClientOpen, setIsEditClientOpen] = useState(false);

  const handleAddField = () => {
    if (!newKey.trim()) return;
    setCustomFields(prev => ({ ...prev, [newKey.trim()]: newValue.trim() }));
    setNewKey('');
    setNewValue('');
  };

  const handleRemoveField = (key: string) => {
    setCustomFields(prev => {
      const copy = { ...prev };
      delete copy[key];
      return copy;
    });
  };

  const handleSaveFields = async () => {
    setIsSaving(true);
    try {
      await api.updateClient(client.id, { customFields });
      setIsEditingFields(false);
      onRefresh();
    } catch (err) {
      alert(writeErrorMessage(err, 'Failed to save custom fields'));
    } finally {
      setIsSaving(false);
    }
  };

  const handleDeleteClient = async () => {
    if (confirm(`Are you sure you want to delete ${client.name} and all visit logs?`)) {
      try {
        await api.deleteClient(client.id);
      } catch (err) {
        // Previously unguarded, which was survivable only while every failure
        // fell back to localStorage and "succeeded". A refused delete now
        // throws, and without this the modal would close as if the record were
        // gone while the server still has it.
        alert(writeErrorMessage(err, 'Failed to delete this client.'));
        return;
      }
      onRefresh();
      onClose();
    }
  };

  return (
    <div style={{
      position: 'fixed',
      inset: 0,
      background: 'rgba(0,0,0,0.8)',
      backdropFilter: 'blur(10px)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      zIndex: 900,
      padding: '20px'
    }}>
      <div className="glass-panel-glow" style={{
        width: '950px',
        maxWidth: '95vw',
        maxHeight: '92vh',
        borderRadius: '20px',
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden'
      }}>
        {/* Header */}
        <div style={{
          padding: '24px',
          borderBottom: '1px solid var(--border-color)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          background: 'rgba(15, 14, 19, 0.9)'
        }}>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
              <h2 className="text-gradient" style={{ fontSize: '24px' }}>{client.name}</h2>
              <span style={{ fontSize: '12px', background: 'rgba(229,184,153,0.15)', color: 'var(--rose-gold-primary)', padding: '4px 10px', borderRadius: '12px', border: '1px solid rgba(229,184,153,0.3)', fontWeight: 600 }}>
                {client.totalVisits} Total Visits
              </span>
            </div>
            <p style={{ color: 'var(--text-muted)', fontSize: '13px', marginTop: '4px' }}>
              Phone: <strong style={{ color: '#fff' }}>{client.phone}</strong> {client.email && `| Email: ${client.email}`}
            </p>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <button className="btn-secondary" onClick={() => setIsEditClientOpen(true)} style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <Edit2 size={16} /> Edit Client
            </button>
            <button className="btn-rose" onClick={() => onOpenNewVisit(client)}>
              <Plus size={16} /> Log New Visit
            </button>
            <button onClick={handleDeleteClient} style={{ background: 'rgba(239,68,68,0.15)', color: '#f87171', border: '1px solid rgba(239,68,68,0.3)', padding: '10px 14px', borderRadius: 'var(--radius-sm)', cursor: 'pointer' }}>
              <Trash2 size={16} />
            </button>
            <button onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}>
              <X size={24} />
            </button>
          </div>
        </div>

        {/* Modal Body */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '24px', display: 'flex', flexDirection: 'column', gap: '24px' }}>
          
          {/* Custom Client Fields Section (JSONB dynamic fields) */}
          <div className="glass-panel" style={{ padding: '20px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '14px' }}>
              <h3 style={{ fontSize: '16px', color: 'var(--rose-gold-primary)', display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Sliders size={18} /> Dynamic Custom Client Attributes (JSONB)
              </h3>
              {!isEditingFields ? (
                <button className="btn-secondary" style={{ padding: '4px 10px', fontSize: '12px' }} onClick={() => setIsEditingFields(true)}>
                  <Edit3 size={14} /> Edit Attributes
                </button>
              ) : (
                <div style={{ display: 'flex', gap: '8px' }}>
                  <button className="btn-rose" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={handleSaveFields} disabled={isSaving}>
                    {isSaving ? 'Saving...' : 'Save Changes'}
                  </button>
                  <button className="btn-secondary" style={{ padding: '4px 10px', fontSize: '12px' }} onClick={() => setIsEditingFields(false)}>
                    Cancel
                  </button>
                </div>
              )}
            </div>

            {/* Display / Edit Custom Fields */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: '12px' }}>
              {Object.keys(customFields).map(key => (
                <div key={key} style={{ background: 'rgba(15,14,19,0.7)', padding: '10px 14px', borderRadius: '10px', border: '1px solid var(--border-color)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <div>
                    <span style={{ fontSize: '11px', color: 'var(--text-muted)', display: 'block', textTransform: 'uppercase' }}>{key}</span>
                    <span style={{ fontSize: '13px', color: '#fff', fontWeight: 600 }}>{String(customFields[key])}</span>
                  </div>
                  {isEditingFields && (
                    <button onClick={() => handleRemoveField(key)} style={{ background: 'none', border: 'none', color: '#f87171', cursor: 'pointer' }}>
                      <Trash2 size={14} />
                    </button>
                  )}
                </div>
              ))}
            </div>

            {/* Add New Custom Field Row */}
            {isEditingFields && (
              <div style={{ display: 'flex', gap: '10px', marginTop: '16px', paddingTop: '14px', borderTop: '1px dashed var(--border-color)' }}>
                <input
                  type="text"
                  placeholder="Attribute Name (e.g. Skin Tone, Dye Ratio)"
                  className="input-field"
                  value={newKey}
                  onChange={(e) => setNewKey(e.target.value)}
                  style={{ flex: '1 1 200px' }}
                />
                <input
                  type="text"
                  placeholder="Value (e.g. Warm Olive, 1:1)"
                  className="input-field"
                  value={newValue}
                  onChange={(e) => setNewValue(e.target.value)}
                  style={{ flex: '1 1 200px' }}
                />
                <button className="btn-secondary" onClick={handleAddField}>
                  <Plus size={16} /> Add Attribute
                </button>
              </div>
            )}
          </div>

          {/* Chronological Visit History Timeline */}
          <div>
            <h3 style={{ fontSize: '18px', color: '#fff', marginBottom: '16px', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <Calendar size={20} color="var(--rose-gold-primary)" /> Visit History & Procedure Timeline
            </h3>

            {visits.length === 0 ? (
              <div className="glass-panel" style={{ padding: '30px', textAlign: 'center', color: 'var(--text-muted)' }}>
                <FileText size={32} style={{ marginBottom: '10px', opacity: 0.5 }} />
                <p>No visit logs found for this client yet.</p>
                <button className="btn-rose" style={{ marginTop: '14px' }} onClick={() => onOpenNewVisit(client)}>
                  <Plus size={16} /> Log First Visit
                </button>
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                {visits.map(visit => {
                  // Some records created before attachments were introduced do
                  // not contain the field.  Treat them as having no files.
                  const attachments = Array.isArray(visit.attachments) ? visit.attachments : [];
                  const statusClass = visit.status === 'COMPLETED' ? 'status-completed' : visit.status === 'SCHEDULED' ? 'status-scheduled' : 'status-cancelled';
                  const hasBeforeAfter = attachments.some(a => a.tag === 'BEFORE') && attachments.some(a => a.tag === 'AFTER');

                  return (
                    <div key={visit.id} className="glass-panel" style={{ padding: '20px', borderLeft: '4px solid var(--rose-gold-primary)' }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '10px', marginBottom: '12px' }}>
                        <div>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                            <span style={{ fontSize: '15px', fontWeight: 700, color: '#fff' }}>
                              {new Date(visit.visitDateTime).toLocaleDateString(undefined, { weekday: 'short', year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
                            </span>
                            <span className={`tag-badge ${statusClass}`}>{visit.status}</span>
                          </div>
                          <span style={{ fontSize: '12px', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: '4px', marginTop: '4px' }}>
                            <Clock size={12} /> Duration: {visit.durationMinutes} minutes
                          </span>
                        </div>

                        {hasBeforeAfter && (
                          <button className="btn-rose" style={{ padding: '6px 12px', fontSize: '12px' }} onClick={() => onOpenPhotoCompare(attachments)}>
                            <Camera size={14} /> Compare Before/After
                          </button>
                        )}
                      </div>

                      {/* Procedure Summary Notes */}
                      <p style={{ fontSize: '14px', color: 'var(--text-main)', lineHeight: '1.6', background: 'rgba(15,14,19,0.5)', padding: '12px 16px', borderRadius: '10px', whiteSpace: 'pre-line', marginBottom: '14px', border: '1px solid rgba(255,255,255,0.05)' }}>
                        {visit.procedureNotes}
                      </p>

                      {/* Photo Attachments Grid */}
                      {attachments.length > 0 && (
                        <div>
                          <p style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '8px', fontWeight: 600 }}>Attachments ({attachments.length} Photos/Files):</p>
                          <div style={{ display: 'flex', gap: '12px', flexWrap: 'wrap' }}>
                            {attachments.map(att => (
                              <div key={att.id} style={{ position: 'relative', width: '100px', height: '100px', borderRadius: '10px', overflow: 'hidden', border: '1px solid var(--border-color)' }}>
                                <img src={att.fileUrl} alt={att.caption || 'Attachment'} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                                <span style={{ position: 'absolute', bottom: '4px', left: '4px', background: 'rgba(0,0,0,0.8)', color: att.tag === 'BEFORE' ? 'var(--rose-gold-primary)' : att.tag === 'AFTER' ? '#2dd4bf' : '#fff', padding: '2px 6px', borderRadius: '6px', fontSize: '9px', fontWeight: 700 }}>
                                  {att.tag}
                                </span>
                              </div>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>

        </div>
      </div>

      {/* Edit Client Modal */}
      {isEditClientOpen && (
        <EditClientModal
          client={client}
          onClose={() => setIsEditClientOpen(false)}
          onSuccess={() => { setIsEditClientOpen(false); onRefresh(); }}
        />
      )}
    </div>
  );
};
