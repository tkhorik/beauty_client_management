import { clientsClaim, setCacheNameDetails } from 'workbox-core';
import { cleanupOutdatedCaches, matchPrecache, precacheAndRoute } from 'workbox-precaching';
import { NavigationRoute, registerRoute } from 'workbox-routing';
import { CacheFirst, NetworkOnly } from 'workbox-strategies';
import { ExpirationPlugin } from 'workbox-expiration';
import type { ManifestEntry } from 'workbox-build';

declare const self: ServiceWorkerGlobalScope & { __WB_MANIFEST: ManifestEntry[] };

setCacheNameDetails({ prefix: 'aura-pwa' });
// Never precache index.html: navigation URLs may contain reset/invitation tokens.
precacheAndRoute(self.__WB_MANIFEST, { ignoreURLParametersMatching: [] });
cleanupOutdatedCaches();
clientsClaim();

self.addEventListener('message', event => {
  if (event.data?.type === 'SKIP_WAITING') void self.skipWaiting();
});

const network = new NetworkOnly({ fetchOptions: { cache: 'no-store' } });
const apiBase = new URL(import.meta.env.VITE_API_BASE_URL || '/api', self.location.origin);
const apiPath = apiBase.pathname.replace(/\/+$/, '');
// NetworkOnly also bypasses the browser HTTP cache. No GET exception for private data.
registerRoute(({ url }) =>
  (url.origin === apiBase.origin && (url.pathname === apiPath || url.pathname.startsWith(`${apiPath}/`)))
  || (url.origin === self.location.origin && /^\/(api|uploads)(\/|$)/.test(url.pathname)), network);

registerRoute(({ url, request }) => url.origin === self.location.origin
  && !url.search && !request.headers.has('Authorization') && !request.headers.has('X-Organization-Id')
  && (/^\/assets\/.+\.(js|css|png|svg|woff2)$/.test(url.pathname)
    || /^\/fonts\/[\w-]+\.woff2$/.test(url.pathname)
    || /^\/pwa\/[\w-]+\.png$/.test(url.pathname)
    || url.pathname === '/favicon.svg' || url.pathname === '/icons.svg'),
new CacheFirst({
  cacheName: 'aura-pwa-public-v1',
  plugins: [new ExpirationPlugin({ maxEntries: 80, maxAgeSeconds: 30 * 24 * 60 * 60, purgeOnQuotaError: true })],
}));

const navigation = new NavigationRoute(async options => {
  try {
    return await network.handle(options);
  } catch {
    return await matchPrecache('/offline.html')
      ?? new Response('Offline. Reconnect and retry.', { status: 503, headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
  }
}, { denylist: [/^\/(api|uploads|healthz|health)(\/|$)/, /^\/\.well-known\//] });
registerRoute(navigation);
