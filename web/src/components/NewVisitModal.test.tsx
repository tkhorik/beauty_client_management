// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { NewVisitModal } from './NewVisitModal';
import { LocaleProvider } from '../i18n/LocaleProvider';
import { api } from '../services/api';
import { compressImage } from '../utils/imageCompressor';
import { localDateTimeInputValue } from '../utils/localDateTime';
import type { Attachment, Client, Visit } from '../types';

vi.mock('../utils/imageCompressor', () => ({ compressImage: vi.fn() }));

const client: Client = { id: 'c', name: 'Client', phone: '123', tags: [], customFields: {}, totalVisits: 0, createdAt: '', updatedAt: '' };
let host: HTMLDivElement;
let root: Root;
const onClose = vi.fn();
const onSuccess = vi.fn();

beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  localStorage.setItem('beauty:locale-preference', 'en');
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  onClose.mockReset();
  onSuccess.mockReset();
  vi.mocked(compressImage).mockReset().mockImplementation(async file => `data:image/jpeg;base64,${file.name}`);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.restoreAllMocks();
});

async function render(locked = true) {
  await act(async () => root.render(<LocaleProvider><NewVisitModal client={locked ? client : undefined} clientsList={[client]} onClose={onClose} onSuccess={onSuccess} /></LocaleProvider>));
}
async function change(element: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement, value: string) {
  await act(async () => {
    const prototype = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : element instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, 'value')!.set!.call(element, value);
    element.dispatchEvent(new Event(element instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  });
}
async function notes() { await change(host.querySelector('textarea')!, 'Historical visit'); }
async function submit() {
  await act(async () => { host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
}
async function photo(tag: 'BEFORE' | 'AFTER') {
  const slot = host.querySelectorAll('.media-slot')[tag === 'BEFORE' ? 0 : 1];
  const input = slot.querySelector<HTMLInputElement>('input[type="file"]')!;
  Object.defineProperty(input, 'files', { configurable: true, value: [new File(['photo'], tag)] });
  await act(async () => { input.dispatchEvent(new Event('change', { bubbles: true })); });
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(accept => { resolve = accept; });
  return { promise, resolve };
}
async function dismissAllWays() {
  await act(async () => {
    host.querySelector<HTMLButtonElement>('.close-button')!.click();
    host.querySelector<HTMLButtonElement>('.dialog-footer .btn-secondary')!.click();
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    const overlay = host.querySelector('.dialog-overlay')!;
    overlay.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    overlay.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
}

it('initializes with local components, including date boundaries', () => {
  expect(localDateTimeInputValue(new Date(2020, 0, 2, 0, 5))).toBe('2020-01-02T00:05');
});

it('submits a historical wall-clock date unchanged, without an offset', async () => {
  const create = vi.spyOn(api, 'createVisit').mockResolvedValue({ id: 'v' } as Visit);
  await render();
  const date = host.querySelector<HTMLInputElement>('input[type="datetime-local"]')!;
  expect(date.value).toBe(localDateTimeInputValue(new Date()));
  await change(date, '2020-01-02T00:05');
  await notes();
  await submit();
  expect(create).toHaveBeenCalledWith(expect.objectContaining({ visitDateTime: '2020-01-02T00:05' }));
});

it('requires explicit client choice and exposes a linked inline validation error', async () => {
  const create = vi.spyOn(api, 'createVisit').mockResolvedValue({ id: 'v' } as Visit);
  await render(false);
  const select = host.querySelector<HTMLSelectElement>('select')!;
  expect(select.value).toBe('');
  expect(select.options[0].text).toBe('Choose a client');
  await notes();
  await submit();
  expect(create).not.toHaveBeenCalled();
  expect(select.getAttribute('aria-invalid')).toBe('true');
  expect(document.getElementById(select.getAttribute('aria-describedby')!)?.getAttribute('role')).toBe('alert');
  expect(document.activeElement).toBe(select);
  await change(select, 'c');
  await submit();
  expect(create).toHaveBeenCalledOnce();
});

it.each(['BEFORE', 'AFTER'] as const)('retries only the failed %s photo without creating a duplicate visit', async failedTag => {
  const create = vi.spyOn(api, 'createVisit').mockResolvedValue({ id: 'saved-visit' } as Visit);
  let fail = true;
  const upload = vi.spyOn(api, 'addAttachment').mockImplementation(async (_id, _data, tag) => {
    if (tag === failedTag && fail) throw new Error('upload failed');
    return { id: tag } as Attachment;
  });
  await render();
  await notes();
  await photo('BEFORE');
  await photo('AFTER');
  await submit();
  expect(create).toHaveBeenCalledOnce();
  expect(upload.mock.calls.map(call => call[2])).toEqual(['BEFORE', 'AFTER']);
  expect(host.querySelector('[role="status"]')?.textContent).toContain('Visit saved. 1 of 2 photos uploaded');
  expect(host.querySelector('textarea')!.disabled).toBe(true);
  expect([...host.querySelectorAll<HTMLButtonElement>('.media-preview button')].every(button => button.disabled)).toBe(true);
  expect(onClose).not.toHaveBeenCalled();
  expect(onSuccess).not.toHaveBeenCalled();
  fail = false;
  await submit();
  expect(create).toHaveBeenCalledOnce();
  expect(upload.mock.calls.map(call => [call[0], call[2]])).toEqual([
    ['saved-visit', 'BEFORE'], ['saved-visit', 'AFTER'], ['saved-visit', failedTag],
  ]);
  expect(onSuccess).toHaveBeenCalledOnce();
  expect(onClose).toHaveBeenCalledOnce();
});

it('guards finishing a partially saved visit and refreshes the saved record after confirmation', async () => {
  vi.spyOn(api, 'createVisit').mockResolvedValue({ id: 'v' } as Visit);
  vi.spyOn(api, 'addAttachment').mockRejectedValue(new Error('offline'));
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
  await render();
  await notes();
  await photo('BEFORE');
  await submit();
  const finish = host.querySelector<HTMLButtonElement>('.dialog-footer .btn-secondary')!;
  expect(finish.textContent).toBe('Finish without remaining photos');
  await act(async () => finish.click());
  expect(onClose).not.toHaveBeenCalled();
  expect(confirm).toHaveBeenCalledWith('The visit is saved. Finish without uploading the remaining photos?');
  confirm.mockReturnValue(true);
  await act(async () => finish.click());
  expect(onSuccess).toHaveBeenCalledOnce();
  expect(onClose).toHaveBeenCalledOnce();
});

it('locks submission, changes and all dismissal paths while saving, including same-tick events', async () => {
  const pending = deferred<Visit>();
  const create = vi.spyOn(api, 'createVisit').mockReturnValue(pending.promise);
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
  await render(false);
  await change(host.querySelector('select')!, 'c');
  await notes();
  await act(async () => {
    const form = host.querySelector('form')!;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
  });
  expect(create).toHaveBeenCalledOnce();
  expect(host.querySelector('textarea')!.disabled).toBe(true);
  expect(host.querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(true);
  await dismissAllWays();
  expect(onClose).not.toHaveBeenCalled();
  expect(confirm).not.toHaveBeenCalled();
  const unload = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(unload);
  expect(unload.defaultPrevented).toBe(true);
  await act(async () => pending.resolve({ id: 'v' } as Visit));
  expect(onClose).toHaveBeenCalledOnce();
});

it('locks saving and dismissal while a photo is compressing', async () => {
  const pending = deferred<string>();
  vi.mocked(compressImage).mockReturnValue(pending.promise);
  const create = vi.spyOn(api, 'createVisit');
  vi.spyOn(window, 'confirm').mockReturnValue(true);
  await render();
  await notes();
  await photo('BEFORE');
  expect(host.querySelector('[role="status"]')?.textContent).toBe('Preparing photo…');
  await submit();
  await dismissAllWays();
  expect(create).not.toHaveBeenCalled();
  expect(onClose).not.toHaveBeenCalled();
  await act(async () => pending.resolve('data:image/jpeg;base64,before'));
  expect(host.querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(false);
});

it('guards dirty drafts but allows an untouched form to close without confirmation', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
  await render();
  await act(async () => host.querySelector<HTMLButtonElement>('.close-button')!.click());
  expect(onClose).toHaveBeenCalledOnce();
  expect(confirm).not.toHaveBeenCalled();
  onClose.mockClear();
  await notes();
  await act(async () => window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })));
  expect(confirm).toHaveBeenCalledWith('Discard this unsaved visit?');
  expect(onClose).not.toHaveBeenCalled();
  const unload = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(unload);
  expect(unload.defaultPrevented).toBe(true);
});
