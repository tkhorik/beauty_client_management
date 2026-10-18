// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it } from 'vitest';
import { LocaleProvider } from '../i18n/LocaleProvider';
import { LanguageSelector } from './LanguageSelector';
import { resolveSystemLocale } from '../i18n/store';

let root: Root;
let host: HTMLDivElement;

beforeEach(async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  localStorage.clear();
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  await act(async () => root.render(<LocaleProvider><LanguageSelector compact /></LocaleProvider>));
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); localStorage.clear(); });

const trigger = () => host.querySelector<HTMLButtonElement>('.lang-menu__trigger')!;
const items = () => Array.from(host.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]'));

it('follows the browser language until the visitor picks one', () => {
  expect(localStorage.getItem('beauty:locale-preference')).toBeNull();
  expect(trigger().getAttribute('aria-expanded')).toBe('false');
  expect(trigger().textContent).toBe(resolveSystemLocale().toUpperCase());
});

it('opens a menu, switches language and remembers the choice', async () => {
  await act(async () => trigger().click());
  expect(items().map(item => item.getAttribute('aria-checked'))).toEqual(['true', 'false', 'false']);
  await act(async () => items()[2].click());
  expect(host.querySelector('[role="menu"]')).toBeNull();
  expect(trigger().textContent).toBe('RU');
  expect(localStorage.getItem('beauty:locale-preference')).toBe('ru');
  expect(document.activeElement).toBe(trigger());
});

it('closes on Escape and on an outside click', async () => {
  await act(async () => trigger().click());
  await act(async () => host.querySelector('[role="menu"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })));
  expect(host.querySelector('[role="menu"]')).toBeNull();
  await act(async () => trigger().click());
  await act(async () => document.body.dispatchEvent(new Event('pointerdown', { bubbles: true })));
  expect(host.querySelector('[role="menu"]')).toBeNull();
});

it('falls back to the single browser language when the list is empty', () => {
  expect(resolveSystemLocale(['ru-RU'])).toBe('ru');
  expect(resolveSystemLocale(['de-DE', 'ru'])).toBe('ru');
  expect(resolveSystemLocale(['de-DE'])).toBe('en');
});
