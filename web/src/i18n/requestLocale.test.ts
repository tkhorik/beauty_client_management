// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '../services/api';
import { clearToken, setToken } from '../auth/tokenStore';
import { setActiveLanguagePreference } from './store';

afterEach(() => { clearToken(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('language preference wire contract', () => {
  it('sends active account locale and the intended account ID with the optimistic revision', async () => {
    localStorage.setItem('beauty:locale-preference', 'en');
    setActiveLanguagePreference('ru');
    setToken('test-only-token');
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ preference: 'ru', revision: 8 }), { status: 200 }));
    vi.stubGlobal('fetch', fetch);
    expect(await api.updateLanguagePreference('ru', 7, 'account-a')).toEqual({ preference: 'ru', revision: 8 });
    const [url, options] = fetch.mock.calls[0];
    expect(url).toContain('/users/me/language');
    expect(options.method).toBe('PUT');
    expect(new Headers(options.headers).get('Accept-Language')).toBe('ru');
    expect(JSON.parse(options.body)).toEqual({ preference: 'ru', expectedRevision: 7, expectedAccountId: 'account-a' });
  });

  it('never turns a rejected or offline account language write into demo success', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'ACCOUNT_CHANGED' }), { status: 403 })));
    await expect(api.updateLanguagePreference('ru', 1, 'account-a')).rejects.toMatchObject({ status: 403, body: { code: 'ACCOUNT_CHANGED' } });
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')));
    await expect(api.updateLanguagePreference('ru', 1, 'account-a')).rejects.toThrow('offline');
  });
});
