import { useEffect, useRef } from 'react';
import type React from 'react';

type DialogEntry = { close: React.MutableRefObject<() => void>; root: React.RefObject<HTMLDivElement | null> };
// Mount order defines the active dialog, including forms opened over a profile.
const stack: DialogEntry[] = [];
let originalOverflow = '';
const focusSelector = 'button:not(:disabled),a[href],input:not(:disabled):not([type="hidden"]),select:not(:disabled),textarea:not(:disabled),[tabindex]:not([tabindex="-1"])';

function panel(entry: DialogEntry) {
  const root = entry.root.current;
  return root?.querySelector<HTMLElement>('[role="dialog"]') ?? root;
}
function focusables(container: HTMLElement) {
  return [...container.querySelectorAll<HTMLElement>(focusSelector)].filter(element => {
    const style = getComputedStyle(element);
    return element.tabIndex >= 0 && !element.closest('[hidden],[inert],[aria-hidden="true"]') && style.display !== 'none' && style.visibility !== 'hidden';
  });
}
function focusPanel(entry: DialogEntry) {
  const target = panel(entry);
  if (!target) return;
  if (!target.hasAttribute('tabindex')) target.tabIndex = -1;
  target.focus({ preventScroll: true });
}
function onKeyDown(event: KeyboardEvent) {
  if (event.defaultPrevented || event.isComposing) return;
  const top = stack.at(-1);
  if (!top) return;
  if (event.key === 'Escape') {
    event.preventDefault();
    top.close.current();
  } else if (event.key === 'Tab') {
    const container = panel(top);
    if (!container) return;
    const items = focusables(container);
    const first = items[0], last = items.at(-1);
    if (!first || !last) { event.preventDefault(); focusPanel(top); return; }
    const current = document.activeElement;
    if (event.shiftKey && (current === first || !items.includes(current as HTMLElement))) {
      event.preventDefault(); last.focus();
    } else if (!event.shiftKey && (current === last || !items.includes(current as HTMLElement))) {
      event.preventDefault(); first.focus();
    }
  }
}
function onFocusIn(event: FocusEvent) {
  const top = stack.at(-1);
  const container = top && panel(top);
  if (top && container && !container.contains(event.target as Node)) focusPanel(top);
}

/** Escape/backdrop dismissal, focus containment and restoration, and scroll lock. */
export function useDismiss(onClose: () => void) {
  const close = useRef(onClose);
  close.current = onClose;
  const root = useRef<HTMLDivElement>(null);
  const pressedOnBackdrop = useRef(false);

  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const entry = { close, root };
    if (!stack.length) {
      originalOverflow = document.body.style.overflow;
      document.body.style.overflow = 'hidden';
      window.addEventListener('keydown', onKeyDown);
      document.addEventListener('focusin', onFocusIn);
    }
    stack.push(entry);
    focusPanel(entry);
    return () => {
      const wasTop = stack.at(-1) === entry;
      const index = stack.indexOf(entry);
      if (index !== -1) stack.splice(index, 1);
      if (!stack.length) {
        window.removeEventListener('keydown', onKeyDown);
        document.removeEventListener('focusin', onFocusIn);
        document.body.style.overflow = originalOverflow;
      }
      if (wasTop) {
        if (opener?.isConnected) opener.focus({ preventScroll: true });
        else if (stack.length) focusPanel(stack[stack.length - 1]);
      }
    };
  }, []);

  return {
    ref: root,
    onMouseDown: (event: React.MouseEvent) => { pressedOnBackdrop.current = event.target === event.currentTarget; },
    onClick: (event: React.MouseEvent) => {
      if (pressedOnBackdrop.current && event.target === event.currentTarget && stack.at(-1)?.root === root) close.current();
      pressedOnBackdrop.current = false;
    },
  };
}
