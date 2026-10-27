// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

let originalMatchMedia: typeof window.matchMedia;

function matchMedia(standalone = false) {
  return vi.fn().mockImplementation((query: string) => ({
    matches: query === '(display-mode: standalone)' && standalone,
    addEventListener: vi.fn(), removeEventListener: vi.fn(),
  }));
}

async function subject(standalone = false) {
  vi.resetModules();
  window.matchMedia = matchMedia(standalone);
  return import('./install');
}

beforeEach(() => { originalMatchMedia = window.matchMedia; });
afterEach(() => { window.matchMedia = originalMatchMedia; vi.restoreAllMocks(); });

it('captures the Chromium prompt early, prevents the default prompt, and consumes it once', async () => {
  const install = await subject();
  install.initInstallPrompt();
  const prompt = vi.fn().mockResolvedValue(undefined);
  const event = new Event('beforeinstallprompt', { cancelable: true }) as Event & { prompt: typeof prompt; userChoice: Promise<{ outcome: 'accepted'; platform: string }> };
  event.prompt = prompt;
  event.userChoice = Promise.resolve({ outcome: 'accepted', platform: 'web' });
  window.dispatchEvent(event);
  expect(event.defaultPrevented).toBe(true);
  expect(install.installCapability()).toBe('prompt');
  await expect(install.showInstallPrompt()).resolves.toBe('accepted');
  expect(prompt).toHaveBeenCalledOnce();
  expect(install.installCapability()).toBe('none');
  await expect(install.showInstallPrompt()).resolves.toBe('unavailable');
});

it('reports dismissal and suppresses controls after installation or standalone launch', async () => {
  const install = await subject();
  install.initInstallPrompt();
  const event = new Event('beforeinstallprompt', { cancelable: true }) as Event & { prompt: () => Promise<void>; userChoice: Promise<{ outcome: 'dismissed'; platform: string }> };
  event.prompt = vi.fn().mockResolvedValue(undefined);
  event.userChoice = Promise.resolve({ outcome: 'dismissed', platform: 'web' });
  window.dispatchEvent(event);
  await expect(install.showInstallPrompt()).resolves.toBe('dismissed');
  window.dispatchEvent(new Event('appinstalled'));
  expect(install.installCapability()).toBe('none');
  const standalone = await subject(true);
  expect(standalone.installCapability()).toBe('none');
});

it('treats a browser rejection of a stale prompt as unavailable', async () => {
  const install = await subject();
  install.initInstallPrompt();
  const event = new Event('beforeinstallprompt', { cancelable: true }) as Event & { prompt: () => Promise<void>; userChoice: Promise<{ outcome: 'dismissed'; platform: string }> };
  event.prompt = vi.fn().mockRejectedValue(new Error('stale prompt'));
  event.userChoice = Promise.resolve({ outcome: 'dismissed', platform: 'web' });
  window.dispatchEvent(event);
  await expect(install.showInstallPrompt()).resolves.toBe('unavailable');
  expect(install.installCapability()).toBe('none');
});
