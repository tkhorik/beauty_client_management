// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { api } from './services/api';
import { LocaleProvider } from './i18n/LocaleProvider';
import type { Client, Visit } from './types';
import type { ComponentProps } from 'react';
import type { Header } from './components/Header';
import type { ClientDetailModal } from './components/ClientDetailModal';
import type { NewVisitModal } from './components/NewVisitModal';

let header: ComponentProps<typeof Header>;
let detail: ComponentProps<typeof ClientDetailModal>;
let newVisit: ComponentProps<typeof NewVisitModal>;
vi.mock('./components/Header', () => ({ Header: (props: typeof header) => { header = props; return null; } }));
vi.mock('./components/ClientDetailModal', () => ({ ClientDetailModal: (props: typeof detail) => {
  detail = props;
  return <output>{props.client.name}:{props.visitsLoading ? 'loading' : props.visitsError ? 'error' : props.visits.map(v => v.id).join(',')}</output>;
} }));
vi.mock('./components/NewVisitModal', () => ({ NewVisitModal: (props: typeof newVisit) => { newVisit = props; return null; } }));
vi.mock('./components/ClientCard', () => ({ ClientCard: ({ client, onSelect }: { client: Client; onSelect: (client: Client) => void }) => <button onClick={() => onSelect(client)}>{client.name}</button> }));
vi.mock('./auth/AuthContext', () => ({ useAuth: () => ({ token: 'test', user: { emailVerified: true }, logout: () => {} }) }));
vi.mock('./auth/OrgContext', () => ({ useOrg: () => ({ current: { id: 'org', role: 'ORG_ADMIN' }, organizations: [] }) }));

const client = (id: string): Client => ({ id, name: id, phone: '123', tags: [], customFields: {}, totalVisits: 1, createdAt: '2020-01-01', updatedAt: '2020-01-01' });
const visits = (id: string): Visit[] => [{ id: `${id}-visit`, clientId: id, visitDateTime: '2020-01-01T10:00', durationMinutes: 60, procedureNotes: '', status: 'COMPLETED', attachments: [], createdAt: '2020-01-01' }];
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
let root: Root;
let host: HTMLDivElement;
async function select(id: string) {
  await act(async () => { Array.from(host.querySelectorAll('button')).find(b => b.textContent === id)!.click(); });
}
beforeEach(async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  localStorage.clear();
  vi.spyOn(api, 'getClients').mockResolvedValue([client('A'), client('B')]);
  vi.spyOn(api, 'getClient').mockImplementation(async id => client(id));
  vi.spyOn(api, 'getVisits').mockImplementation(async id => visits(id ?? ''));
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  await act(async () => root.render(<LocaleProvider><App /></LocaleProvider>));
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); vi.restoreAllMocks(); });

it('only the latest search/tag request may update results or loading', async () => {
  const old = deferred<Client[]>(), latest = deferred<Client[]>();
  vi.mocked(api.getClients).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);
  await act(async () => header.onSearchChange('alice'));
  await act(async () => header.onTagSelect('VIP'));
  expect(api.getClients).toHaveBeenLastCalledWith('alice', 'VIP');
  await act(async () => old.resolve([client('old')]));
  expect(host.querySelector('button')).toBeNull(); // Latest request is still loading.
  await act(async () => latest.resolve([client('latest')]));
  expect(host.textContent).toContain('latest');
  const slow = deferred<Client[]>(), fast = deferred<Client[]>();
  vi.mocked(api.getClients).mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise);
  await act(async () => header.onSearchChange('b'));
  await act(async () => header.onSearchChange('be'));
  await act(async () => fast.resolve([client('newest')]));
  await act(async () => slow.resolve([client('stale')]));
  expect(host.textContent).toBe('newest');
});

it('clears old history immediately and ignores a late refresh after changing client', async () => {
  await select('A');
  expect(host.querySelector('output')?.textContent).toBe('A:A-visit');
  const staleProfile = deferred<Client>(), staleHistory = deferred<Visit[]>(), current = deferred<Visit[]>();
  vi.mocked(api.getClient).mockReturnValueOnce(staleProfile.promise);
  vi.mocked(api.getVisits).mockReturnValueOnce(staleHistory.promise).mockReturnValueOnce(current.promise);
  await act(async () => detail.onRefresh());
  await act(async () => detail.onClose());
  await select('B');
  expect(detail.visits).toEqual([]);
  expect(host.querySelector('output')?.textContent).toBe('B:loading');
  await act(async () => current.resolve(visits('B')));
  await act(async () => { staleProfile.resolve(client('A')); staleHistory.resolve(visits('A')); });
  expect(host.querySelector('output')?.textContent).toBe('B:B-visit');
});

it('handles reversed initial selection responses and errors without showing empty history', async () => {
  const old = deferred<Visit[]>(), current = deferred<Visit[]>();
  vi.mocked(api.getVisits).mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise);
  await select('A');
  await act(async () => detail.onClose());
  await select('B');
  await act(async () => current.reject(new Error('unavailable')));
  expect(host.querySelector('output')?.textContent).toBe('B:error');
  await act(async () => old.resolve(visits('A')));
  expect(host.querySelector('output')?.textContent).toBe('B:error');
  await act(async () => detail.onRefresh());
  expect(host.querySelector('output')?.textContent).toBe('B:B-visit');
});

it('a delayed mutation refresh uses the current filters and selection', async () => {
  await select('A');
  await act(async () => header.onOpenNewVisit());
  const oldSuccess = newVisit.onSuccess;
  await act(async () => detail.onClose());
  await act(async () => header.onSearchChange('current'));
  await select('B');
  await act(async () => oldSuccess());
  expect(api.getClients).toHaveBeenLastCalledWith('current', '');
  expect(api.getVisits).toHaveBeenLastCalledWith('B');
  expect(host.querySelector('output')?.textContent).toBe('B:B-visit');
});
