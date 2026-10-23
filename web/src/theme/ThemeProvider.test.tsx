// @vitest-environment jsdom
/// <reference types="node" />
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { ThemeProvider, THEME_STORAGE_KEY, parseThemePreference, useTheme } from './ThemeProvider';
import { LocaleProvider } from '../i18n/LocaleProvider';
import { ThemeSelector } from '../components/ThemeSelector';

let host: HTMLDivElement;
let root: Root;
let dark = false;
const listeners = new Set<() => void>();
function Probe() {
  const { preference } = useTheme();
  return <><output>{preference}</output><ThemeSelector /></>;
}
async function mount() {
  await act(async () => root.render(<ThemeProvider><LocaleProvider><Probe /></LocaleProvider></ThemeProvider>));
}
async function choose(value: string) {
  await act(async () => {
    const select = host.querySelector('select')!;
    select.value = value;
    select.dispatchEvent(new Event('change', { bubbles: true }));
  });
}
async function system(value: boolean) { await act(async () => { dark = value; listeners.forEach(listener => listener()); }); }
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  localStorage.clear(); dark = false; listeners.clear();
  vi.stubGlobal('matchMedia', vi.fn(() => ({ get matches() { return dark; }, addEventListener: (_: string, fn: () => void) => listeners.add(fn), removeEventListener: (_: string, fn: () => void) => listeners.delete(fn) })));
  document.head.innerHTML = '<meta name="theme-color" content="#f8f7f2">';
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
it('defaults invalid/missing preferences to System and follows live system changes', async () => {
  for (const value of [null, '', 'sepia', '{}', 'DARK']) expect(parseThemePreference(value)).toBe('system');
  await mount(); expect(host.querySelector('output')?.textContent).toBe('system');
  expect(document.documentElement.dataset.theme).toBe('light');
  await system(true);
  expect(document.documentElement.dataset.theme).toBe('dark');
  expect(document.documentElement.style.colorScheme).toBe('dark');
  expect(document.querySelector('meta')?.content).toBe('#18211d');
});
it('persists manual overrides, ignores system changes and resumes following System', async () => {
  dark = true; localStorage.setItem(THEME_STORAGE_KEY, 'light');
  await mount(); expect(document.documentElement.dataset.theme).toBe('light');
  expect(listeners.size).toBe(0);
  await choose('dark'); expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
  await system(false); expect(document.documentElement.dataset.theme).toBe('dark');
  await choose('system'); expect(document.documentElement.dataset.theme).toBe('light');
  await system(true); expect(document.documentElement.dataset.theme).toBe('dark');
  await act(async () => root.unmount()); root = createRoot(host); await mount();
  expect(host.querySelector('select')?.value).toBe('system');
});
it('synchronizes other tabs, invalid values, removal and storage.clear without writing back', async () => {
  await mount(); const write = vi.spyOn(Storage.prototype, 'setItem');
  for (const [key, newValue, expected] of [[THEME_STORAGE_KEY, 'dark', 'dark'], [THEME_STORAGE_KEY, 'wrong', 'light'], [THEME_STORAGE_KEY, null, 'light'], [null, null, 'light']] as const) {
    await act(async () => window.dispatchEvent(new StorageEvent('storage', { key, newValue })));
    expect(document.documentElement.dataset.theme).toBe(expected);
  }
  expect(write).not.toHaveBeenCalled();
});
it('keeps the selector functional when storage is unavailable', async () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied'); });
  await mount(); await choose('dark'); expect(document.documentElement.dataset.theme).toBe('dark');
});
it('labels all options and the description in Russian', async () => {
  localStorage.setItem('beauty:locale-preference', 'ru'); await mount();
  expect(host.querySelector('label')?.textContent).toBe('Оформление');
  expect([...host.querySelectorAll('option')].map(option => option.textContent)).toEqual(['Системное', 'Светлое', 'Тёмное']);
  expect(host.querySelector('select')?.getAttribute('aria-describedby')).toBe(host.querySelector('p')?.id);
});
it('head bootstrap resolves a saved override before React, including invalid and blocked storage', () => {
  const html = readFileSync('index.html', 'utf8');
  const script = html.match(/<script>([\s\S]*?)<\/script>/)![1];
  dark = true;
  for (const [value, expected] of [['light', 'light'], ['dark', 'dark'], ['invalid', 'dark'], ['system', 'dark']]) {
    localStorage.setItem(THEME_STORAGE_KEY, value); window.eval(script);
    expect(document.documentElement.dataset.theme).toBe(expected);
    expect(document.documentElement.style.colorScheme).toBe(expected);
  }
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
  window.eval(script); expect(document.documentElement.dataset.theme).toBe('dark');
});
