// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from '../auth/AuthContext';
import { api, ApiError } from '../services/api';
import type { UserProfile } from '../types';
import { LocaleProvider, useLocale } from './LocaleProvider';
import { LanguageAccountSync } from './LanguageAccountSync';
import { effectiveAcceptLanguage, type LanguagePreference } from './store';

vi.mock('../auth/session', () => ({ restoreSession: vi.fn(async () => null), endSession: vi.fn(async () => {}) }));

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function profile(id = 'a', preference: LanguagePreference = 'en', revision = 0): UserProfile {
  return { id, email: `${id}@example.com`, fullName: id, createdAt: '2026-01-01', languagePreference: preference, languageRevision: revision };
}
let auth: ReturnType<typeof useAuth>;
let locale: ReturnType<typeof useLocale>;
function Probe() {
  auth = useAuth();
  locale = useLocale();
  return <output>{auth.user?.id}:{locale.locale}:{auth.user?.languageRevision}</output>;
}
let root: Root;
let host: HTMLDivElement;
const pending = (id = 'a') => JSON.parse(localStorage.getItem(`beauty:locale-pending:${id}`) ?? 'null');
async function choose(value: LanguagePreference) { await act(async () => locale.setPreference(value)); }
async function event(name: string) { await act(async () => { window.dispatchEvent(new Event(name)); }); }
async function login(user = profile()) { await act(async () => auth.login('test-only-token', user)); }

beforeEach(async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  localStorage.clear();
  localStorage.setItem('beauty:locale-preference', 'en');
  vi.spyOn(api, 'getCurrentUser').mockResolvedValue(profile());
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  await act(async () => root.render(<LocaleProvider><AuthProvider><LanguageAccountSync /><Probe /></AuthProvider></LocaleProvider>));
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); vi.restoreAllMocks(); });

describe('account language synchronization through mounted providers', () => {
  it('keeps the latest rapid selection and rebases it after an earlier PUT', async () => {
    const first = deferred<{ preference: LanguagePreference; revision: number }>();
    const second = deferred<{ preference: LanguagePreference; revision: number }>();
    const put = vi.spyOn(api, 'updateLanguagePreference').mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    await login(profile('a', 'system', 4));
    await choose('en');
    await choose('ru');
    expect(locale.locale).toBe('ru');
    expect(put).toHaveBeenCalledTimes(1);
    await act(async () => first.resolve({ preference: 'en', revision: 5 }));
    expect(put).toHaveBeenNthCalledWith(2, 'ru', 5, 'a');
    expect(locale.locale).toBe('ru');
    await act(async () => second.resolve({ preference: 'ru', revision: 6 }));
    expect(pending()).toBeNull();
    expect(auth.user?.languageRevision).toBe(6);
  });

  it('starts a new account request even while the former account request never settles', async () => {
    const old = deferred<{ preference: LanguagePreference; revision: number }>();
    const put = vi.spyOn(api, 'updateLanguagePreference').mockReturnValueOnce(old.promise).mockResolvedValueOnce({ preference: 'en', revision: 1 });
    await login();
    await choose('ru');
    await login(profile('b', 'ru'));
    await choose('en');
    expect(put).toHaveBeenCalledTimes(2);
    expect(put).toHaveBeenNthCalledWith(1, 'ru', 0, 'a');
    expect(put).toHaveBeenNthCalledWith(2, 'en', 0, 'b');
    expect(auth.user?.id).toBe('b');
    expect(auth.user?.languageRevision).toBe(1);
    await act(async () => old.resolve({ preference: 'ru', revision: 1 }));
    expect(auth.user?.id).toBe('b');
    expect(locale.locale).toBe('en');
    expect(pending('a')?.preference).toBe('ru');
    expect(pending('b')).toBeNull();
  });

  it('does not accept a stale response after logging out and back into the same account', async () => {
    const old = deferred<{ preference: LanguagePreference; revision: number }>();
    const put = vi.spyOn(api, 'updateLanguagePreference').mockReturnValueOnce(old.promise).mockResolvedValueOnce({ preference: 'ru', revision: 3 });
    await login();
    await choose('ru');
    await act(async () => auth.logout());
    expect(locale.preference).toBe('en');
    await login(profile('a', 'en', 2));
    expect(put).toHaveBeenCalledTimes(2);
    await act(async () => old.resolve({ preference: 'en', revision: 1 }));
    expect(auth.user?.languageRevision).toBe(3);
    expect(locale.locale).toBe('ru');
  });

  it('rejects a focus GET older than a completed PUT, including before profile rerender', async () => {
    const get = deferred<UserProfile>();
    vi.mocked(api.getCurrentUser).mockReturnValueOnce(get.promise);
    vi.spyOn(api, 'updateLanguagePreference').mockResolvedValue({ preference: 'ru', revision: 5 });
    await login(profile('a', 'en', 4));
    await event('focus');
    await choose('ru');
    await act(async () => get.resolve(profile('a', 'en', 4)));
    expect(auth.user?.languageRevision).toBe(5);
    expect(locale.locale).toBe('ru');
  });

  it('keeps offline edits for reconnect and uses account locale in request headers, not guest storage', async () => {
    const put = vi.spyOn(api, 'updateLanguagePreference').mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce({ preference: 'ru', revision: 1 });
    await login();
    await choose('ru');
    expect(pending()?.preference).toBe('ru');
    expect(localStorage.getItem('beauty:locale-preference')).toBe('en');
    expect(effectiveAcceptLanguage()).toBe('ru');
    await event('online');
    expect(put).toHaveBeenCalledTimes(2);
    expect(pending()).toBeNull();
    await act(async () => auth.logout());
    expect(effectiveAcceptLanguage()).toBe('en');
  });

  it('applies a newer server preference on conflict and displays a localized notice', async () => {
    vi.spyOn(api, 'updateLanguagePreference').mockRejectedValue(new ApiError(409, { preference: 'ru', revision: 8 }));
    await login();
    await choose('en');
    expect(pending()).toBeNull();
    expect(auth.user?.languageRevision).toBe(8);
    expect(locale.locale).toBe('ru');
    expect(host.querySelector('[role="status"]')?.textContent).toContain('Применена более новая');
  });

  it('rebases a more recent local edit on conflict without discarding it', async () => {
    const first = deferred<{ preference: LanguagePreference; revision: number }>();
    const put = vi.spyOn(api, 'updateLanguagePreference').mockReturnValueOnce(first.promise).mockResolvedValueOnce({ preference: 'ru', revision: 9 });
    await login();
    await choose('en');
    await choose('ru');
    await act(async () => first.reject(new ApiError(409, { preference: 'en', revision: 8 })));
    expect(put).toHaveBeenNthCalledWith(2, 'ru', 8, 'a');
    expect(locale.locale).toBe('ru');
    expect(pending()).toBeNull();
    expect(host.querySelector('[role="status"]')).toBeNull();
  });

  it('applies and synchronizes another tab edit, then reconciles its confirmed server change', async () => {
    vi.spyOn(api, 'updateLanguagePreference').mockResolvedValue({ preference: 'ru', revision: 2 });
    await login(profile('a', 'en', 1));
    localStorage.setItem('beauty:locale-pending:a', JSON.stringify({ id: 'other-tab', preference: 'ru', revision: 1 }));
    await act(async () => window.dispatchEvent(new StorageEvent('storage', { key: 'beauty:locale-pending:a' })));
    expect(locale.locale).toBe('ru');
    expect(pending()).toBeNull();
    vi.mocked(api.getCurrentUser).mockResolvedValue(profile('a', 'en', 3));
    await act(async () => window.dispatchEvent(new StorageEvent('storage', { key: 'beauty:locale-pending:a' })));
    expect(locale.locale).toBe('en');
    expect(auth.user?.languageRevision).toBe(3);
  });

  it('uses cached account preference and revision while the refreshed auth profile is older', async () => {
    localStorage.setItem('beauty:locale-account:a', JSON.stringify({ preference: 'ru', revision: 7 }));
    const put = vi.spyOn(api, 'updateLanguagePreference').mockResolvedValue({ preference: 'en', revision: 8 });
    await login(profile('a', 'en', 4));
    expect(locale.locale).toBe('ru');
    await choose('en');
    expect(put).toHaveBeenCalledWith('en', 7, 'a');
    expect(auth.user?.languageRevision).toBe(8);
  });
});
