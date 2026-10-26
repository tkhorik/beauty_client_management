// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

vi.mock('../auth/useAuth', () => ({ useAuth: () => ({ token: null, initialising: false, user: null, login: () => {}, logout: () => {} }) }));
vi.mock('../auth/useOrg', () => ({ useOrg: () => ({ current: null, organizations: [], loading: false }) }));

let root: Root;
let host: HTMLDivElement;

/** `deepLinks` reads the query string once, at import, so each case loads the app fresh. */
async function renderAppAt(url: string) {
  window.history.replaceState({}, '', url);
  vi.resetModules();
  const [{ App }, { LocaleProvider }] = await Promise.all([import('../App'), import('../i18n/LocaleProvider')]);
  await act(async () => root.render(<LocaleProvider><App /></LocaleProvider>));
}

function button(label: string) {
  const match = Array.from(host.querySelectorAll('button')).find(b => b.textContent?.trim() === label);
  if (!match) throw new Error(`No button labelled ${label}`);
  return match;
}

const heading = () => host.querySelector('h1')?.textContent;
const isRegisterForm = () => host.querySelector('#fullName') !== null;

beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  localStorage.clear();
  localStorage.setItem('beauty:locale-preference', 'en');
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); window.history.replaceState({}, '', '/'); });

it('shows the landing page to a signed-out visitor', async () => {
  await renderAppAt('/');
  expect(heading()).toBe('Every client, every visit, beautifully remembered');
  expect(host.querySelector('a[href*="releases"]')).not.toBeNull();
});

it('"Get started" opens registration and "Back" returns to the landing page', async () => {
  await renderAppAt('/');
  await act(async () => button('Get started').click());
  expect(isRegisterForm()).toBe(true);
  await act(async () => button('Back').click());
  expect(heading()).toBe('Every client, every visit, beautifully remembered');
});

it('"Sign in" opens the sign-in form', async () => {
  await renderAppAt('/');
  await act(async () => host.querySelector<HTMLButtonElement>('.landing-cta-row .btn-secondary')!.click());
  expect(heading()).toBe('Aura Beauty Log');
  expect(isRegisterForm()).toBe(false);
  expect(host.querySelector('#password')).not.toBeNull();
});

it('an invite link skips the landing page', async () => {
  await renderAppAt('/?invite=abc');
  expect(host.querySelector('.landing')).toBeNull();
  expect(host.querySelector('#password')).not.toBeNull();
});
