import { registerSW } from 'virtual:pwa-register';

let initialised = false;
let needsUpdate = false;
let reloadConsented = false;
let controllerUpdated = false;
let update: ((reloadPage?: boolean) => Promise<void>) | undefined;
const listeners = new Set<() => void>();

export const getUpdateAvailable = () => needsUpdate;
export function subscribeUpdates(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

/** Failure is deliberately nonfatal: the site still works as a normal website. */
export function initServiceWorker() {
  if (initialised || !import.meta.env.PROD || !('serviceWorker' in navigator)) return;
  initialised = true;
  try {
    update = registerSW({
      onNeedRefresh() { needsUpdate = true; listeners.forEach(listener => listener()); },
      onNeedReload() {
        // Another tab may activate the waiting worker. Never discard this
        // tab's forms without its own explicit confirmation.
        controllerUpdated = true;
        if (reloadConsented) window.location.reload();
        else { needsUpdate = true; listeners.forEach(listener => listener()); }
      },
      onRegisteredSW(_url, registration) {
        // Recheck on returning to the app, without forcing activation or reload.
        document.addEventListener('visibilitychange', () => {
          if (document.visibilityState === 'visible') void registration?.update().catch(() => {});
        });
      },
      onRegisterError() { /* Installation is optional; do not interrupt client work. */ },
    });
  } catch { /* Browsers may deny service workers in restricted/private contexts. */ }
}

export async function applyUpdate() {
  if (controllerUpdated) { window.location.reload(); return; }
  reloadConsented = true;
  try { await update?.(true); }
  catch (error) { reloadConsented = false; throw error; }
}
