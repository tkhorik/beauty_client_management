// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { ReactElement } from 'react';
import { expect, it, vi } from 'vitest';
import { LocaleProvider } from '../i18n/LocaleProvider';
import { api } from '../services/api';
import { ClientDetailModal } from './ClientDetailModal';
import { EditClientModal } from './EditClientModal';
import { NewClientModal } from './NewClientModal';
import type { Client } from '../types';

const client: Client = {
  id: 'client-1', name: 'Existing Client', phone: '1555', email: 'existing@example.com',
  tags: ['VIP'], customFields: {}, totalVisits: 0, createdAt: '', updatedAt: '',
};

const setValue = (input: HTMLInputElement, value: string) => {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
  input.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText' }));
};

const click = async (element: HTMLElement) => act(async () => { element.click(); });
const pressEscape = async () => act(async () => { window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); });

async function render(element: ReactElement) {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  const host = document.createElement('div');
  document.body.appendChild(host);
  const root = createRoot(host);
  await act(async () => root.render(<LocaleProvider>{element}</LocaleProvider>));
  const byLabel = (text: string) => {
    const label = [...host.querySelectorAll('label')].find(item => item.textContent?.startsWith(text))!;
    return document.getElementById(label.htmlFor) as HTMLInputElement;
  };
  const submit = async () => act(async () => {
    host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  });
  const cleanup = async () => {
    await act(async () => root.unmount());
    host.remove();
    vi.restoreAllMocks();
  };
  return { host, byLabel, submit, cleanup };
}

it('includes a typed, uncommitted tag when creating a client', async () => {
  const create = vi.spyOn(api, 'createClient').mockResolvedValue({ id: 'created' } as Client);
  const { byLabel, submit, cleanup } = await render(<NewClientModal onClose={() => {}} onSuccess={() => {}} />);
  try {
    await act(async () => {
      setValue(byLabel('Full Name'), 'New Client');
      setValue(byLabel('Phone Number'), '15550000000');
      setValue(byLabel('Primary Client Tags'), 'Sensitive Skin');
    });
    await submit();
    expect(create).toHaveBeenCalledWith(expect.objectContaining({ tags: ['Sensitive Skin'] }));
  } finally { await cleanup(); }
});

it('includes a typed tag when editing and permits an unchanged legacy phone number', async () => {
  const update = vi.spyOn(api, 'updateClient').mockResolvedValue(client);
  const { byLabel, submit, cleanup } = await render(<EditClientModal client={client} onClose={() => {}} onSuccess={() => {}} />);
  try {
    await act(async () => setValue(byLabel('Primary Client Tags'), 'Lash Extensions'));
    await submit();
    expect(update).toHaveBeenCalledWith(client.id, expect.objectContaining({ phone: '1555', tags: ['VIP', 'Lash Extensions'] }));
  } finally { await cleanup(); }
});

it('keeps a dirty create draft after Escape is cancelled, then closes after explicit confirmation', async () => {
  const onClose = vi.fn();
  const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true);
  const { byLabel, cleanup } = await render(<NewClientModal onClose={onClose} onSuccess={() => {}} />);
  try {
    const name = byLabel('Full Name');
    await act(async () => setValue(name, 'Unsaved Client'));
    await pressEscape();
    expect(confirm).toHaveBeenCalledOnce();
    expect(onClose).not.toHaveBeenCalled();
    expect(name.value).toBe('Unsaved Client');

    await pressEscape();
    expect(onClose).toHaveBeenCalledOnce();
  } finally { await cleanup(); }
});

it('submits a create draft once and blocks dismissal while the request is pending', async () => {
  let resolveCreate!: (value: Client) => void;
  const pending = new Promise<Client>(resolve => { resolveCreate = resolve; });
  const create = vi.spyOn(api, 'createClient').mockReturnValue(pending);
  const onClose = vi.fn();
  const onSuccess = vi.fn();
  const { host, byLabel, submit, cleanup } = await render(<NewClientModal onClose={onClose} onSuccess={onSuccess} />);
  try {
    await act(async () => {
      setValue(byLabel('Full Name'), 'Busy Client');
      setValue(byLabel('Phone Number'), '15550000000');
    });
    await submit();
    await submit();
    expect(create).toHaveBeenCalledOnce();
    expect(host.querySelector('fieldset')!.disabled).toBe(true);
    expect(host.querySelector<HTMLButtonElement>('button[aria-label="Close"]')!.disabled).toBe(true);

    await pressEscape();
    expect(onClose).not.toHaveBeenCalled();

    await act(async () => resolveCreate({ ...client, id: 'created' }));
    expect(onSuccess).toHaveBeenCalledOnce();
    expect(onClose).toHaveBeenCalledOnce();
  } finally { await cleanup(); }
});

it('guards dirty inline profile attributes before closing and discards them after confirmation', async () => {
  const onClose = vi.fn();
  const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true);
  const { host, cleanup } = await render(
    <ClientDetailModal client={client} visits={[]} onClose={onClose} onRefresh={() => {}} onOpenNewVisit={() => {}} onOpenPhotoCompare={() => {}} />,
  );
  try {
    const edit = [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.includes('Edit Attributes'))!;
    await click(edit);
    const add = [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.includes('Add Attribute'))!;
    await click(add);
    expect(host.querySelector<HTMLInputElement>('.attribute-key-input')).not.toBeNull();

    await pressEscape();
    expect(confirm).toHaveBeenCalledOnce();
    expect(onClose).not.toHaveBeenCalled();
    expect(host.querySelector<HTMLInputElement>('.attribute-key-input')).not.toBeNull();

    await pressEscape();
    expect(onClose).toHaveBeenCalledOnce();
  } finally { await cleanup(); }
});
