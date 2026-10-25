// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AttachmentImage } from './AttachmentImage';
import { ClientDetailModal } from './ClientDetailModal';
import { PhotoCompareModal } from './PhotoCompareModal';
import { LocaleProvider } from '../i18n/LocaleProvider';
import { api } from '../services/api';
import { setToken, clearToken } from '../auth/tokenStore';
import { setActiveOrgId, clearActiveOrgId } from '../auth/orgStore';
import type { Attachment, Client, Visit } from '../types';

const photo = (id: string, tag: Attachment['tag'] = 'BEFORE'): Attachment => ({
  id, visitId: 'visit', fileUrl: `/api/attachments/${id}/file`, fileType: 'image/jpeg',
  fileSize: 10, tag, uploadedAt: '2026-01-01',
});
const visit: Visit = {
  id: 'visit', clientId: 'client', visitDateTime: '2026-01-01T10:00', durationMinutes: 60,
  procedureNotes: 'History must remain visible', status: 'COMPLETED', createdAt: '2026-01-01',
  attachments: [photo('missing'), photo('healthy', 'AFTER')],
};
const client: Client = {
  id: 'client', name: 'Client', phone: '123', tags: [], customFields: {}, totalVisits: 1,
  createdAt: '2026-01-01', updatedAt: '2026-01-01',
};
const fetchMock = vi.fn<typeof fetch>();
let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  localStorage.clear();
  setToken('test-access');
  setActiveOrgId('test-org');
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:photo');
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  clearToken();
  clearActiveOrgId();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it('loads history without fetching photos, then keeps notes and healthy photos when another photo is missing', async () => {
  fetchMock.mockResolvedValueOnce(Response.json([visit]));
  const history = await api.getVisits('client');
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(history).toEqual([visit]);
  fetchMock.mockImplementation(async input => String(input).includes('/missing/')
    ? Response.json({ code: 'ATTACHMENT_NOT_FOUND' }, { status: 404 })
    : new Response(new Blob(['photo'], { type: 'image/jpeg' })));
  await act(async () => root.render(<LocaleProvider><ClientDetailModal client={client} visits={history}
    onClose={() => {}} onRefresh={() => {}} onOpenNewVisit={() => {}} onOpenPhotoCompare={() => {}} />
  </LocaleProvider>));
  expect(host.textContent).toContain(visit.procedureNotes);
  expect(host.textContent).toContain('Photo unavailable');
  expect(host.textContent).not.toContain('Could not load visit history');
  expect(host.querySelector('img')?.src).toBe('blob:photo');
  for (const [, init] of fetchMock.mock.calls) {
    const headers = new Headers(init?.headers);
    expect(headers.get('Authorization')).toBe('Bearer test-access');
    expect(headers.get('X-Org-Id')).toBe('test-org');
  }
});

it('keeps real history API failures as errors and normalizes legacy records', async () => {
  fetchMock.mockResolvedValueOnce(Response.json({}, { status: 500 }));
  await expect(api.getVisits('client')).rejects.toMatchObject({ status: 500 });
  fetchMock.mockResolvedValueOnce(Response.json([{ ...visit, attachments: null }]));
  expect((await api.getVisits('client'))[0].attachments).toEqual([]);
});

it('retries a failed photo and releases its object URL when closed', async () => {
  fetchMock.mockRejectedValueOnce(new TypeError('Network failed'));
  await act(async () => root.render(<LocaleProvider><AttachmentImage fileUrl={photo('retry').fileUrl} alt="Photo" /></LocaleProvider>));
  expect(host.textContent).toContain('Photo unavailable');
  fetchMock.mockResolvedValueOnce(new Response('image'));
  await act(async () => host.querySelector('button')!.click());
  expect(host.querySelector('img')?.src).toBe('blob:photo');
  await act(async () => root.render(null));
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:photo');
});

it('does not show a late photo after switching records and releases the late object URL', async () => {
  let resolve!: (url: string) => void;
  const pending = new Promise<string>(done => { resolve = done; });
  vi.spyOn(api, 'loadAttachmentUrl').mockReturnValueOnce(pending);
  await act(async () => root.render(<LocaleProvider><AttachmentImage fileUrl={photo('old').fileUrl} alt="Photo" /></LocaleProvider>));
  expect(host.querySelector('[role="status"]')).not.toBeNull();
  await act(async () => root.render(<LocaleProvider><AttachmentImage fileUrl="/new.jpg" alt="Photo" /></LocaleProvider>));
  await act(async () => resolve('blob:old'));
  expect(host.querySelector('img')?.getAttribute('src')).toBe('/new.jpg');
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:old');
});

it('offers retry for image decode failures without removing the visit', async () => {
  await act(async () => root.render(<LocaleProvider><AttachmentImage fileUrl="/photo.jpg" alt="Photo" /></LocaleProvider>));
  await act(async () => host.querySelector('img')!.dispatchEvent(new Event('error')));
  expect(host.textContent).toContain('Photo unavailable');
  await act(async () => host.querySelector('button')!.click());
  expect(host.querySelector('img')?.getAttribute('src')).toBe('/photo.jpg');
});

it('loads authenticated comparison images and recovers only the failed image', async () => {
  fetchMock.mockImplementation(async input => String(input).includes('/missing/')
    ? Response.json({}, { status: 404 }) : new Response('image'));
  await act(async () => root.render(<LocaleProvider><PhotoCompareModal attachments={visit.attachments} onClose={() => {}} /></LocaleProvider>));
  expect(host.textContent).toContain('Photo unavailable');
  expect(host.querySelector('input[type="range"]')).toBeNull();
  fetchMock.mockResolvedValueOnce(new Response('image'));
  await act(async () => Array.from(host.querySelectorAll('button')).find(button => button.textContent === 'Retry')!.click());
  expect(fetchMock).toHaveBeenCalledTimes(3);
  expect(host.querySelectorAll('img')).toHaveLength(2);
  expect(host.querySelector('input[type="range"]')).not.toBeNull();
});

it('does not report a successful photo upload as failed because of its preview', async () => {
  fetchMock.mockResolvedValueOnce(new Response('image'))
    .mockResolvedValueOnce(Response.json(photo('uploaded'), { status: 201 }));
  await expect(api.addAttachment('visit', 'data:image/jpeg;base64,aQ==', 'BEFORE')).resolves.toEqual(photo('uploaded'));
  expect(fetchMock).toHaveBeenCalledTimes(2);
});

it('opens each individual photo, restores focus and visit scroll, and leaves comparison independent', async () => {
  const compare = vi.fn();
  const closeProfile = vi.fn();
  vi.spyOn(api, 'loadAttachmentUrl').mockImplementation(async url => `blob:${url}`);
  await act(async () => root.render(<LocaleProvider><ClientDetailModal client={client} visits={[visit]}
    onClose={closeProfile} onRefresh={() => {}} onOpenNewVisit={() => {}} onOpenPhotoCompare={compare} />
  </LocaleProvider>));
  const profile = host.querySelector('[role="dialog"]')!;
  const scroll = host.querySelector<HTMLElement>('.dialog-body')!;
  scroll.scrollTop = 180;
  const buttons = [...host.querySelectorAll<HTMLButtonElement>('.attachment-open')];
  expect(buttons).toHaveLength(2);
  for (const [index, button] of buttons.entries()) {
    button.focus();
    await act(async () => button.click());
    const viewer = host.querySelector('.photo-viewer')!;
    expect(viewer.querySelector('img')?.getAttribute('src')).toBe(`blob:${visit.attachments[index].fileUrl}`);
    expect(viewer.querySelector('input[type="range"]')).toBeNull();
    expect(document.activeElement).toBe(viewer);
    await act(async () => window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true })));
    expect(host.querySelector('.photo-viewer')).toBeNull();
    expect(document.activeElement).toBe(button);
    expect(host.querySelector('[role="dialog"]')).toBe(profile);
    expect(scroll.scrollTop).toBe(180);
    expect(closeProfile).not.toHaveBeenCalled();
  }
  expect(compare).not.toHaveBeenCalled();
  await act(async () => [...host.querySelectorAll('button')].find(button => button.textContent?.includes('Compare Before/After'))!.click());
  expect(compare).toHaveBeenCalledWith(visit.attachments);
});

it('opens a lone failed thumbnail, retries without nested buttons, and can close and reopen', async () => {
  const attachment = { ...photo('solo', 'PROCEDURE'), caption: 'Portrait treatment' };
  const load = vi.spyOn(api, 'loadAttachmentUrl').mockRejectedValue(new Error('offline'));
  await act(async () => root.render(<LocaleProvider><ClientDetailModal client={client} visits={[{ ...visit, attachments: [attachment] }]}
    onClose={() => {}} onRefresh={() => {}} onOpenNewVisit={() => {}} onOpenPhotoCompare={() => {}} />
  </LocaleProvider>));
  expect(host.querySelector('button button')).toBeNull();
  expect(host.textContent).not.toContain('Compare Before/After');
  const trigger = host.querySelector<HTMLButtonElement>('.attachment-open')!;
  expect(trigger.getAttribute('aria-label')).toBe('Open photo: Portrait treatment');
  // The thumbnail retry is an independent action, not a photo-open click.
  await act(async () => host.querySelector<HTMLButtonElement>('.attachment-feedback button')!.click());
  expect(host.querySelector('.photo-viewer')).toBeNull();
  trigger.focus();
  let resolve!: (url: string) => void;
  load.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
  await act(async () => trigger.click());
  expect(host.querySelector('.photo-viewer [role="status"]')?.textContent).toContain('Loading');
  await act(async () => resolve('blob:solo'));
  const viewer = host.querySelector('.photo-viewer')!;
  expect(viewer.textContent).toContain('Portrait treatment');
  expect(viewer.textContent).toContain('Procedure');
  await act(async () => viewer.querySelector('img')!.dispatchEvent(new Event('error')));
  expect(viewer.textContent).toContain('Photo unavailable');
  load.mockResolvedValue('blob:retry');
  await act(async () => viewer.querySelector<HTMLButtonElement>('.attachment-feedback button')!.click());
  expect(viewer.querySelector('img')?.getAttribute('src')).toBe('blob:retry');
  await act(async () => viewer.querySelector<HTMLButtonElement>('[aria-label="Close"]')!.click());
  expect(document.activeElement).toBe(trigger);
  await act(async () => trigger.click());
  expect(host.querySelector('.photo-viewer img')?.getAttribute('src')).toBe('blob:retry');
});
