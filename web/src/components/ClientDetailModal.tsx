import { useAppTranslation, useLocale } from '../i18n/LocaleProvider';
import React, { useState } from 'react';
import type { Client, Visit, Attachment } from '../types';
import { X, Calendar, Clock, Plus, Trash2, Edit3, Edit2, Camera, FileText, Sliders, ImagePlus } from 'lucide-react';
import { AttributeEditor } from './AttributeEditor';
import { duplicateAttributeKey, toAttributeRecord, toAttributeRows, type AttributeRow } from './attributes';
import { api, writeErrorMessage } from '../services/api';
import { EditClientModal } from './EditClientModal';
import { PhotoSourcePicker } from './PhotoSourcePicker';
import { compressImage } from '../utils/imageCompressor';
import { defaultPhotoTag } from '../utils/photoTags';

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
  const { t } = useAppTranslation();
  const { formatDate } = useLocale();
  // Null while viewing. The draft is seeded from the current record each time
  // editing starts, so Cancel discards it and a refreshed record is never
  // overwritten by values captured when the modal opened.
  const [draftFields, setDraftFields] = useState<AttributeRow[] | null>(null);
  const isEditingFields = draftFields !== null;
  const [isSaving, setIsSaving] = useState(false);
  const [isEditClientOpen, setIsEditClientOpen] = useState(false);
  const savedFields = Object.entries(client.customFields ?? {});
  // Adding a photo to a visit that was already logged, e.g. the AFTER shot once the procedure is done.
  const [photoVisitId, setPhotoVisitId] = useState<string | null>(null);
  const [photoTag, setPhotoTag] = useState<Attachment['tag']>('BEFORE');
  const [uploadingPhoto, setUploadingPhoto] = useState(false);

  const openAddPhoto = (visitId: string, attachments: Attachment[]) => {
    setPhotoVisitId(visitId);
    setPhotoTag(defaultPhotoTag(attachments));
  };

  const handleAddPhoto = async (visitId: string, file: File) => {
    setUploadingPhoto(true);
    try {
      const compressedDataUrl = await compressImage(file, 1200, 0.85);
      await api.addAttachment(visitId, compressedDataUrl, photoTag);
      setPhotoVisitId(null);
      onRefresh();
    } catch (err) {
      alert(writeErrorMessage(err, t('photoPicker.failedToAddPhoto')));
    } finally {
      setUploadingPhoto(false);
    }
  };

  const handleSaveFields = async () => {
    if (!draftFields) return;
    const duplicate = duplicateAttributeKey(draftFields);
    if (duplicate) {
      alert(t('attributes.duplicateKey', { key: duplicate }));
      return;
    }
    setIsSaving(true);
    try {
      await api.updateClient(client.id, { customFields: toAttributeRecord(draftFields) });
      setDraftFields(null);
      onRefresh();
    } catch (err) {
      alert(writeErrorMessage(err,t('clientDetailModal.failedToSaveCustomFields')));
    } finally {
      setIsSaving(false);
    }
  };

  const handleDeleteClient = async () => {
    if (confirm(t('clientDetailModal.confirmDelete', { name: client.name }))) {
      try {
        await api.deleteClient(client.id);
      } catch (err) {
        // Previously unguarded, which was survivable only while every failure
        // fell back to localStorage and "succeeded". A refused delete now
        // throws, and without this the modal would close as if the record were
        // gone while the server still has it.
        alert(writeErrorMessage(err,t('clientDetailModal.failedToDeleteThisClient')));
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
                {client.totalVisits} {t('clientDetailModal.totalVisits')}
              </span>
            </div>
            <p style={{ color: 'var(--text-muted)', fontSize: '13px', marginTop: '4px' }}>
              {t('clientDetailModal.phone')} <strong style={{ color: '#fff' }}>{client.phone}</strong> {client.email && `| ${t('auth.email')}: ${client.email}`}
            </p>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <button className="btn-secondary" onClick={() => setIsEditClientOpen(true)} style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <Edit2 size={16} /> {t('clientDetailModal.editClient')}
            </button>
            <button className="btn-rose" onClick={() => onOpenNewVisit(client)}>
              <Plus size={16} /> {t('clientDetailModal.logNewVisit')}
            </button>
            <button aria-label={t('common.delete')} onClick={handleDeleteClient} style={{ background: 'rgba(239,68,68,0.15)', color: '#f87171', border: '1px solid rgba(239,68,68,0.3)', padding: '10px 14px', borderRadius: 'var(--radius-sm)', cursor: 'pointer' }}>
              <Trash2 size={16} />
            </button>
            <button aria-label={t('common.close')} onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}>
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
                <Sliders size={18} /> {t('clientDetailModal.dynamicCustomClientAttributesJSONB')}
              </h3>
              {!isEditingFields ? (
                <button className="btn-secondary" style={{ padding: '4px 10px', fontSize: '12px' }} onClick={() => setDraftFields(toAttributeRows(client.customFields))}>
                  <Edit3 size={14} /> {t('clientDetailModal.editAttributes')}
                </button>
              ) : (
                <div style={{ display: 'flex', gap: '8px' }}>
                  <button className="btn-rose" style={{ padding: '4px 12px', fontSize: '12px' }} onClick={handleSaveFields} disabled={isSaving}>
                    {isSaving ?t('clientDetailModal.saving') :t('clientDetailModal.saveChanges')}
                  </button>
                  <button className="btn-secondary" style={{ padding: '4px 10px', fontSize: '12px' }} disabled={isSaving} onClick={() => setDraftFields(null)}>
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
                      <span style={{ fontSize: '14px', color: '#fff', fontWeight: 600, overflowWrap: 'anywhere' }}>{String(value)}</span>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Chronological Visit History Timeline */}
          <div>
            <h3 style={{ fontSize: '18px', color: '#fff', marginBottom: '16px', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <Calendar size={20} color="var(--rose-gold-primary)" /> {t('clientDetailModal.visitHistoryAndProcedureTimeline')}
            </h3>

            {visits.length === 0 ? (
              <div className="glass-panel" style={{ padding: '30px', textAlign: 'center', color: 'var(--text-muted)' }}>
                <FileText size={32} style={{ marginBottom: '10px', opacity: 0.5 }} />
                <p>{t('clientDetailModal.noVisitLogsFoundForThisClientYet')}</p>
                <button className="btn-rose" style={{ marginTop: '14px' }} onClick={() => onOpenNewVisit(client)}>
                  <Plus size={16} /> {t('clientDetailModal.logFirstVisit')}
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
                              {formatDate(visit.visitDateTime, { weekday: 'short', year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
                            </span>
                            <span className={`tag-badge ${statusClass}`}>{t(visit.status === 'COMPLETED' ? 'newVisitModal.cOMPLETED' : visit.status === 'SCHEDULED' ? 'newVisitModal.sCHEDULED' : 'newVisitModal.cANCELLED')}</span>
                          </div>
                          <span style={{ fontSize: '12px', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: '4px', marginTop: '4px' }}>
                            <Clock size={12} /> {t('clientDetailModal.duration')} {t('counts.minutes', { count: visit.durationMinutes })}
                          </span>
                        </div>

                        {hasBeforeAfter && (
                          <button className="btn-rose" style={{ padding: '6px 12px', fontSize: '12px' }} onClick={() => onOpenPhotoCompare(attachments)}>
                            <Camera size={14} /> {t('clientDetailModal.compareBeforeAfter')}
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
                          <p style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '8px', fontWeight: 600 }}>{t('clientDetailModal.attachmentCount', { count: attachments.length })}</p>
                          <div style={{ display: 'flex', gap: '12px', flexWrap: 'wrap' }}>
                            {attachments.map(att => (
                              <div key={att.id} style={{ position: 'relative', width: '100px', height: '100px', borderRadius: '10px', overflow: 'hidden', border: '1px solid var(--border-color)' }}>
                                <img src={att.fileUrl} alt={att.caption || t('clientDetailModal.attachment')} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                                <span style={{ position: 'absolute', bottom: '4px', left: '4px', background: 'rgba(0,0,0,0.8)', color: att.tag === 'BEFORE' ? 'var(--rose-gold-primary)' : att.tag === 'AFTER' ? '#2dd4bf' : '#fff', padding: '2px 6px', borderRadius: '6px', fontSize: '9px', fontWeight: 700 }}>
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
                                disabled={uploadingPhoto}
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
                          disabled={uploadingPhoto}
                          onClick={() => openAddPhoto(visit.id, attachments)}
                        >
                          <ImagePlus size={14} /> {t('photoPicker.addPhoto')}
                        </button>
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
