import { useAppTranslation } from '../i18n/useLocale';
import { useAttachmentImage } from './useAttachmentImage';

export function AttachmentImage({ fileUrl, alt, onOpen, openLabel }: {
  fileUrl: string; alt: string; onOpen?: () => void; openLabel?: string;
}) {
  const { t } = useAppTranslation();
  const image = useAttachmentImage(fileUrl);
  if (onOpen) return <>
    <button type="button" className="attachment-open" aria-label={openLabel} onClick={onOpen}>
      {image.failed ? <div className="attachment-feedback">{t('attachment.unavailable')}</div>
        : !image.src ? <div className="attachment-feedback" role="status">{t('common.loading')}</div>
        : <img src={image.src} alt={alt} onError={image.onError} loading="lazy" decoding="async" />}
    </button>
    {image.failed && <div className="attachment-feedback">
      <button type="button" onClick={image.retry}>{t('clientDetailModal.retryVisits')}</button>
    </div>}
  </>;
  if (image.failed) return (
    <div className="attachment-feedback">
      <p>{t('attachment.unavailable')}</p>
      <button type="button" onClick={image.retry}>{t('clientDetailModal.retryVisits')}</button>
    </div>
  );
  if (!image.src) return <div className="attachment-feedback" role="status">{t('common.loading')}</div>;
  return <img src={image.src} alt={alt} onError={image.onError} loading="lazy" decoding="async" />;
}
