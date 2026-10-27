import { useEffect, useState, useSyncExternalStore } from 'react';
import { useAppTranslation } from '../i18n/useLocale';
import { applyUpdate, getUpdateAvailable, subscribeUpdates } from '../pwa/runtime';
import './PwaStatus.css';

export function PwaStatus() {
  const { t } = useAppTranslation();
  const [offline, setOffline] = useState(() => !navigator.onLine);
  const [confirmUpdate, setConfirmUpdate] = useState(false);
  const [failed, setFailed] = useState(false);
  const needsUpdate = useSyncExternalStore(subscribeUpdates, getUpdateAvailable, () => false);
  useEffect(() => {
    const changed = () => setOffline(!navigator.onLine);
    window.addEventListener('online', changed);
    window.addEventListener('offline', changed);
    return () => { window.removeEventListener('online', changed); window.removeEventListener('offline', changed); };
  }, []);
  return <div className="pwa-status">
    {offline && <aside className="notice" role="status">{t('pwa.offline')}</aside>}
    {needsUpdate && <aside className="notice" role="status">
      <span>{t(confirmUpdate ? 'pwa.updateConfirm' : 'pwa.updateAvailable')}</span>
      <button type="button" className="btn-secondary" disabled={offline} onClick={() => {
        if (!confirmUpdate) { setConfirmUpdate(true); return; }
        void applyUpdate().catch(() => setFailed(true));
      }}>{t(confirmUpdate ? 'pwa.reload' : 'pwa.update')}</button>
      {confirmUpdate && <button type="button" className="btn-secondary" onClick={() => setConfirmUpdate(false)}>{t('pwa.later')}</button>}
      {failed && <span>{t('pwa.updateFailed')}</span>}
    </aside>}
  </div>;
}
