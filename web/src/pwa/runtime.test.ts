// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { RegisterSWOptions } from 'vite-plugin-pwa/types';

const mocks = vi.hoisted(() => ({ register: vi.fn(), update: vi.fn() }));
vi.mock('virtual:pwa-register', () => ({ registerSW: mocks.register }));

beforeEach(() => {
  vi.resetModules();
  vi.stubEnv('PROD', true);
  vi.stubGlobal('navigator', { serviceWorker: {} });
  mocks.update.mockReset().mockResolvedValue(undefined);
  mocks.register.mockReset().mockReturnValue(mocks.update);
});
afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it('registers once and treats registration failure as optional', async () => {
  mocks.register.mockImplementationOnce(() => { throw new Error('denied'); });
  const runtime = await import('./runtime');
  expect(() => runtime.initServiceWorker()).not.toThrow();
  runtime.initServiceWorker();
  expect(mocks.register).toHaveBeenCalledOnce();
  expect(runtime.getUpdateAvailable()).toBe(false);
});

it('does not register during development or without service-worker support', async () => {
  vi.stubEnv('PROD', false);
  let runtime = await import('./runtime');
  runtime.initServiceWorker();
  vi.resetModules();
  vi.stubEnv('PROD', true);
  vi.stubGlobal('navigator', {});
  runtime = await import('./runtime');
  runtime.initServiceWorker();
  expect(mocks.register).not.toHaveBeenCalled();
});

it('keeps an unconsenting tab open when another tab activates an update', async () => {
  const reload = vi.fn();
  vi.stubGlobal('window', { location: { reload } });
  const runtime = await import('./runtime');
  runtime.initServiceWorker();
  const options = mocks.register.mock.calls[0][0] as RegisterSWOptions;
  const listener = vi.fn();
  const unsubscribe = runtime.subscribeUpdates(listener);
  options.onNeedRefresh?.();
  expect(runtime.getUpdateAvailable()).toBe(true);
  options.onNeedReload?.();
  expect(reload).not.toHaveBeenCalled();
  expect(listener).toHaveBeenCalled();
  await runtime.applyUpdate();
  expect(reload).toHaveBeenCalledOnce();
  unsubscribe();
});

it('activates only after consent and reloads the consenting tab', async () => {
  const reload = vi.fn();
  vi.stubGlobal('window', { location: { reload } });
  const runtime = await import('./runtime');
  runtime.initServiceWorker();
  const options = mocks.register.mock.calls[0][0] as RegisterSWOptions;
  options.onNeedRefresh?.();
  expect(mocks.update).not.toHaveBeenCalled();
  await runtime.applyUpdate();
  expect(mocks.update).toHaveBeenCalledOnce();
  options.onNeedReload?.();
  expect(reload).toHaveBeenCalledOnce();
});
