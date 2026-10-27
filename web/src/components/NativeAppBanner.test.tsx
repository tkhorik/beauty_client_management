// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { LocaleProvider } from '../i18n/LocaleProvider';

let root: Root;
let host: HTMLDivElement;
let originals: Record<string, PropertyDescriptor | undefined>;

beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  originals = Object.fromEntries(['userAgent', 'platform', 'maxTouchPoints'].map(key => [key, Object.getOwnPropertyDescriptor(Navigator.prototype, key)]));
  Object.defineProperties(Navigator.prototype, {
    userAgent: { configurable: true, value: 'Mozilla/5.0 (Linux; Android 14)' },
    platform: { configurable: true, value: 'Linux armv8l' }, maxTouchPoints: { configurable: true, value: 1 },
  });
  window.matchMedia = vi.fn().mockReturnValue({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() });
  localStorage.clear(); localStorage.setItem('beauty:locale-preference', 'en');
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount()); host.remove(); vi.restoreAllMocks();
  for (const [key, descriptor] of Object.entries(originals)) if (descriptor) Object.defineProperty(Navigator.prototype, key, descriptor);
});

it('offers web installation alongside, not instead of, native Android open and download actions', async () => {
  vi.resetModules();
  const { NativeAppBanner } = await import('./NativeAppBanner');
  await act(async () => root.render(<LocaleProvider><NativeAppBanner /></LocaleProvider>));
  const event = new Event('beforeinstallprompt', { cancelable: true }) as Event & { prompt: () => Promise<void>; userChoice: Promise<{ outcome: 'dismissed'; platform: string }> };
  event.prompt = vi.fn().mockResolvedValue(undefined);
  event.userChoice = Promise.resolve({ outcome: 'dismissed', platform: 'web' });
  await act(async () => window.dispatchEvent(event));
  expect(event.defaultPrevented).toBe(true);
  expect(host.textContent).toContain('Install web app');
  expect(host.textContent).toContain('Open app');
  expect(host.textContent).toContain('Download app');
  expect(host.querySelector('a[href^="intent://"]')).not.toBeNull();
  expect(host.querySelector('a[href*="github.com"]')).not.toBeNull();
});

it('hides the whole promotion as soon as the web app is installed', async () => {
  vi.resetModules();
  const { NativeAppBanner } = await import('./NativeAppBanner');
  await act(async () => root.render(<LocaleProvider><NativeAppBanner /></LocaleProvider>));
  expect(host.querySelector('.native-app-banner')).not.toBeNull();
  await act(async () => window.dispatchEvent(new Event('appinstalled')));
  expect(host.querySelector('.native-app-banner')).toBeNull();
});
