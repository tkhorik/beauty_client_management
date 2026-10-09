export type NativeAppPlatform = 'android' | 'ios' | null;

const EMBEDDED_BROWSER = /;\s*wv\)|\bFBAN\b|\bFBAV\b|Instagram|Line\/|TikTok|Snapchat/i;

export function detectNativeAppPlatform(
  userAgent: string,
  platform: string,
  maxTouchPoints: number,
  standalone: boolean,
): NativeAppPlatform {
  if (standalone || EMBEDDED_BROWSER.test(userAgent)) return null;

  if (/Android/i.test(userAgent)) return 'android';
  if (/iPhone|iPad|iPod/i.test(userAgent)) return 'ios';
  // iPadOS can request the desktop Safari user agent.
  if (platform === 'MacIntel' && maxTouchPoints > 1) return 'ios';
  return null;
}

export function isStandaloneDisplayMode(): boolean {
  return window.matchMedia?.('(display-mode: standalone)').matches === true
    || (navigator as Navigator & { standalone?: boolean }).standalone === true;
}

export function buildAndroidAppIntent(
  currentUrl: string,
  packageId: string,
  fallbackUrl: string,
): string {
  const current = new URL(currentUrl);
  const scheme = current.protocol.replace(':', '');
  const pathAndQuery = `${current.pathname}${current.search}`;
  return `intent://${current.host}${pathAndQuery}#Intent;scheme=${scheme};package=${packageId};S.browser_fallback_url=${encodeURIComponent(fallbackUrl)};end`;
}

export const ANDROID_PACKAGE_ID = import.meta.env.VITE_ANDROID_PACKAGE_ID || 'com.beauty.app';
export const ANDROID_DOWNLOAD_URL = import.meta.env.VITE_ANDROID_DOWNLOAD_URL
  || 'https://github.com/tkhorik/beauty_client_management/releases/latest';

/** Bearer-token pages stay in the browser; they must not be forwarded by the banner. */
export function isSensitiveAppLink(url: URL): boolean {
  return url.pathname.replace(/\/+$/, '') === '/reset-password'
    || new URLSearchParams(url.search).has('orgToken');
}
