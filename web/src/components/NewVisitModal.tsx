import { useAppTranslation } from '../i18n/LocaleProvider';
import React, { useId, useState } from 'react';
import { useDismiss } from './useDismiss';
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

export const NewVisitModal: React.FC<NewVisitModalProps> = ({
  client,
  clientsList,
  onClose,
  onSuccess
}) => {
  const { t } = useAppTranslation();
  const dismiss = useDismiss(onClose);
  const id = useId();
  const [selectedClientId, setSelectedClientId] = useState(client?.id || (clientsList[0]?.id || ''));
  const [visitDateTime, setVisitDateTime] = useState(() => localDateTimeInputValue(new Date()));
  const [durationMinutes, setDurationMinutes] = useState(60);
  const [procedureNotes, setProcedureNotes] = useState('');
  const [status, setStatus] = useState<Visit['status']>('COMPLETED');
  
  // Attachments state
  const [beforePhoto, setBeforePhoto] = useState<string | null>(null);
  const [afterPhoto, setAfterPhoto] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handlePhotoFile = async (file: File, tag: 'BEFORE' | 'AFTER') => {
    try {
      // Client-side auto compression before payload dispatch
      const compressedDataUrl = await compressImage(file, 1200, 0.85);
      if (tag === 'BEFORE') setBeforePhoto(compressedDataUrl);
      else setAfterPhoto(compressedDataUrl);
    } catch {
      alert(t('newVisitModal.failedToCompressImageFile'));
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedClientId) {
      alert(t('newVisitModal.pleaseSelectAClient'));
      return;
    }
    if (!procedureNotes.trim()) {
      alert(t('newVisitModal.pleaseEnterProcedureNotesAndDetails'));
      return;
    }

    setIsSubmitting(true);
    try {
      const visit = await api.createVisit({
        clientId: selectedClientId,
        visitDateTime,
        durationMinutes,
        procedureNotes,
        status
      });

      // Upload Before photo if attached
      if (beforePhoto) {
        await api.addAttachment(visit.id, beforePhoto, 'BEFORE');
      }
      // Upload After photo if attached
      if (afterPhoto) {
        await api.addAttachment(visit.id, afterPhoto, 'AFTER');
      }

      onSuccess();
      onClose();
    } catch (err) {
      alert(writeErrorMessage(err,t('newVisitModal.failedToLogVisitRecord')));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="dialog-overlay" {...dismiss}>
      <div role="dialog" aria-modal="true" aria-labelledby={`${id}-title`} tabIndex={-1} className="dialog-panel client-dialog client-dialog--wide">
        {/* Header */}
        <div className="dialog-header">
          <h2 id={`${id}-title`} className="dialog-title">
            <Calendar size={20} /> {t('newVisitModal.logProcedureVisitEntry')}
          </h2>
          <button type="button" aria-label={t('common.close')} onClick={onClose} className="close-button">
            <X size={22} />
          </button>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} className="dialog-body dialog-form">
          
          {/* Client Select */}
          <div className="form-field">
            <label htmlFor={`${id}-client`}>{t('newVisitModal.clientProfile')}</label>
            <select
              id={`${id}-client`}
              className="input-field"
              value={selectedClientId}
              onChange={(e) => setSelectedClientId(e.target.value)}
              disabled={!!client}
            >
              {clientsList.map(c => (
                <option key={c.id} value={c.id}>
                  {c.name} ({c.phone})
                </option>
              ))}
            </select>
          </div>

          <div className="form-grid form-grid--three">
            <div className="form-field">
              <label htmlFor={`${id}-date`}>{t('newVisitModal.dateAndTime')}</label>
              <input
                id={`${id}-date`}
                type="datetime-local"
                required
                className="input-field"
                value={visitDateTime}
                onChange={(e) => setVisitDateTime(e.target.value)}
              />
            </div>

            <div className="form-field">
              <label htmlFor={`${id}-duration`}>{t('newVisitModal.durationMins')}</label>
              <input
                id={`${id}-duration`}
                type="number"
                min="15"
                step="15"
                className="input-field"
                value={durationMinutes}
                onChange={(e) => setDurationMinutes(parseInt(e.target.value) || 60)}
              />
            </div>

            <div className="form-field">
              <label htmlFor={`${id}-status`}>{t('newVisitModal.visitStatus')}</label>
              <select
                id={`${id}-status`}
                className="input-field"
                value={status}
                onChange={(e) => setStatus(e.target.value as any)}
              >
                <option value="COMPLETED">{t('newVisitModal.cOMPLETED')}</option>
                <option value="SCHEDULED">{t('newVisitModal.sCHEDULED')}</option>
                <option value="CANCELLED">{t('newVisitModal.cANCELLED')}</option>
              </select>
            </div>
          </div>

          {/* Procedure Notes */}
          <div className="form-field">
            <label htmlFor={`${id}-notes`}>
              {t('newVisitModal.procedureDetailsAndFormulaNotes')}
            </label>
            <textarea
              id={`${id}-notes`}
              required
              rows={4}
              className="input-field"
              placeholder={t('newVisitModal.enterLashMappingSpecsHairDyeFormulaRatiosLaserIntensityLevelsOrS')}
              value={procedureNotes}
              onChange={(e) => setProcedureNotes(e.target.value)}
            />
          </div>

          {/* Before & After Photo Attachments */}
          <div className="form-section">
            <p className="form-section__title">
              {t('newVisitModal.attachProcedureMediaCompressedBeforeUpload')}
            </p>
            <div className="media-grid">
              
              {/* Before Photo Box */}
              <div className="media-slot">
                <span className="media-slot__label">{t('newVisitModal.bEFOREPHOTO')}</span>
                {beforePhoto ? (
                  <div className="media-preview">
                    <img src={beforePhoto} alt={t('newVisitModal.before')} loading="lazy" decoding="async" />
                    <button type="button" aria-label={t('common.remove')} onClick={() => setBeforePhoto(null)} className="icon-button">
                      <X size={14} />
                    </button>
                  </div>
                ) : (
                  <div>
                    <PhotoSourcePicker color="var(--rose-gold-primary)" disabled={isSubmitting} onFile={(file) => handlePhotoFile(file, 'BEFORE')} />
                  </div>
                )}
              </div>

              {/* After Photo Box */}
              <div className="media-slot">
                <span className="media-slot__label">{t('newVisitModal.aFTERPHOTO')}</span>
                {afterPhoto ? (
                  <div className="media-preview">
                    <img src={afterPhoto} alt={t('newVisitModal.after')} loading="lazy" decoding="async" />
                    <button type="button" aria-label={t('common.remove')} onClick={() => setAfterPhoto(null)} className="icon-button">
                      <X size={14} />
                    </button>
                  </div>
                ) : (
                  <div>
                    <PhotoSourcePicker color="var(--emerald-accent)" disabled={isSubmitting} onFile={(file) => handlePhotoFile(file, 'AFTER')} />
                  </div>
                )}
              </div>

            </div>
          </div>

          <div className="dialog-footer">
            <button type="button" className="btn-secondary" onClick={onClose}>{t('newVisitModal.cancel')}</button>
            <button type="submit" className="btn-rose" disabled={isSubmitting}>
              {isSubmitting ?t('newVisitModal.savingVisitRecord') :t('newVisitModal.logVisitRecord')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
