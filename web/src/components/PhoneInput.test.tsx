// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { expect, it, vi } from 'vitest';
import { NewClientModal } from './NewClientModal';
import { LocaleProvider } from '../i18n/LocaleProvider';
import { api } from '../services/api';
import type { Client } from '../types';

const setValue = (input: HTMLInputElement, value: string, inputType = 'insertText') => {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
  input.setSelectionRange(value.length, value.length);
  input.dispatchEvent(new InputEvent('input', { bubbles: true, inputType }));
};

async function renderModal() {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  const host = document.createElement('div');
  document.body.appendChild(host);
  const root = createRoot(host);
  await act(async () => root.render(<LocaleProvider><NewClientModal onClose={() => {}} onSuccess={() => {}} /></LocaleProvider>));
  const phone = host.querySelector<HTMLInputElement>('input[type="tel"]')!;
  const name = host.querySelector<HTMLInputElement>('input[type="text"]')!;
  const submit = async () => act(async () => { host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  const cleanup = async () => { await act(async () => root.unmount()); host.remove(); vi.restoreAllMocks(); };
  return { host, phone, name, submit, cleanup };
}

it('is an accessible, labelled, required tel field', async () => {
  const { host, phone, cleanup } = await renderModal();
  try {
    expect(host.querySelector(`label[for="${phone.id}"]`)).not.toBeNull();
    expect(phone.getAttribute('aria-required')).toBe('true');
    expect(phone.placeholder).toBe('+1 (555) 000-0000');
  } finally { await cleanup(); }
});

it('masks keystrokes, and backspace over a separator removes the digit before it', async () => {
  const { phone, cleanup } = await renderModal();
  try {
    await act(async () => setValue(phone, '5550000000'));
    expect(phone.value).toBe('+1 (555) 000-0000');
    await act(async () => setValue(phone, '+1 (555) 000-000'));
    await act(async () => setValue(phone, '+1 (555) 000', 'deleteContentBackward'));
    expect(phone.value).toBe('+1 (555) 000');
    await act(async () => setValue(phone, '+1 (555) ', 'deleteContentBackward'));
    expect(phone.value).toBe('+1 (555');
  } finally { await cleanup(); }
});

it('shows an inline error instead of submitting an empty or incomplete number', async () => {
  const create = vi.spyOn(api, 'createClient').mockResolvedValue({ id: 'c' } as Client);
  const { phone, name, submit, cleanup } = await renderModal();
  try {
    await act(async () => setValue(name, 'Victoria Sterling'));
    await submit();
    expect(phone.getAttribute('aria-invalid')).toBe('true');
    expect(document.getElementById(phone.getAttribute('aria-describedby')!)?.textContent).toBe('Phone number is required.');

    await act(async () => setValue(phone, '1555000'));
    await submit();
    expect(document.getElementById(phone.getAttribute('aria-describedby')!)?.textContent).toMatch(/complete phone number/);
    expect(create).not.toHaveBeenCalled();

    await act(async () => setValue(phone, '+1 (555) 0000000'));
    expect(phone.getAttribute('aria-invalid')).toBe('false');
    await submit();
    expect(create).toHaveBeenCalledWith(expect.objectContaining({ phone: '+1 (555) 000-0000' }));
  } finally { await cleanup(); }
});
