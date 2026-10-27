// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { LocaleProvider } from '../i18n/LocaleProvider';

let root: Root;
let host: HTMLDivElement;
let originalUserAgent: PropertyDescriptor | undefined;
let originalPlatform: PropertyDescriptor | undefined;
let originalMaxTouchPoints: PropertyDescriptor | undefined;

async function renderIos() {
  vi.resetModules();
  const { PwaInstallButton } = await import('./PwaInstallButton');
  await act(async () => root.render(<LocaleProvider><PwaInstallButton /></LocaleProvider>));
}

beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  originalUserAgent = Object.getOwnPropertyDescriptor(Navigator.prototype, 'userAgent');
  originalPlatform = Object.getOwnPropertyDescriptor(Navigator.prototype, 'platform');
  originalMaxTouchPoints = Object.getOwnPropertyDescriptor(Navigator.prototype, 'maxTouchPoints');
  Object.defineProperties(Navigator.prototype, {
    userAgent: { configurable: true, value: 'Mozilla/5.0 (iPhone)' },
    platform: { configurable: true, value: 'iPhone' }, maxTouchPoints: { configurable: true, value: 1 },
  });
  window.matchMedia = vi.fn().mockReturnValue({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() });
  localStorage.setItem('beauty:locale-preference', 'en');
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount()); host.remove(); vi.restoreAllMocks();
  if (originalUserAgent) Object.defineProperty(Navigator.prototype, 'userAgent', originalUserAgent);
  if (originalPlatform) Object.defineProperty(Navigator.prototype, 'platform', originalPlatform);
  if (originalMaxTouchPoints) Object.defineProperty(Navigator.prototype, 'maxTouchPoints', originalMaxTouchPoints);
});

it('shows accessible iPhone Home Screen instructions and supports Escape dismissal', async () => {
  await renderIos();
  await act(async () => host.querySelector<HTMLButtonElement>('button')!.click());
  const dialog = host.querySelector<HTMLElement>('[role="dialog"]')!;
  expect(dialog.textContent).toContain('Add to Home Screen');
  expect(dialog.getAttribute('aria-modal')).toBe('true');
  expect(document.activeElement).toBe(dialog);
  await act(async () => window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true })));
  expect(host.querySelector('[role="dialog"]')).toBeNull();
});
