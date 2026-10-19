// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { expect, it, vi } from 'vitest';
import { EMAIL_PATTERN, NewClientModal } from './NewClientModal';
import { LocaleProvider } from '../i18n/LocaleProvider';
import { api } from '../services/api';
import type { Client } from '../types';

const setValue = (input: HTMLInputElement, value: string) => {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
  input.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText' }));
};

async function renderModal() {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  const host = document.createElement('div');
  document.body.appendChild(host);
  const root = createRoot(host);
  await act(async () => root.render(<LocaleProvider><NewClientModal onClose={() => {}} onSuccess={() => {}} /></LocaleProvider>));
  const byLabel = (text: string) => {
    const label = [...host.querySelectorAll('label')].find(l => l.textContent?.startsWith(text))!;
    return document.getElementById(label.htmlFor) as HTMLInputElement;
  };
  const submit = async () => act(async () => { host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  const cleanup = async () => { await act(async () => root.unmount()); host.remove(); vi.restoreAllMocks(); };
  return { host, byLabel, submit, cleanup };
}

const errorOf = (input: HTMLInputElement) => document.getElementById(input.getAttribute('aria-describedby') ?? '')?.textContent;

it('is a labelled dialog whose fields all have associated labels', async () => {
  const { host, byLabel, cleanup } = await renderModal();
  try {
    const dialog = host.querySelector('[role="dialog"]')!;
    expect(document.getElementById(dialog.getAttribute('aria-labelledby')!)?.textContent).toContain('Create New Client Profile');
    for (const label of ['Full Name', 'Phone Number', 'Email Address', 'Primary Client Tags']) expect(byLabel(label), label).not.toBeNull();
    expect(byLabel('Full Name').placeholder).toBe('e.g. Victoria Sterling');
    expect(byLabel('Email Address').placeholder).toBe('client@example.com');
  } finally { await cleanup(); }
});

it('flags an empty name and a malformed email inline, and does not submit', async () => {
  const create = vi.spyOn(api, 'createClient').mockResolvedValue({ id: 'c' } as Client);
  const { byLabel, submit, cleanup } = await renderModal();
  try {
    const name = byLabel('Full Name'), phone = byLabel('Phone Number'), email = byLabel('Email Address');
    await act(async () => { setValue(phone, '15550000000'); setValue(email, 'client@example'); });
    await submit();
    expect(name.getAttribute('aria-invalid')).toBe('true');
    expect(errorOf(name)).toBe('Full name is required.');
    expect(email.getAttribute('aria-invalid')).toBe('true');
    expect(errorOf(email)).toContain('valid email');
    expect(document.activeElement).toBe(name);
    expect(create).not.toHaveBeenCalled();

    await act(async () => { setValue(name, 'Victoria Sterling'); setValue(email, 'client@example.com'); });
    expect(name.getAttribute('aria-invalid')).toBe('false');
    expect(email.getAttribute('aria-invalid')).toBe('false');
    await submit();
    expect(create).toHaveBeenCalledWith(expect.objectContaining({ name: 'Victoria Sterling', email: 'client@example.com' }));
  } finally { await cleanup(); }
});

it('submits without an email, since it is optional', async () => {
  const create = vi.spyOn(api, 'createClient').mockResolvedValue({ id: 'c' } as Client);
  const { byLabel, submit, cleanup } = await renderModal();
  try {
    await act(async () => { setValue(byLabel('Full Name'), 'Victoria Sterling'); setValue(byLabel('Phone Number'), '15550000000'); });
    await submit();
    expect(create).toHaveBeenCalledWith(expect.objectContaining({ email: undefined }));
  } finally { await cleanup(); }
});

it('adds tags as removable chips on Enter and dedupes them', async () => {
  const { host, byLabel, cleanup } = await renderModal();
  try {
    const tag = byLabel('Primary Client Tags');
    for (const value of ['Sensitive Skin', 'Sensitive Skin']) {
      await act(async () => setValue(tag, value));
      await act(async () => { tag.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); });
    }
    expect([...host.querySelectorAll('li')].map(li => li.textContent)).toEqual(['Sensitive Skin']);
    await act(async () => { host.querySelector<HTMLButtonElement>('button[aria-label="Remove tag Sensitive Skin"]')!.click(); });
    expect(host.querySelectorAll('li')).toHaveLength(0);
  } finally { await cleanup(); }
});

it('accepts common addresses and rejects malformed ones', () => {
  for (const ok of ['a@b.co', 'first.last+tag@sub.example.com']) expect(EMAIL_PATTERN.test(ok), ok).toBe(true);
  for (const bad of ['plain', 'a@b', 'a@b.c', 'a b@c.com', '@c.com', 'a@.com', 'a@b..com']) expect(EMAIL_PATTERN.test(bad), bad).toBe(false);
});
