import { useSyncExternalStore } from 'react';

interface DeferredInstallPrompt extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }>;
}

export type InstallCapability = 'prompt' | 'ios' | 'none';

let deferredPrompt: DeferredInstallPrompt | null = null;
let installed = false;
let initialised = false;
const listeners = new Set<() => void>();

function emit() { listeners.forEach(listener => listener()); }

export function isStandalone(): boolean {
  return window.matchMedia?.('(display-mode: standalone)').matches === true
    || (navigator as Navigator & { standalone?: boolean }).standalone === true;
}

export function isIos(): boolean {
  return /iPhone|iPad|iPod/i.test(navigator.userAgent)
    || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
}

export function installCapability(): InstallCapability {
  if (installed || isStandalone()) return 'none';
  if (deferredPrompt) return 'prompt';
  return isIos() ? 'ios' : 'none';
}

export function isInstalledOrStandalone(): boolean {
  return installed || isStandalone();
}

/** Called before React mounts so Chromium cannot show its own install prompt. */
export function initInstallPrompt(): void {
  if (initialised || typeof window === 'undefined') return;
  initialised = true;
  window.addEventListener('beforeinstallprompt', event => {
    event.preventDefault();
    deferredPrompt = event as DeferredInstallPrompt;
    emit();
  });
  window.addEventListener('appinstalled', () => {
    deferredPrompt = null;
    installed = true;
    emit();
  });
  window.matchMedia?.('(display-mode: standalone)').addEventListener('change', emit);
}

export async function showInstallPrompt(): Promise<'accepted' | 'dismissed' | 'unavailable'> {
  const prompt = deferredPrompt;
  if (!prompt) return 'unavailable';
  deferredPrompt = null;
  emit();
  try {
    await prompt.prompt();
    const { outcome } = await prompt.userChoice;
    return outcome;
  } catch {
    // Browsers can reject a stale prompt (for example after a navigation).
    // Installation remains optional, so do not turn that into an app error.
    return 'unavailable';
  } finally {
    emit();
  }
}

export function useInstallCapability(): InstallCapability {
  return useSyncExternalStore(
    listener => { listeners.add(listener); return () => listeners.delete(listener); },
    installCapability,
    () => 'none',
  );
}

/** Keeps surrounding promotional UI in sync with appinstalled/display-mode. */
export function useIsInstalledOrStandalone(): boolean {
  return useSyncExternalStore(
    listener => { listeners.add(listener); return () => listeners.delete(listener); },
    isInstalledOrStandalone,
    () => false,
  );
}
