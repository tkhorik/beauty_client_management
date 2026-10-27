import { useCallback, useEffect, useLayoutEffect, useRef } from 'react';

/** Guards dismissal without replacing useDismiss's focus and overlay behavior. */
export function useDraftGuard(dirty: boolean, busy: boolean, onClose: () => void, message: string): () => void {
  const current = useRef({ dirty, busy, onClose, message });
  // Refresh after commit, not during render, so rendering stays pure; layout
  // effects run before any event handler could read the ref.
  useLayoutEffect(() => {
    current.current = { dirty, busy, onClose, message };
  });

  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (!current.current.dirty && !current.current.busy) return;
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, []);

  return useCallback(() => {
    const value = current.current;
    if (value.busy) return;
    if (!value.dirty || window.confirm(value.message)) value.onClose();
  }, []);
}
