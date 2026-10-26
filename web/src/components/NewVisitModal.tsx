import { useAppTranslation } from '../i18n/useLocale';
import React, { useId, useRef, useState } from 'react';
import { useDismiss } from './useDismiss';
import { useDraftGuard } from './useDraftGuard';
import type { Client, Visit } from '../types';
import { api, writeErrorMessage } from '../services/api';
import { localDateTimeInputValue } from '../utils/localDateTime';
import { compressImage } from '../utils/imageCompressor';
import { PhotoSourcePicker } from './PhotoSourcePicker';
import { X, Calendar } from 'lucide-react';
import './ClientForms.css';

interface NewVisitModalProps {
  client?: Client;
  clientsList: Client[];
  onClose: () => void;
  onSuccess: () => void;
}

type PhotoTag = 'BEFORE' | 'AFTER';
type FieldErrors = Partial<Record<'client' | 'date' | 'duration' | 'notes', string>>;
type SavedVisit = { id: string; photos: { tag: PhotoTag; data: string; uploaded: boolean }[] };

export const NewVisitModal: React.FC<NewVisitModalProps> = ({ client, clientsList, onClose, onSuccess }) => {
  const { t } = useAppTranslation();
  const id = useId();
  const [initialDate] = useState(() => localDateTimeInputValue(new Date()));
  const [selectedClientId, setSelectedClientId] = useState(client?.id ?? '');
  const [visitDateTime, setVisitDateTime] = useState(initialDate);
  const [durationMinutes, setDurationMinutes] = useState('60');
  const [procedureNotes, setProcedureNotes] = useState('');
  const [status, setStatus] = useState<Visit['status']>('COMPLETED');
  const [photos, setPhotos] = useState<Partial<Record<PhotoTag, string>>>({});
  const [busy, setBusy] = useState<'saving' | 'compressing' | null>(null);
  const busyRef = useRef(false);
  // Retain server-confirmed progress across retries; never recreate a saved visit.
  const savedRef = useRef<SavedVisit | null>(null);
  const [saved, setSaved] = useState<SavedVisit | null>(null);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [error, setError] = useState('');
  const dirty = !!saved || selectedClientId !== (client?.id ?? '') || visitDateTime !== initialDate
    || durationMinutes !== '60' || procedureNotes !== '' || status !== 'COMPLETED' || Object.keys(photos).length > 0;
  const guardedClose = useDraftGuard(dirty, !!busy, () => {
    if (savedRef.current) onSuccess();
    onClose();
  }, t(saved ? 'visitUx.leavePartial' : 'visitUx.discard'));
  const close = () => { if (!busyRef.current) guardedClose(); };
  const dismiss = useDismiss(close);
  const locked = !!busy || !!saved;
  const edit = (update: () => void) => { if (!busyRef.current && !savedRef.current) update(); };

  const handlePhotoFile = async (file: File, tag: PhotoTag) => {
    if (busyRef.current || savedRef.current) return;
    busyRef.current = true;
    setBusy('compressing');
    setError('');
    try {
      const data = await compressImage(file, 1200, 0.85);
      setPhotos(current => ({ ...current, [tag]: data }));
    } catch {
      setError(t('newVisitModal.failedToCompressImageFile'));
    } finally {
      busyRef.current = false;
      setBusy(null);
    }
  };

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (busyRef.current) return;
    if (!savedRef.current) {
      const next: FieldErrors = {};
      if (!selectedClientId) next.client = t('newVisitModal.pleaseSelectAClient');
      if (!procedureNotes.trim()) next.notes = t('newVisitModal.pleaseEnterProcedureNotesAndDetails');
      if (!visitDateTime || !Number.isFinite(new Date(visitDateTime).getTime())) next.date = t('visitUx.invalidDate');
      const duration = Number(durationMinutes);
      if (!Number.isFinite(duration) || duration < 15 || duration % 15 !== 0) next.duration = t('visitUx.invalidDuration');
      setErrors(next);
      if (Object.keys(next).length) {
        const first = Object.keys(next)[0];
        document.getElementById(`${id}-${first}`)?.focus();
        return;
      }
    }
    busyRef.current = true;
    setBusy('saving');
    setError('');
    try {
      let progress = savedRef.current;
      if (!progress) {
        const visit = await api.createVisit({ clientId: selectedClientId, visitDateTime,
          durationMinutes: Number(durationMinutes), procedureNotes, status });
        progress = { id: visit.id, photos: (['BEFORE', 'AFTER'] as const).flatMap(tag =>
          photos[tag] ? [{ tag, data: photos[tag], uploaded: false }] : []) };
        savedRef.current = progress;
        setSaved({ ...progress });
      }
      let uploadError = '';
      for (const photo of progress.photos) {
        if (photo.uploaded) continue;
        try {
          await api.addAttachment(progress.id, photo.data, photo.tag);
          photo.uploaded = true;
          setSaved({ ...progress });
        } catch (err) {
          uploadError = writeErrorMessage(err, t('visitUx.uploadFailed'));
        }
      }
      if (progress.photos.some(photo => !photo.uploaded)) {
        setError(uploadError);
      } else {
        onSuccess();
        onClose();
      }
    } catch (err) {
      setError(writeErrorMessage(err, t('newVisitModal.failedToLogVisitRecord')));
    } finally {
      busyRef.current = false;
      setBusy(null);
    }
  };

  const fieldError = (field: keyof FieldErrors) => errors[field]
    ? <p id={`${id}-${field}-error`} className="field-error" role="alert">{errors[field]}</p> : null;
  const describedBy = (field: keyof FieldErrors) => errors[field] ? `${id}-${field}-error` : undefined;
  const options = client && !clientsList.some(item => item.id === client.id) ? [client, ...clientsList] : clientsList;

  return (
    <div className="dialog-overlay" {...dismiss}>
      <div role="dialog" aria-modal="true" aria-labelledby={`${id}-title`} aria-busy={!!busy} tabIndex={-1} className="dialog-panel client-dialog client-dialog--wide client-dialog--form">
        <div className="dialog-header">
          <h2 id={`${id}-title`} className="dialog-title"><Calendar size={20} /> {t('visitUx.title')}</h2>
          <button type="button" aria-label={t('common.close')} disabled={!!busy} onClick={close} className="close-button"><X size={22} /></button>
        </div>
        <form onSubmit={handleSubmit} noValidate className="dialog-body dialog-form">
          <div className="dialog-form-content">
            {saved && <p className="form-status" role="status">{t('visitUx.partial', {
              saved: saved.photos.filter(photo => photo.uploaded).length, total: saved.photos.length,
            })}</p>}
            {busy === 'compressing' && <p role="status">{t('visitUx.compressing')}</p>}
            {error && <p className="form-error" role="alert">{error}</p>}
            <div className="form-field">
              <label htmlFor={`${id}-client`}>{t('newVisitModal.clientProfile')}</label>
              <select id={`${id}-client`} required className="input-field" value={selectedClientId}
                onChange={event => edit(() => setSelectedClientId(event.target.value))} disabled={!!client || locked}
                aria-invalid={!!errors.client} aria-describedby={describedBy('client')}>
                <option value="">{t('visitUx.selectClient')}</option>
                {options.map(item => <option key={item.id} value={item.id}>{item.name} ({item.phone})</option>)}
              </select>
              {fieldError('client')}
            </div>
            <div className="form-grid form-grid--three">
              <div className="form-field">
                <label htmlFor={`${id}-date`}>{t('newVisitModal.dateAndTime')}</label>
                <input id={`${id}-date`} type="datetime-local" required className="input-field" value={visitDateTime} disabled={locked}
                  onChange={event => edit(() => setVisitDateTime(event.target.value))} aria-invalid={!!errors.date} aria-describedby={describedBy('date')} />
                {fieldError('date')}
              </div>
              <div className="form-field">
                <label htmlFor={`${id}-duration`}>{t('newVisitModal.durationMins')}</label>
                <input id={`${id}-duration`} type="number" min="15" step="15" required className="input-field" value={durationMinutes} disabled={locked}
                  onChange={event => edit(() => setDurationMinutes(event.target.value))} aria-invalid={!!errors.duration} aria-describedby={describedBy('duration')} />
                {fieldError('duration')}
              </div>
              <div className="form-field">
                <label htmlFor={`${id}-status`}>{t('newVisitModal.visitStatus')}</label>
                <select id={`${id}-status`} className="input-field" value={status} disabled={locked}
                  onChange={event => edit(() => setStatus(event.target.value as Visit['status']))}>
                  <option value="COMPLETED">{t('newVisitModal.cOMPLETED')}</option>
                  <option value="SCHEDULED">{t('newVisitModal.sCHEDULED')}</option>
                  <option value="CANCELLED">{t('newVisitModal.cANCELLED')}</option>
                </select>
              </div>
            </div>
            <div className="form-field">
              <label htmlFor={`${id}-notes`}>{t('visitUx.notes')}</label>
              <textarea id={`${id}-notes`} required rows={4} className="input-field" placeholder={t('visitUx.notesPlaceholder')}
                value={procedureNotes} disabled={locked} onChange={event => edit(() => setProcedureNotes(event.target.value))}
                aria-invalid={!!errors.notes} aria-describedby={describedBy('notes')} />
              {fieldError('notes')}
            </div>
            <div className="form-section">
              <p className="form-section__title">{t('visitUx.photos')}</p>
              <div className="media-grid">
                {(['BEFORE', 'AFTER'] as const).map(tag => <div className="media-slot" key={tag}>
                  <span className="media-slot__label">{t(tag === 'BEFORE' ? 'newVisitModal.before' : 'newVisitModal.after')}</span>
                  {photos[tag] ? <div className="media-preview">
                    <img src={photos[tag]} alt={t(tag === 'BEFORE' ? 'newVisitModal.before' : 'newVisitModal.after')} loading="lazy" decoding="async" />
                    <button type="button" aria-label={t('common.remove')} disabled={locked} className="icon-button" onClick={() => edit(() => setPhotos(current => {
                      const next = { ...current }; delete next[tag]; return next;
                    }))}><X size={14} /></button>
                  </div> : <PhotoSourcePicker disabled={locked} color={tag === 'BEFORE' ? 'var(--rose-gold-primary)' : 'var(--emerald-accent)'} onFile={file => handlePhotoFile(file, tag)} />}
                  {saved?.photos.some(photo => photo.tag === tag) && <span>{t(saved.photos.find(photo => photo.tag === tag)?.uploaded ? 'visitUx.uploaded' : 'visitUx.pending')}</span>}
                </div>)}
              </div>
            </div>
          </div>
          <div className="dialog-footer">
            <button type="button" className="btn-secondary" disabled={!!busy} onClick={close}>{t(saved ? 'visitUx.finish' : 'newVisitModal.cancel')}</button>
            <button type="submit" className="btn-rose" disabled={!!busy}>{t(busy === 'saving' ? 'visitUx.saving' : saved ? 'visitUx.retry' : 'visitUx.save')}</button>
          </div>
        </form>
      </div>
    </div>
  );
};
