import { useAppTranslation, useLocale } from '../i18n/useLocale';
import React, { useId, useRef, useState } from 'react';
import { useDismiss } from './useDismiss';
import { useDraftGuard } from './useDraftGuard';
import type { Client, Visit, Attachment } from '../types';
import { X, Calendar, Clock, Plus, Trash2, Edit3, Edit2, Camera, FileText, Sliders, ImagePlus, MoreHorizontal } from 'lucide-react';
import { AttributeEditor } from './AttributeEditor';
import { duplicateAttributeKey, toAttributeRecord, toAttributeRows, type AttributeRow } from './attributes';
import { api, writeErrorMessage } from '../services/api';
import { EditClientModal } from './EditClientModal';
import { PhotoSourcePicker } from './PhotoSourcePicker';
import { compressImage } from '../utils/imageCompressor';
import { defaultPhotoTag } from '../utils/photoTags';
import { AttachmentImage } from './AttachmentImage';
import { PhotoViewerModal } from './PhotoViewerModal';
import './ClientForms.css';

interface ClientDetailModalProps {
  client: Client;
  visits: Visit[];
  visitsLoading?: boolean;
  visitsError?: boolean;
  onClose: () => void;
  onRefresh: () => void;
  onOpenNewVisit: (client: Client) => void;
  onOpenPhotoCompare: (attachments: Attachment[]) => void;
}

export const ClientDetailModal: React.FC<ClientDetailModalProps> = ({
  client,
  visits,
  visitsLoading = false,
  visitsError = false,
  onClose,
  onRefresh,
  onOpenNewVisit,
  onOpenPhotoCompare
}) => {
  const { t } = useAppTranslation();
  const id = useId();
  const { formatDate } = useLocale();
  // Null while viewing. The draft is seeded from the current record each time
  // editing starts, so Cancel discards it and a refreshed record is never
  // overwritten by values captured when the modal opened.
  const [draftFields, setDraftFields] = useState<AttributeRow[] | null>(null);
  const isEditingFields = draftFields !== null;
  const [isSaving, setIsSaving] = useState(false);
  const [isEditClientOpen, setIsEditClientOpen] = useState(false);
  const [selectedPhoto, setSelectedPhoto] = useState<Attachment | null>(null);
  const savedFields = Object.entries(client.customFields ?? {});
  // Adding a photo to a visit that was already logged, e.g. the AFTER shot once the procedure is done.
  const [photoVisitId, setPhotoVisitId] = useState<string | null>(null);
  const [photoTag, setPhotoTag] = useState<Attachment['tag']>('BEFORE');
  const [uploadingPhoto, setUploadingPhoto] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);
  const [error, setError] = useState('');
  const busyRef = useRef(false);
  const fieldsBaseline = useRef('');
  const fieldsDirty = draftFields !== null && JSON.stringify(draftFields) !== fieldsBaseline.current;
  const busy = isSaving || uploadingPhoto || isDeleting;
  const guardedClose = useDraftGuard(fieldsDirty, busy, onClose, t('webUx.discardDraft'));
  const requestClose = () => { if (!busyRef.current) guardedClose(); };
  const dismiss = useDismiss(requestClose);
  const cancelFields = () => {
    if (!busyRef.current && (!fieldsDirty || window.confirm(t('webUx.discardDraft')))) {
      setDraftFields(null);
      setError('');
    }
  };
  const startFields = () => {
    if (busyRef.current) return;
    const rows = toAttributeRows(client.customFields);
    fieldsBaseline.current = JSON.stringify(rows);
    setDraftFields(rows);
    setError('');
  };

  const openAddPhoto = (visitId: string, attachments: Attachment[]) => {
    if (busyRef.current || isEditingFields) return;
    setPhotoVisitId(visitId);
    setPhotoTag(defaultPhotoTag(attachments));
  };

  const handleAddPhoto = async (visitId: string, file: File) => {
    if (busyRef.current || isEditingFields) return;
    busyRef.current = true;
    setError('');
    setUploadingPhoto(true);
    try {
      const compressedDataUrl = await compressImage(file, 1200, 0.85);
      await api.addAttachment(visitId, compressedDataUrl, photoTag);
      setPhotoVisitId(null);
      onRefresh();
    } catch (err) {
      setError(writeErrorMessage(err, t('photoPicker.failedToAddPhoto')));
    } finally {
      busyRef.current = false;
      setUploadingPhoto(false);
    }
  };

  const handleSaveFields = async () => {
    if (!draftFields || busyRef.current) return;
    const duplicate = duplicateAttributeKey(draftFields);
    if (duplicate) {
      setError(t('attributes.duplicateKey', { key: duplicate }));
      return;
    }
    busyRef.current = true;
    setError('');
    setIsSaving(true);
    try {
      await api.updateClient(client.id, { customFields: toAttributeRecord(draftFields) });
      setDraftFields(null);
      onRefresh();
    } catch (err) {
      setError(writeErrorMessage(err,t('clientDetailModal.failedToSaveCustomFields')));
    } finally {
      busyRef.current = false;
      setIsSaving(false);
    }
  };

  const handleDeleteClient = async () => {
    if (busyRef.current || isEditingFields || !window.confirm(t('clientDetailModal.confirmDelete', { name: client.name }))) return;
    busyRef.current = true;
    setIsDeleting(true);
    setError('');
    try {
      await api.deleteClient(client.id);
      onRefresh();
      onClose();
    } catch (err) {
      setError(writeErrorMessage(err, t('clientDetailModal.failedToDeleteThisClient')));
    } finally {
      busyRef.current = false;
      setIsDeleting(false);
    }
  };

  return (
    <div className="dialog-overlay" {...dismiss}>
      <div role="dialog" aria-modal="true" aria-labelledby={`${id}-title`} tabIndex={-1} className="dialog-panel client-dialog client-dialog--detail">
        {/* Header */}
        <div className="dialog-header dialog-header--responsive">
          <div className="dialog-header__copy">
            <div className="detail-summary">
              <h2 id={`${id}-title`} className="dialog-title">{client.name}</h2>
              <span style={{ fontSize: '12px', background: 'var(--bg-elevated)', color: 'var(--rose-gold-primary)', padding: '4px 10px', borderRadius: '12px', border: '1px solid var(--border-color)', fontWeight: 600 }}>
                {client.totalVisits} {t('clientDetailModal.totalVisits')}
              </span>
            </div>
            <p className="detail-summary">
              {t('clientDetailModal.phone')} <strong>{client.phone}</strong> {client.email && `| ${t('auth.email')}: ${client.email}`}
            </p>
          </div>

          <div className="detail-actions">
            <button type="button" className="btn-secondary" disabled={busy || isEditingFields} onClick={() => setIsEditClientOpen(true)}>
              <Edit2 size={16} /> {t('clientDetailModal.editClient')}
            </button>
            <button className="btn-rose" disabled={busy || isEditingFields} onClick={() => onOpenNewVisit(client)}>
              <Plus size={16} /> {t('clientDetailModal.logNewVisit')}
            </button>
            <details className="profile-menu">
              <summary aria-label={t('webUx.moreActions')}><MoreHorizontal size={20} /></summary>
              <div className="profile-menu__items">
                <button type="button" disabled={busy || isEditingFields} onClick={handleDeleteClient} className="btn-secondary">
                  <Trash2 size={16} /> {isDeleting ? t('webUx.deleting') : t('common.delete')}
                </button>
              </div>
            </details>
          </div>
          <button type="button" aria-label={t('common.close')} onClick={requestClose} disabled={busy} className="close-button">
            <X size={24} />
          </button>
        </div>

        {/* Modal Body */}
        {error && <div className="form-alert profile-feedback" role="alert">{error}</div>}
        <div className="dialog-body dialog-stack" aria-busy={busy}>
          
          {/* Custom Client Fields Section (JSONB dynamic fields) */}
          <details className="detail-card optional-details" onToggle={(event) => { if (isEditingFields) event.currentTarget.open = true; }}>
            <summary><Sliders size={18} /> {t('webUx.clientDetails')}</summary>
            <div className="attribute-section-head">
              <h3 style={{ fontSize: '16px', color: 'var(--rose-gold-primary)', display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Sliders size={18} /> {t('clientDetailModal.dynamicCustomClientAttributesJSONB')}
              </h3>
              {!isEditingFields ? (
                <button className="btn-secondary" style={{ padding: '4px 10px', fontSize: '12px' }} disabled={busy} onClick={startFields}>
                  <Edit3 size={14} /> {t('clientDetailModal.editAttributes')}
                </button>
              ) : (
                <div style={{ display: 'flex', gap: '8px' }}>
                  <button className="btn-rose" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={handleSaveFields} disabled={isSaving}>
                    {isSaving ?t('clientDetailModal.saving') :t('clientDetailModal.saveChanges')}
                  </button>
                  <button className="btn-secondary" style={{ padding: '4px 10px', fontSize: '12px' }} disabled={isSaving} onClick={cancelFields}>
                    {t('clientDetailModal.cancel')}
                  </button>
                </div>
              )}
            </div>

            {draftFields ? (
              <AttributeEditor rows={draftFields} onChange={setDraftFields} disabled={isSaving} addLabel={t('clientDetailModal.addAttribute')} />
            ) : savedFields.length === 0 ? (
              <p style={{ fontSize: '13px', color: 'var(--text-muted)' }}>{t('attributes.none')}</p>
            ) : (
              <div className="attribute-grid">
                {savedFields.map(([key, value]) => (
                  <div key={key} className="attribute-tile">
                    <div style={{ minWidth: 0 }}>
                      <span style={{ fontSize: '11px', color: 'var(--text-muted)', display: 'block', textTransform: 'uppercase', letterSpacing: '0.04em' }}>{key}</span>
                      <span style={{ fontSize: '14px', color: 'var(--text-main)', fontWeight: 600, overflowWrap: 'anywhere' }}>{String(value)}</span>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </details>

          {/* Chronological Visit History Timeline */}
          <div>
            <h3 className="section-heading">
              <Calendar size={20} color="var(--rose-gold-primary)" /> {t('clientDetailModal.visitHistoryAndProcedureTimeline')}
            </h3>

            {visitsLoading ? (
              <p role="status">{t('clientDetailModal.loadingVisits')}</p>
            ) : visitsError ? (
              <div role="alert">
                <p>{t('clientDetailModal.failedToLoadVisits')}</p>
                <button className="btn-secondary" onClick={onRefresh}>{t('clientDetailModal.retryVisits')}</button>
              </div>
            ) : visits.length === 0 ? (
              <div className="glass-panel" style={{ padding: '30px', textAlign: 'center', color: 'var(--text-muted)' }}>
                <FileText size={32} style={{ marginBottom: '10px', opacity: 0.5 }} />
                <p>{t('clientDetailModal.noVisitLogsFoundForThisClientYet')}</p>
                <button className="btn-rose" style={{ marginTop: '14px' }} disabled={busy || isEditingFields} onClick={() => onOpenNewVisit(client)}>
                  <Plus size={16} /> {t('clientDetailModal.logFirstVisit')}
                </button>
              </div>
            ) : (
              <div className="visit-list">
                {visits.map(visit => {
                  // Some records created before attachments were introduced do
                  // not contain the field.  Treat them as having no files.
                  const attachments = Array.isArray(visit.attachments) ? visit.attachments : [];
                  const statusClass = visit.status === 'COMPLETED' ? 'status-completed' : visit.status === 'SCHEDULED' ? 'status-scheduled' : 'status-cancelled';
                  const hasBeforeAfter = attachments.some(a => a.tag === 'BEFORE') && attachments.some(a => a.tag === 'AFTER');

                  return (
                    <article key={visit.id} className="visit-card">
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '10px', marginBottom: '12px' }}>
                        <div>
                          <div className="visit-date-status">
                            <span className="visit-date">
                              {formatDate(visit.visitDateTime, { weekday: 'short', year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
                            </span>
                            <span className={`tag-badge ${statusClass}`}>{t(visit.status === 'COMPLETED' ? 'newVisitModal.cOMPLETED' : visit.status === 'SCHEDULED' ? 'newVisitModal.sCHEDULED' : 'newVisitModal.cANCELLED')}</span>
                          </div>
                          <span style={{ fontSize: '12px', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: '4px', marginTop: '4px' }}>
                            <Clock size={12} /> {t('clientDetailModal.duration')} {t('counts.minutes', { count: visit.durationMinutes })}
                          </span>
                        </div>

                        {hasBeforeAfter && (
                          <button className="btn-rose" style={{ padding: '6px 12px', fontSize: '12px' }} disabled={busy || isEditingFields} onClick={() => onOpenPhotoCompare(attachments)}>
                            <Camera size={14} /> {t('clientDetailModal.compareBeforeAfter')}
                          </button>
                        )}
                      </div>

                      {/* Procedure Summary Notes */}
                      <p className="visit-note">
                        {visit.procedureNotes}
                      </p>

                      {/* Photo Attachments Grid */}
                      {attachments.length > 0 && (
                        <div>
                          <p style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '8px', fontWeight: 600 }}>{t('clientDetailModal.attachmentCount', { count: attachments.length })}</p>
                          <div style={{ display: 'flex', gap: '12px', flexWrap: 'wrap' }}>
                            {attachments.map(att => (
                              <div key={att.id} className="attachment">
                                <AttachmentImage fileUrl={att.fileUrl} alt={att.caption || t('clientDetailModal.attachment')}
                                  onOpen={() => setSelectedPhoto(att)}
                                  openLabel={t('webUx.openPhoto', { caption: att.caption || t(att.tag === 'BEFORE' ? 'newVisitModal.before' : att.tag === 'AFTER' ? 'newVisitModal.after' : att.tag === 'PROCEDURE' ? 'attachment.procedure' : 'attachment.document') })} />
                                <span>
                                  {t(att.tag === 'BEFORE' ? 'newVisitModal.before' : att.tag === 'AFTER' ? 'newVisitModal.after' : att.tag === 'PROCEDURE' ? 'attachment.procedure' : 'attachment.document')}
                                </span>
                              </div>
                            ))}
                          </div>
                        </div>
                      )}

                      {photoVisitId === visit.id ? (
                        <div style={{ marginTop: '14px', padding: '12px', border: '1px dashed var(--border-color)', borderRadius: '10px', display: 'flex', flexDirection: 'column', gap: '10px' }}>
                          <span style={{ fontSize: '12px', color: 'var(--text-muted)', fontWeight: 600 }}>{t('photoPicker.photoType')}</span>
                          <div role="group" aria-label={t('photoPicker.photoType')} style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                            {(['BEFORE', 'AFTER', 'PROCEDURE'] as const).map(tag => (
                              <button
                                key={tag}
                                type="button"
                                aria-pressed={photoTag === tag}
                                className={photoTag === tag ? 'btn-rose' : 'btn-secondary'}
                                style={{ padding: '6px 12px', fontSize: '12px' }}
                                disabled={busy || isEditingFields}
                                onClick={() => setPhotoTag(tag)}
                              >
                                {t(tag === 'BEFORE' ? 'newVisitModal.before' : tag === 'AFTER' ? 'newVisitModal.after' : 'attachment.procedure')}
                              </button>
                            ))}
                          </div>
                          {uploadingPhoto ? (
                            <span role="status" style={{ fontSize: '12px', color: 'var(--text-muted)' }}>{t('photoPicker.uploading')}</span>
                          ) : (
                            <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', alignItems: 'center' }}>
                              <PhotoSourcePicker onFile={(file) => handleAddPhoto(visit.id, file)} />
                              <button type="button" className="btn-secondary" style={{ padding: '8px 12px', fontSize: '12px' }} onClick={() => setPhotoVisitId(null)}>
                                {t('newVisitModal.cancel')}
                              </button>
                            </div>
                          )}
                        </div>
                      ) : (
                        <button
                          type="button"
                          className="btn-secondary"
                          style={{ marginTop: '14px', padding: '6px 12px', fontSize: '12px' }}
                          disabled={busy || isEditingFields}
                          onClick={() => openAddPhoto(visit.id, attachments)}
                        >
                          <ImagePlus size={14} /> {t('photoPicker.addPhoto')}
                        </button>
                      )}
                    </article>
                  );
                })}
              </div>
            )}
          </div>

        </div>
      </div>

      {/* Edit Client Modal */}
      {selectedPhoto && <PhotoViewerModal key={selectedPhoto.id} attachment={selectedPhoto} onClose={() => setSelectedPhoto(null)} />}
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
