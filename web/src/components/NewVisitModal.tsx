import React, { useState } from 'react';
import type { Client, Visit } from '../types';
import { api } from '../services/api';
import { compressImage } from '../utils/imageCompressor';
import { X, Calendar, Camera } from 'lucide-react';

interface NewVisitModalProps {
  client?: Client;
  clientsList: Client[];
  onClose: () => void;
  onSuccess: () => void;
}

export const NewVisitModal: React.FC<NewVisitModalProps> = ({
  client,
  clientsList,
  onClose,
  onSuccess
}) => {
  const [selectedClientId, setSelectedClientId] = useState(client?.id || (clientsList[0]?.id || ''));
  const [visitDateTime, setVisitDateTime] = useState(new Date().toISOString().slice(0, 16));
  const [durationMinutes, setDurationMinutes] = useState(60);
  const [procedureNotes, setProcedureNotes] = useState('');
  const [status, setStatus] = useState<Visit['status']>('COMPLETED');
  
  // Attachments state
  const [beforePhoto, setBeforePhoto] = useState<string | null>(null);
  const [afterPhoto, setAfterPhoto] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handlePhotoUpload = async (e: React.ChangeEvent<HTMLInputElement>, tag: 'BEFORE' | 'AFTER') => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      // Client-side auto compression before payload dispatch
      const compressedDataUrl = await compressImage(file, 1200, 0.85);
      if (tag === 'BEFORE') setBeforePhoto(compressedDataUrl);
      else setAfterPhoto(compressedDataUrl);
    } catch (err) {
      alert('Failed to compress image file.');
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedClientId) {
      alert('Please select a client.');
      return;
    }
    if (!procedureNotes.trim()) {
      alert('Please enter procedure notes and details.');
      return;
    }

    setIsSubmitting(true);
    try {
      const visit = await api.createVisit({
        clientId: selectedClientId,
        visitDateTime: new Date(visitDateTime).toISOString(),
        durationMinutes,
        procedureNotes,
        status
      });

      // Upload Before photo if attached
      if (beforePhoto) {
        await api.addAttachment(visit.id, beforePhoto, 'BEFORE', 'Baseline before procedure photo');
      }
      // Upload After photo if attached
      if (afterPhoto) {
        await api.addAttachment(visit.id, afterPhoto, 'AFTER', 'Finished procedure photo result');
      }

      onSuccess();
      onClose();
    } catch (err) {
      alert('Failed to log visit record');
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
        width: '750px',
        maxWidth: '95vw',
        maxHeight: '92vh',
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
            <Calendar size={20} /> Log Procedure Visit Entry
          </h2>
          <button onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}>
            <X size={22} />
          </button>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} style={{ padding: '24px', flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '18px' }}>
          
          {/* Client Select */}
          <div>
            <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>Client Profile *</label>
            <select
              className="input-field"
              value={selectedClientId}
              onChange={(e) => setSelectedClientId(e.target.value)}
              disabled={!!client}
              style={{ height: '42px' }}
            >
              {clientsList.map(c => (
                <option key={c.id} value={c.id} style={{ background: '#181622', color: '#fff' }}>
                  {c.name} ({c.phone})
                </option>
              ))}
            </select>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '14px' }}>
            <div>
              <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>Date & Time *</label>
              <input
                type="datetime-local"
                required
                className="input-field"
                value={visitDateTime}
                onChange={(e) => setVisitDateTime(e.target.value)}
              />
            </div>

            <div>
              <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>Duration (Mins)</label>
              <input
                type="number"
                min="15"
                step="15"
                className="input-field"
                value={durationMinutes}
                onChange={(e) => setDurationMinutes(parseInt(e.target.value) || 60)}
              />
            </div>

            <div>
              <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>Visit Status</label>
              <select
                className="input-field"
                value={status}
                onChange={(e) => setStatus(e.target.value as any)}
                style={{ height: '42px' }}
              >
                <option value="COMPLETED" style={{ background: '#181622' }}>COMPLETED</option>
                <option value="SCHEDULED" style={{ background: '#181622' }}>SCHEDULED</option>
                <option value="CANCELLED" style={{ background: '#181622' }}>CANCELLED</option>
              </select>
            </div>
          </div>

          {/* Procedure Notes */}
          <div>
            <label style={{ fontSize: '12px', color: 'var(--rose-gold-primary)', fontWeight: 600, marginBottom: '6px', display: 'block' }}>
              Procedure Details & Formula Notes *
            </label>
            <textarea
              required
              rows={4}
              className="input-field"
              placeholder="Enter lash mapping specs, hair dye formula ratios, laser intensity levels, or skin treatment details..."
              value={procedureNotes}
              onChange={(e) => setProcedureNotes(e.target.value)}
              style={{ lineHeight: '1.5', fontFamily: 'inherit' }}
            />
          </div>

          {/* Before & After Photo Attachments */}
          <div>
            <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '8px', display: 'block', fontWeight: 600 }}>
              Attach Procedure Media (Compressed before upload)
            </label>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
              
              {/* Before Photo Box */}
              <div style={{
                border: '1px dashed var(--border-color)',
                borderRadius: '12px',
                padding: '16px',
                textAlign: 'center',
                background: 'rgba(15,14,19,0.5)',
                position: 'relative'
              }}>
                <span style={{ fontSize: '11px', fontWeight: 700, color: 'var(--rose-gold-primary)', textTransform: 'uppercase' }}>BEFORE PHOTO</span>
                {beforePhoto ? (
                  <div style={{ marginTop: '10px', height: '120px', borderRadius: '8px', overflow: 'hidden', position: 'relative' }}>
                    <img src={beforePhoto} alt="Before" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                    <button type="button" onClick={() => setBeforePhoto(null)} style={{ position: 'absolute', top: '6px', right: '6px', background: 'rgba(0,0,0,0.7)', border: 'none', color: '#fff', borderRadius: '50%', padding: '4px', cursor: 'pointer' }}>
                      <X size={14} />
                    </button>
                  </div>
                ) : (
                  <label style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '6px', marginTop: '12px', cursor: 'pointer' }}>
                    <Camera size={24} color="var(--rose-gold-primary)" />
                    <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>Upload Before Photo</span>
                    <input type="file" accept="image/*" style={{ display: 'none' }} onChange={(e) => handlePhotoUpload(e, 'BEFORE')} />
                  </label>
                )}
              </div>

              {/* After Photo Box */}
              <div style={{
                border: '1px dashed var(--border-color)',
                borderRadius: '12px',
                padding: '16px',
                textAlign: 'center',
                background: 'rgba(15,14,19,0.5)',
                position: 'relative'
              }}>
                <span style={{ fontSize: '11px', fontWeight: 700, color: '#2dd4bf', textTransform: 'uppercase' }}>AFTER PHOTO</span>
                {afterPhoto ? (
                  <div style={{ marginTop: '10px', height: '120px', borderRadius: '8px', overflow: 'hidden', position: 'relative' }}>
                    <img src={afterPhoto} alt="After" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                    <button type="button" onClick={() => setAfterPhoto(null)} style={{ position: 'absolute', top: '6px', right: '6px', background: 'rgba(0,0,0,0.7)', border: 'none', color: '#fff', borderRadius: '50%', padding: '4px', cursor: 'pointer' }}>
                      <X size={14} />
                    </button>
                  </div>
                ) : (
                  <label style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '6px', marginTop: '12px', cursor: 'pointer' }}>
                    <Camera size={24} color="#2dd4bf" />
                    <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>Upload After Photo</span>
                    <input type="file" accept="image/*" style={{ display: 'none' }} onChange={(e) => handlePhotoUpload(e, 'AFTER')} />
                  </label>
                )}
              </div>

            </div>
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px', marginTop: '12px', paddingTop: '16px', borderTop: '1px solid var(--border-color)' }}>
            <button type="button" className="btn-secondary" onClick={onClose}>Cancel</button>
            <button type="submit" className="btn-rose" disabled={isSubmitting}>
              {isSubmitting ? 'Saving Visit Record...' : 'Log Visit Record'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
