import { describe, expect, it } from 'vitest';
import { resolveSystemLocale } from './store';
import { errorTranslationKey } from './errors';
import { responseBelongsToSession, settlePending } from './syncState';

describe('locale resolution', () => {
  it('selects Russian from a regional browser preference and otherwise falls back to English', () => {
    expect(resolveSystemLocale(['ru-RU', 'en-US'])).toBe('ru');
    expect(resolveSystemLocale(['de-DE', 'en-US'])).toBe('en');
    expect(resolveSystemLocale(['en-US', 'ru-RU'])).toBe('en');
  });

  it('does not lose a newer offline choice when an older request completes', () => {
    const first = { id: 'one', preference: 'en' as const, revision: 4 };
    const second = { id: 'two', preference: 'ru' as const, revision: 4 };
    expect(settlePending(first, second, 5)).toEqual({ ...second, revision: 5 });
    expect(settlePending(first, first, 5)).toBeNull();
  });

  it('rejects responses after logout/relogin or account changes', () => {
    expect(responseBelongsToSession('a', 7, 'a', 7)).toBe(true);
    expect(responseBelongsToSession('a', 7, 'b', 7)).toBe(false);
    expect(responseBelongsToSession('a', 7, 'a', 8)).toBe(false);
  });

  it('maps stable API error codes without matching an English server message', () => {
    expect(errorTranslationKey({ body: { code: 'PASSWORD_TOO_SHORT' } })).toBe('apiErrors.PASSWORD_TOO_SHORT');
    expect(errorTranslationKey({ body: { code: 'EMAIL_NOT_VERIFIED' } })).toBe('apiErrors.EMAIL_NOT_VERIFIED');
    expect(errorTranslationKey({ body: { error: 'a mutable English message' } })).toBe('errors.UNKNOWN');
  });
});
