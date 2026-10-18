// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { expect, it, vi } from 'vitest';
import { NewVisitModal } from './NewVisitModal';
import { LocaleProvider } from '../i18n/LocaleProvider';
import { api } from '../services/api';
import { localDateTimeInputValue } from '../utils/localDateTime';
import type { Client, Visit } from '../types';

it('initializes with local components, including date boundaries', () => {
  expect(localDateTimeInputValue(new Date(2020, 0, 2, 0, 5))).toBe('2020-01-02T00:05');
});

it('submits a historical wall-clock date unchanged, without an offset', async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  const client: Client = { id: 'c', name: 'Client', phone: '123', tags: [], customFields: {}, totalVisits: 0, createdAt: '', updatedAt: '' };
  const create = vi.spyOn(api, 'createVisit').mockResolvedValue({ id: 'v' } as Visit);
  const host = document.createElement('div');
  const root = createRoot(host);
  try {
    await act(async () => root.render(<LocaleProvider><NewVisitModal client={client} clientsList={[client]} onClose={() => {}} onSuccess={() => {}} /></LocaleProvider>));
    const date = host.querySelector<HTMLInputElement>('input[type="datetime-local"]')!;
    expect(date.value).toBe(localDateTimeInputValue(new Date()));
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(date, '2020-01-02T00:05');
      date.dispatchEvent(new Event('input', { bubbles: true }));
      const notes = host.querySelector('textarea')!;
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(notes, 'Historical visit');
      notes.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await act(async () => { host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
    expect(create).toHaveBeenCalledWith(expect.objectContaining({ visitDateTime: '2020-01-02T00:05' }));
  } finally {
    await act(async () => root.unmount());
    vi.restoreAllMocks();
  }
});
