// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

let root: Root;
let host: HTMLDivElement;

async function renderProvider() {
  const [{ OrgProvider }, { useOrg }] = await Promise.all([
    import('./OrgContext'),
    import('./useOrg'),
  ]);
  function Probe() {
    const { current, connectionError, offline, refresh } = useOrg();
    return <button onClick={() => void refresh()}>{`${current?.id ?? 'none'}:${connectionError}:${offline}`}</button>;
  }
  await act(async () => root.render(<OrgProvider><Probe /></OrgProvider>));
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
  vi.stubEnv('VITE_DEMO_MODE', 'false');
});

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
});

vi.mock('./useAuth', () => ({ useAuth: () => ({ token: 'test-token' }) }));

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
