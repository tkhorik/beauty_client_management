// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { expect, it, vi } from 'vitest';
import { LocaleProvider } from '../i18n/LocaleProvider';
import { api } from '../services/api';
import { InviteAcceptDialog } from './InviteAcceptDialog';

vi.mock('../auth/OrgContext', () => ({ useOrg: () => ({ refresh: vi.fn(async () => {}), select: vi.fn() }) }));

it('ignores Escape and backdrop clicks while the join request is in flight', async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  vi.spyOn(api, 'previewInviteLink').mockResolvedValue({ valid: true, organization: { name: 'Salon' } } as Awaited<ReturnType<typeof api.previewInviteLink>>);
  let fail!: (reason: unknown) => void;
  vi.spyOn(api, 'acceptInviteLink').mockReturnValue(new Promise((_, reject) => { fail = reject; }));
  const onDone = vi.fn();
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  try {
    await act(async () => root.render(<LocaleProvider><InviteAcceptDialog token="t" onDone={onDone} /></LocaleProvider>));
    const join = [...host.querySelectorAll('button')].find(b => b.textContent === 'Join')!;
    await act(async () => join.click());
    await act(async () => { window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); });
    const backdrop = host.querySelector<HTMLElement>('.auth-dialog-backdrop')!;
    await act(async () => {
      backdrop.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
      backdrop.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(onDone).not.toHaveBeenCalled();
    await act(async () => fail(new Error('Network down')));
    expect(host.querySelector('[role="alert"]')).not.toBeNull();
    expect(onDone).not.toHaveBeenCalled();
  } finally {
    await act(async () => root.unmount());
    host.remove();
    vi.restoreAllMocks();
  }
});
