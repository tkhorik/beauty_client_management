import { useId } from 'react';
import { X } from 'lucide-react';
import type { Attachment } from '../types';
import { useAppTranslation } from '../i18n/useLocale';
import { useAttachmentImage } from './useAttachmentImage';
import { useDismiss } from './useDismiss';
import './ClientForms.css';

export function PhotoViewerModal({ attachment, onClose }: { attachment: Attachment; onClose: () => void }) {
  const { t } = useAppTranslation();
  const id = useId();
  const dismiss = useDismiss(onClose);
  const image = useAttachmentImage(attachment.fileUrl);
  const tag = t(attachment.tag === 'BEFORE' ? 'newVisitModal.before' : attachment.tag === 'AFTER'
    ? 'newVisitModal.after' : attachment.tag === 'PROCEDURE' ? 'attachment.procedure' : 'attachment.document');

  return <div className="dialog-overlay photo-viewer-overlay" {...dismiss}>
    <section role="dialog" aria-modal="true" aria-labelledby={`${id}-title`} tabIndex={-1}
      className="dialog-panel client-dialog photo-viewer">
      <header className="dialog-header">
        <h2 id={`${id}-title`} className="dialog-title">{t('webUx.photoViewer')}</h2>
        <button type="button" className="close-button" aria-label={t('common.close')} onClick={onClose}><X size={24} /></button>
      </header>
      <div className="photo-viewer-stage">
        {image.failed ? <div className="attachment-feedback" role="status">
          <p>{t('attachment.unavailable')}</p>
          <button type="button" onClick={image.retry}>{t('clientDetailModal.retryVisits')}</button>
        </div> : !image.src ? <p role="status">{t('common.loading')}</p>
          : <img src={image.src} alt={attachment.caption || tag} onError={image.onError} />}
      </div>
      <footer className="photo-viewer-caption">
        <strong>{tag}</strong>
        {attachment.caption && <p>{attachment.caption}</p>}
      </footer>
    </section>
  </div>;
}
