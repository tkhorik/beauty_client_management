// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Organization } from '../types';

const auth = vi.hoisted(() => ({ token: 'test-token' as string | null }));
const realOrg: Organization = { id: 'real', name: 'Real', slug: 'real', role: 'ORG_ADMIN', status: 'ACTIVE' };
const otherOrg: Organization = { ...realOrg, id: 'other', name: 'Other' };

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

let root: Root;
let host: HTMLDivElement;

async function renderProvider() {
  const [{ OrgProvider }, { useOrg }] = await Promise.all([
    import('./OrgContext'),
    import('./useOrg'),
  ]);
  function Probe() {
    const { current, organizations, loading, connectionError, offline, refresh } = useOrg();
    return <button data-loading={loading} data-organizations={organizations.map(org => org.id).join(',')} onClick={() => void refresh()}>{`${current?.id ?? 'none'}:${connectionError}:${offline}`}</button>;
  }
  const render = () => act(async () => root.render(<OrgProvider><Probe /></OrgProvider>));
  await render();
  return render;
}

async function apiForCurrentModules() {
  return (await import('../services/api')).api;
}

function state() { return host.querySelector('button')?.textContent; }

beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  vi.resetModules();
  localStorage.clear();
  auth.token = 'test-token';
  vi.stubEnv('VITE_DEMO_MODE', 'false');
});

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
});

vi.mock('./useAuth', () => ({ useAuth: () => auth }));

describe('organization connectivity', () => {
  it('shows a retryable connection state rather than creating a fake organization outside demo mode', async () => {
    const api = await apiForCurrentModules();
    vi.spyOn(api, 'getOrganizations').mockRejectedValue(new TypeError('offline'));
    await renderProvider();
    expect(state()).toBe('none:true:false');
  });

  it('keeps the last verified organization mounted when a later refresh fails', async () => {
    const api = await apiForCurrentModules();
    vi.spyOn(api, 'getOrganizations')
      .mockResolvedValueOnce([{ id: 'real', name: 'Real', slug: 'real', role: 'ORG_ADMIN', status: 'ACTIVE' }])
      .mockRejectedValueOnce(new TypeError('offline'));
    await renderProvider();
    expect(state()).toBe('real:false:false');
    await act(async () => host.querySelector<HTMLButtonElement>('button')!.click());
    expect(state()).toBe('real:true:false');
  });

  it('keeps mounted child state while a refresh is pending or rejected', async () => {
    const api = await apiForCurrentModules();
    let rejectRefresh!: (reason: unknown) => void;
    const refresh = new Promise<never>((_, reject) => { rejectRefresh = reject; });
    vi.spyOn(api, 'getOrganizations')
      .mockResolvedValueOnce([{ id: 'real', name: 'Real', slug: 'real', role: 'ORG_ADMIN', status: 'ACTIVE' }])
      .mockReturnValueOnce(refresh);
    const [{ OrgProvider }, { useOrg }] = await Promise.all([import('./OrgContext'), import('./useOrg')]);
    function DirtyProbe() {
      const { current, connectionError, refresh: reload } = useOrg();
      const [draft, setDraft] = useState('unsaved note');
      return <><input aria-label="draft" value={draft} onChange={event => setDraft(event.target.value)} /><button onClick={() => void reload()}>{`${current?.id}:${connectionError}`}</button></>;
    }
    await act(async () => root.render(<OrgProvider><DirtyProbe /></OrgProvider>));
    expect(host.querySelector<HTMLInputElement>('[aria-label="draft"]')?.value).toBe('unsaved note');
    await act(async () => host.querySelector<HTMLButtonElement>('button')!.click());
    expect(host.querySelector<HTMLInputElement>('[aria-label="draft"]')?.value).toBe('unsaved note');
    await act(async () => rejectRefresh(new TypeError('offline')));
    expect(host.querySelector<HTMLInputElement>('[aria-label="draft"]')?.value).toBe('unsaved note');
    expect(state()).toBe('real:true');
  });

  it('clears the connection error when an initial retry succeeds', async () => {
    const api = await apiForCurrentModules();
    vi.spyOn(api, 'getOrganizations')
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce([{ id: 'real', name: 'Real', slug: 'real', role: 'ORG_ADMIN', status: 'ACTIVE' }]);
    await renderProvider();
    expect(state()).toBe('none:true:false');
    await act(async () => host.querySelector<HTMLButtonElement>('button')!.click());
    expect(state()).toBe('real:false:false');
  });

  it('treats transient HTTP failures as retryable and preserves a verified organization', async () => {
    const { api, ApiError } = await import('../services/api');
    vi.spyOn(api, 'getOrganizations')
      .mockResolvedValueOnce([{ id: 'real', name: 'Real', slug: 'real', role: 'ORG_ADMIN', status: 'ACTIVE' }])
      .mockRejectedValueOnce(new ApiError(503, { error: 'temporarily unavailable' }));
    await renderProvider();
    await act(async () => host.querySelector<HTMLButtonElement>('button')!.click());
    expect(state()).toBe('real:true:false');
  });

  it('uses the clearly-labelled synthetic organization only in explicit demo mode', async () => {
    vi.stubEnv('VITE_DEMO_MODE', 'true');
    vi.resetModules();
    const api = await apiForCurrentModules();
    vi.spyOn(api, 'getOrganizations').mockRejectedValue(new TypeError('offline'));
    await renderProvider();
    expect(state()).toBe('demo-offline:false:true');
  });
});


describe('organization account isolation', () => {
  it('ignores an initial old-account response while the next account is still loading', async () => {
    const oldRequest = deferred<Organization[]>();
    const newRequest = deferred<Organization[]>();
    const api = await apiForCurrentModules();
    vi.spyOn(api, 'getOrganizations')
      .mockReturnValueOnce(oldRequest.promise)
      .mockReturnValueOnce(newRequest.promise);
    const render = await renderProvider();
    auth.token = 'other-token';
    await render();
    await act(async () => oldRequest.resolve([realOrg]));
    expect(state()).toBe('none:false:false');
    expect(host.querySelector('button')?.dataset.loading).toBe('true');
    expect(host.querySelector('button')?.dataset.organizations).toBe('');
    expect((await import('./orgStore')).getActiveOrgId()).toBeNull();
    await act(async () => newRequest.resolve([otherOrg]));
    expect(state()).toBe('other:false:false');
  });

  it.each(['network', '503'])('clears the old current organization before a new account load that fails: %s', async failure => {
    const newRequest = deferred<Organization[]>();
    const { api, ApiError } = await import('../services/api');
    vi.spyOn(api, 'getOrganizations')
      .mockResolvedValueOnce([realOrg])
      .mockReturnValueOnce(newRequest.promise);
    const render = await renderProvider();
    expect(state()).toBe('real:false:false');
    auth.token = 'other-token';
    await render();
    expect(state()).toBe('none:false:false');
    expect(host.querySelector('button')?.dataset.organizations).toBe('');
    expect(host.querySelector('button')?.dataset.loading).toBe('true');
    await act(async () => newRequest.reject(failure === 'network'
      ? new TypeError('offline') : new ApiError(503, { error: 'unavailable' })));
    expect(state()).toBe('none:true:false');
    expect(host.querySelector('button')?.dataset.loading).toBe('false');
  });

  it.each(['success', 'forbidden'])('ignores a stale manual refresh after switching accounts: %s', async outcome => {
    const oldRefresh = deferred<Organization[]>();
    const { api, ApiError } = await import('../services/api');
    vi.spyOn(api, 'getOrganizations')
      .mockResolvedValueOnce([realOrg])
      .mockReturnValueOnce(oldRefresh.promise)
      .mockResolvedValueOnce([otherOrg]);
    const render = await renderProvider();
    await act(async () => host.querySelector<HTMLButtonElement>('button')!.click());
    auth.token = 'other-token';
    await render();
    expect(state()).toBe('other:false:false');
    await act(async () => {
      if (outcome === 'success') oldRefresh.resolve([realOrg]);
      else oldRefresh.reject(new ApiError(403, { error: 'forbidden' }));
    });
    expect(state()).toBe('other:false:false');
    expect((await import('./orgStore')).getActiveOrgId()).toBe('other');
  });

  it('does not reuse an unfinished refresh when returning to the original token', async () => {
    const oldRefresh = deferred<Organization[]>();
    const api = await apiForCurrentModules();
    vi.spyOn(api, 'getOrganizations')
      .mockResolvedValueOnce([realOrg])
      .mockReturnValueOnce(oldRefresh.promise)
      .mockResolvedValueOnce([otherOrg])
      .mockResolvedValueOnce([{ ...realOrg, id: 'new-membership' }]);
    const render = await renderProvider();
    await act(async () => host.querySelector<HTMLButtonElement>('button')!.click());
    auth.token = 'other-token';
    await render();
    auth.token = 'test-token';
    await render();
    await act(async () => oldRefresh.resolve([realOrg]));
    expect(state()).toBe('new-membership:false:false');
  });
});
