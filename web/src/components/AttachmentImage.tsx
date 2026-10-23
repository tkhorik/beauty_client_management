import { useAppTranslation } from '../i18n/LocaleProvider';
import { useAttachmentImage } from './useAttachmentImage';

export function AttachmentImage({ fileUrl, alt }: { fileUrl: string; alt: string }) {
  const { t } = useAppTranslation();
  const image = useAttachmentImage(fileUrl);
  if (image.failed) return (
    <div className="attachment-feedback">
      <p>{t('attachment.unavailable')}</p>
      <button type="button" onClick={image.retry}>{t('clientDetailModal.retryVisits')}</button>
    </div>
  );
  if (!image.src) return <div className="attachment-feedback" role="status">{t('common.loading')}</div>;
  return <img src={image.src} alt={alt} onError={image.onError} loading="lazy" decoding="async" />;
}
