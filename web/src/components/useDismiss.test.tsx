// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useDismiss } from './useDismiss';

function Dialog({ id, onClose }: { id: string; onClose: () => void }) {
  const dismiss = useDismiss(onClose);
  return (
    <div data-testid={`${id}-backdrop`} {...dismiss}>
      <div data-testid={`${id}-panel`}>panel</div>
    </div>
  );
}

let root: Root;
let host: HTMLDivElement;

beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });

const el = (id: string) => host.querySelector<HTMLElement>(`[data-testid="${id}"]`)!;
const press = (target: HTMLElement, release: HTMLElement = target) => {
  target.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
  release.dispatchEvent(new MouseEvent('click', { bubbles: true }));
};
const escape = () => window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true }));

it('Escape closes only the topmost dialog', async () => {
  const outer = vi.fn();
  const inner = vi.fn();
  await act(async () => root.render(<><Dialog id="outer" onClose={outer} /><Dialog id="inner" onClose={inner} /></>));
  escape();
  expect(inner).toHaveBeenCalledTimes(1);
  expect(outer).not.toHaveBeenCalled();

  await act(async () => root.render(<Dialog id="outer" onClose={outer} />));
  escape();
  expect(outer).toHaveBeenCalledTimes(1);
});

it('a click on the backdrop closes, a click on the panel does not', async () => {
  const onClose = vi.fn();
  await act(async () => root.render(<Dialog id="d" onClose={onClose} />));
  press(el('d-panel'));
  expect(onClose).not.toHaveBeenCalled();
  press(el('d-backdrop'));
  expect(onClose).toHaveBeenCalledTimes(1);
});

it('a press that starts inside the panel and ends on the backdrop does not close', async () => {
  const onClose = vi.fn();
  await act(async () => root.render(<Dialog id="d" onClose={onClose} />));
  el('d-panel').dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
  el('d-backdrop').dispatchEvent(new MouseEvent('click', { bubbles: true }));
  expect(onClose).not.toHaveBeenCalled();
});

it('an Escape already handled by a menu inside the dialog is ignored', async () => {
  const onClose = vi.fn();
  await act(async () => root.render(<Dialog id="d" onClose={onClose} />));
  const handled = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true });
  handled.preventDefault();
  window.dispatchEvent(handled);
  expect(onClose).not.toHaveBeenCalled();
});

function FocusDialog({ id, onClose, children }: { id: string; onClose: () => void; children?: React.ReactNode }) {
  const dismiss = useDismiss(onClose);
  return <div {...dismiss}><section role="dialog" aria-label={id} data-testid={id} tabIndex={-1}>
    <button data-testid={`${id}-first`}>First</button><button data-testid={`${id}-last`}>Last</button>
  </section>{children}</div>;
}

it('contains keyboard focus and restores the trigger and body scroll when closed', async () => {
  const trigger = document.createElement('button');
  document.body.append(trigger);
  trigger.focus();
  const previousOverflow = document.body.style.overflow;
  await act(async () => root.render(<FocusDialog id="focus" onClose={() => {}} />));
  expect(document.activeElement).toBe(el('focus'));
  expect(document.body.style.overflow).toBe('hidden');
  el('focus-last').focus();
  const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
  el('focus-last').dispatchEvent(tab);
  expect(tab.defaultPrevented).toBe(true);
  expect(document.activeElement).toBe(el('focus-first'));
  el('focus-first').dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }));
  expect(document.activeElement).toBe(el('focus-last'));
  trigger.focus();
  expect(document.activeElement).toBe(el('focus'));
  await act(async () => root.render(null));
  expect(document.activeElement).toBe(trigger);
  expect(document.body.style.overflow).toBe(previousOverflow);
  trigger.remove();
});

it('restores focus to the parent dialog while a nested dialog closes', async () => {
  await act(async () => root.render(<FocusDialog id="parent" onClose={() => {}} />));
  el('parent-last').focus();
  await act(async () => root.render(<FocusDialog id="parent" onClose={() => {}}><FocusDialog id="nested" onClose={() => {}} /></FocusDialog>));
  expect(document.activeElement).toBe(el('nested'));
  await act(async () => root.render(<FocusDialog id="parent" onClose={() => {}} />));
  expect(document.activeElement).toBe(el('parent-last'));
  expect(document.body.style.overflow).toBe('hidden');
});
