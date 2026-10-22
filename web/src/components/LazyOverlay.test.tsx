// @vitest-environment jsdom
import { act, useState, type ComponentType } from 'react';
import { createRoot } from 'react-dom/client';
import { expect, it, vi } from 'vitest';
import { LocaleProvider } from '../i18n/LocaleProvider';
import { lazyOverlay } from './lazyOverlay';

it('recovers a rejected overlay chunk without remounting the directory or losing its draft', async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {});
  const loader = vi.fn<() => Promise<{ default: ComponentType<{ onClose: () => void }> }>>()
    .mockRejectedValueOnce(new Error('Network chunk failure'))
    .mockResolvedValue({ default: ({ onClose }) => <section role="dialog">Loaded form<button onClick={onClose}>Done</button></section> });
  const Overlay = lazyOverlay(loader);
  const mounted = vi.fn();
  function Directory() {
    const [draft] = useState(() => { mounted(); return 'Unsubmitted search'; });
    const [open, setOpen] = useState(true);
    return <><input aria-label="Search" defaultValue={draft} />{open && <Overlay onClose={() => setOpen(false)} />}</>;
  }
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  try {
    await act(async () => root.render(<LocaleProvider><Directory /></LocaleProvider>));
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('Check your connection');
    const input = host.querySelector('input')!;
    input.value = 'Kept search draft';
    const retry = [...host.querySelectorAll('button')].find(b => b.textContent === 'Try again')!;
    await act(async () => retry.click());
    expect(loader).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('Loaded form');
    expect(host.querySelector('input')).toBe(input);
    expect(input.value).toBe('Kept search draft');
    expect(mounted).toHaveBeenCalledTimes(1);
    await act(async () => host.querySelector<HTMLButtonElement>('button')!.click());
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    expect(input.value).toBe('Kept search draft');
  } finally {
    await act(async () => root.unmount());
    host.remove();
    errorLog.mockRestore();
  }
});

it('keeps repeated chunk failures recoverable and explains the explicit reload before closing safely', async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {});
  const loader = vi.fn<() => Promise<{ default: ComponentType<{ onClose: () => void }> }>>()
    .mockRejectedValue(new Error('Cached import failure'));
  const Overlay = lazyOverlay(loader);
  function Directory() {
    const [open, setOpen] = useState(true);
    return <><input aria-label="Search" defaultValue="Saved draft" />{open && <Overlay onClose={() => setOpen(false)} />}</>;
  }
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const button = (label: string) => [...host.querySelectorAll('button')].find(b => b.textContent === label)!;
  try {
    await act(async () => root.render(<LocaleProvider><Directory /></LocaleProvider>));
    const input = host.querySelector('input')!;
    await act(async () => button('Try again').click());
    expect(loader).toHaveBeenCalledTimes(2);
    expect(host.querySelector('[role="alert"]')).not.toBeNull();
    const reload = button('Reload page');
    expect(reload).toBeDefined();
    expect(document.getElementById(reload.getAttribute('aria-describedby')!)?.textContent).toContain('Unsaved changes in open forms will be lost');
    expect(host.querySelector('input')).toBe(input);
    await act(async () => button('Close').click());
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    expect(input.value).toBe('Saved draft');
  } finally {
    await act(async () => root.unmount());
    host.remove();
    errorLog.mockRestore();
  }
});

it('reopens a loaded overlay without suspending on its chunk again', async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  const loader = vi.fn(async () => ({ default: ({ onClose }: { onClose: () => void }) => <section role="dialog">Loaded form<button onClick={onClose}>Done</button></section> }));
  const Overlay = lazyOverlay(loader);
  let toggle!: (open: boolean) => void;
  function Directory() {
    const [open, setOpen] = useState(true);
    toggle = setOpen;
    return open ? <Overlay onClose={() => setOpen(false)} /> : null;
  }
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  try {
    await act(async () => root.render(<LocaleProvider><Directory /></LocaleProvider>));
    expect(host.textContent).toContain('Loaded form');
    await act(async () => toggle(false));
    act(() => toggle(true));
    expect(host.textContent).toContain('Loaded form');
    expect(host.textContent).not.toContain('Loading');
    expect(loader).toHaveBeenCalledTimes(1);
  } finally {
    await act(async () => root.unmount());
    host.remove();
  }
});

it('reports a render error inside a loaded overlay as a crash, not a connection problem', async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {});
  const Broken = (): never => { throw new Error('Unexpected visit data'); };
  const Overlay = lazyOverlay(async () => ({ default: Broken }));
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  try {
    await act(async () => root.render(<LocaleProvider><Overlay onClose={() => {}} /></LocaleProvider>));
    const alert = host.querySelector('[role="alert"]')?.textContent ?? '';
    expect(alert).toContain('Something went wrong');
    expect(alert).not.toContain('Check your connection');
    expect([...host.querySelectorAll('button')].some(b => b.textContent === 'Try again')).toBe(false);
    expect(errorLog.mock.calls.some(call => String(call[0]).includes('Overlay crashed'))).toBe(true);
  } finally {
    await act(async () => root.unmount());
    host.remove();
    errorLog.mockRestore();
  }
});
