import { useEffect, useRef } from 'react';
import type React from 'react';

// Open dialogs in mount order. Escape closes only the last one, so the edit
// form stacked on a client card closes without taking the card with it.
const stack: Array<React.MutableRefObject<() => void>> = [];

function onKeyDown(e: KeyboardEvent) {
  // A menu inside the dialog (LanguageSelector) handles its own Escape and
  // marks it handled; an IME composition uses Escape to cancel itself.
  if (e.key !== 'Escape' || e.defaultPrevented || e.isComposing) return;
  const top = stack[stack.length - 1];
  if (!top) return;
  e.preventDefault();
  top.current();
}

/**
 * Closes an overlay on Escape or on a click on its backdrop. Spread the
 * returned props on the full-screen backdrop element.
 *
 * A backdrop click counts only when the press also started on the backdrop:
 * selecting text in a field and releasing outside the panel is not a request
 * to throw the form away.
 */
export function useDismiss(onClose: () => void) {
  const close = useRef(onClose);
  close.current = onClose;
  const pressedOnBackdrop = useRef(false);

  useEffect(() => {
    const entry = close;
    if (stack.length === 0) window.addEventListener('keydown', onKeyDown);
    stack.push(entry);
    return () => {
      stack.splice(stack.indexOf(entry), 1);
      if (stack.length === 0) window.removeEventListener('keydown', onKeyDown);
    };
  }, []);

  return {
    onMouseDown: (e: React.MouseEvent) => {
      pressedOnBackdrop.current = e.target === e.currentTarget;
    },
    onClick: (e: React.MouseEvent) => {
      if (pressedOnBackdrop.current && e.target === e.currentTarget) close.current();
      pressedOnBackdrop.current = false;
    },
  };
}
