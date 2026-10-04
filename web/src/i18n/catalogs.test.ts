import { createInstance } from 'i18next';
import { describe, expect, it } from 'vitest';
import { en, ru } from './locales';
import { featureEn, featureRu } from './features';
import { runtimeEn, runtimeRu } from './runtimeFeatures';
import { newClientEn, newClientRu } from './newClient';
import { auditEn, auditRu } from './audit';
import { apiErrorEn, apiErrorRu } from './apiErrors';
import { resolveSystemLocale } from './store';

const english = { ...en, ...featureEn, ...runtimeEn, ...newClientEn, ...auditEn, apiErrors: apiErrorEn };
const russian = { ...ru, ...featureRu, ...runtimeRu, ...newClientRu, ...auditRu, apiErrors: apiErrorRu };
function flatten(input: object, prefix = ''): Record<string, string> {
  return Object.fromEntries(Object.entries(input).flatMap(([key, value]) => typeof value === 'string' ? [[prefix + key, value]] : Object.entries(flatten(value, `${prefix}${key}.`))));
}
const enFlat = flatten(english), ruFlat = flatten(russian);
const placeholders = (value: string) => [...value.matchAll(/{{\s*([^}]+)\s*}}/g)].map(match => match[1]).sort();

describe('complete English and Russian catalogs', () => {
  it('has identical nonempty keys and interpolation arguments in both languages', () => {
    expect(Object.keys(ruFlat).sort()).toEqual(Object.keys(enFlat).sort());
    for (const [key, value] of Object.entries(enFlat)) {
      expect(ruFlat[key].trim(), key).not.toBe('');
      expect(placeholders(ruFlat[key]), key).toEqual(placeholders(value));
    }
  });
  it('contains every plural category required by each supported locale', () => {
    const pluralBases = [...new Set(Object.keys(enFlat).filter(key => /_(one|few|many|other)$/.test(key)).map(key => key.replace(/_(one|few|many|other)$/, '')))];
    for (const locale of ['en', 'ru']) for (const base of pluralBases) {
      const catalog = locale === 'ru' ? ruFlat : enFlat;
      for (const category of new Intl.PluralRules(locale).resolvedOptions().pluralCategories) expect(catalog[`${base}_${category}`], `${locale}:${base}_${category}`).toBeTruthy();
    }
  });
  it('renders zero, singular, few, many and 21-style Russian plurals', async () => {
    const instance = createInstance();
    await instance.init({ resources: { en: { translation: english }, ru: { translation: russian } }, lng: 'ru', fallbackLng: false, initAsync: false });
    for (const [count, expected] of [[0, '0 процедур'], [1, '1 процедура'], [2, '2 процедуры'], [5, '5 процедур'], [11, '11 процедур'], [21, '21 процедура']] as const) expect(instance.t('counts.visits', { count })).toBe(expected);
    expect(instance.t('verificationBanner.deadline', { count: 1 })).toContain('в течение 1 дня');
    expect(instance.t('verificationBanner.deadline', { count: 21 })).toContain('в течение 21 дня');
    expect(instance.t('apiErrors.PASSWORD_TOO_SHORT', { min: 12 })).toContain('12');
  });
  it('follows browser language order and falls back to English for unsupported or empty lists', () => {
    for (const [languages, expected] of [[['en-US', 'ru-RU'], 'en'], [['de-DE', 'ru-RU'], 'ru'], [['de-DE', 'fr'], 'en'], [[], 'en']] as const) expect(resolveSystemLocale(languages)).toBe(expected);
  });
});
