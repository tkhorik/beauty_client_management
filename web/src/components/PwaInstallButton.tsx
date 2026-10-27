import { useEffect, useId, useState } from 'react';
import { Download, Share } from 'lucide-react';
import { useAppTranslation } from '../i18n/useLocale';
import { initInstallPrompt, showInstallPrompt, useInstallCapability } from '../pwa/install';
import { isSensitiveAppLink } from '../nativeAppBanner';
import { useDismiss } from './useDismiss';

function IosInstallGuide({ onClose, title, steps, hint, close }: { onClose: () => void; title: string; steps: string; hint: string; close: string }) {
  const id = useId();
  const dismiss = useDismiss(onClose);
  return <div className="dialog-overlay" {...dismiss}>
    <section className="dialog-panel client-dialog pwa-install-guide" role="dialog" aria-modal="true" aria-labelledby={`${id}-title`} tabIndex={-1} onClick={event => event.stopPropagation()}>
      <div className="dialog-header"><h2 id={`${id}-title`} className="dialog-title">{title}</h2><button className="close-button" type="button" aria-label={close} onClick={onClose}>×</button></div>
      <div className="dialog-body dialog-stack"><p>{steps}</p><p className="pwa-install-guide__hint">{hint}</p><button type="button" className="btn-secondary" onClick={onClose}>{close}</button></div>
    </section>
  </div>;
}

/** An explicit install action; iOS exposes Home Screen installation through its share sheet. */
export function PwaInstallButton({ className = 'native-app-banner__button' }: { className?: string }) {
  useEffect(() => { initInstallPrompt(); }, []);
  const { t } = useAppTranslation();
  const capability = useInstallCapability();
  const [showIosGuide, setShowIosGuide] = useState(false);

  // Embedded webviews cannot expose the Home Screen action. Password-reset
  // and invitation URLs must stay in a normal browser, not an installed shell.
  const embedded = /;\s*wv\)|\bFBAN\b|\bFBAV\b|Instagram|Line\/|TikTok|Snapchat/i.test(navigator.userAgent);
  if (capability === 'none' || embedded || isSensitiveAppLink(new URL(window.location.href))) return null;
  if (capability === 'ios') {
    return <>
      <button type="button" className={className} onClick={() => setShowIosGuide(true)}>
        <Share size={16} aria-hidden="true" />{t('pwa.install')}
      </button>
      {showIosGuide && <IosInstallGuide onClose={() => setShowIosGuide(false)} title={t('pwa.iosTitle')} steps={t('pwa.iosSteps')} hint={t('pwa.iosSafari')} close={t('common.close')} />}
    </>;
  }
  return <button type="button" className={className} onClick={() => void showInstallPrompt()}><Download size={16} aria-hidden="true" />{t('pwa.install')}</button>;
}
