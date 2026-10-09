import { useState } from 'react';
import { Download, ExternalLink, Smartphone, X } from 'lucide-react';
import { useAppTranslation } from '../i18n/LocaleProvider';
import {
  ANDROID_DOWNLOAD_URL,
  ANDROID_PACKAGE_ID,
  buildAndroidAppIntent,
  detectNativeAppPlatform,
  isSensitiveAppLink,
  isStandaloneDisplayMode,
} from '../nativeAppBanner';

const CAMPAIGN_VERSION = 'v1';

function platformForBrowser() {
  return detectNativeAppPlatform(
    navigator.userAgent,
    navigator.platform,
    navigator.maxTouchPoints,
    isStandaloneDisplayMode(),
  );
}

function dismissalKey(platform: 'android' | 'ios') {
  return `beauty:native-app-banner:${CAMPAIGN_VERSION}:${platform}`;
}

function wasDismissed(platform: 'android' | 'ios') {
  try {
    return window.localStorage.getItem(dismissalKey(platform)) === 'dismissed';
  } catch {
    return false;
  }
}

export function NativeAppBanner() {
  const { t } = useAppTranslation();
  const [platform] = useState(platformForBrowser);
  const [dismissed, setDismissed] = useState(() => platform ? wasDismissed(platform) : false);

  if (!platform || dismissed || isSensitiveAppLink(new URL(window.location.href))) return null;

  const dismiss = () => {
    try {
      window.localStorage.setItem(dismissalKey(platform), 'dismissed');
    } catch {
      // Dismissal still applies for this page view when storage is unavailable.
    }
    setDismissed(true);
  };

  const openAppUrl = platform === 'android'
    ? buildAndroidAppIntent(window.location.href, ANDROID_PACKAGE_ID, ANDROID_DOWNLOAD_URL)
    : undefined;

  return (
    <aside className="native-app-banner" aria-label={t('nativeAppBanner.label')}>
      <Smartphone className="native-app-banner__icon" size={22} aria-hidden="true" />
      <div className="native-app-banner__copy">
        <strong>{t(platform === 'android' ? 'nativeAppBanner.androidTitle' : 'nativeAppBanner.iosTitle')}</strong>
        <span>{t(platform === 'android' ? 'nativeAppBanner.androidDescription' : 'nativeAppBanner.iosDescription')}</span>
      </div>
      {platform === 'android' && (
        <div className="native-app-banner__actions">
          <a className="native-app-banner__button native-app-banner__button--primary" href={openAppUrl}>
            <ExternalLink size={16} aria-hidden="true" />
            {t('nativeAppBanner.openApp')}
          </a>
          <a className="native-app-banner__button" href={ANDROID_DOWNLOAD_URL} target="_blank" rel="noopener noreferrer">
            <Download size={16} aria-hidden="true" />
            {t('nativeAppBanner.downloadApp')}
          </a>
        </div>
      )}
      <button
        className="native-app-banner__dismiss"
        type="button"
        onClick={dismiss}
        aria-label={t('nativeAppBanner.dismiss')}
      >
        <X size={20} aria-hidden="true" />
      </button>
    </aside>
  );
}
