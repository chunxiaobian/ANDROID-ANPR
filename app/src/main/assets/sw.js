const CACHE_NAME = 'android-anpr-shell-v1';
const SHELL_ASSETS = [
  '/',
  '/manifest.webmanifest',
  '/pwa-icon-192.png',
  '/pwa-icon-512.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(SHELL_ASSETS))
      .catch(() => {})
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;

  // Never intercept live streams, APIs, snapshots, or file downloads/streams
  const p = url.pathname;
  if (
    p.startsWith('/video/') ||
    p.startsWith('/audio/') ||
    p.startsWith('/api/') ||
    p.startsWith('/files') ||
    p.startsWith('/control/') ||
    p === '/status' ||
    p === '/info.json' ||
    p === '/set' ||
    p === '/snapshot' ||
    p === '/record/status'
  ) {
    return;
  }

  // Network-first for HTML shell so APK updates are picked up immediately
  if (p === '/' || req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then((res) => {
          if (res && res.ok) {
            const copy = res.clone();
            caches.open(CACHE_NAME).then((c) => c.put('/', copy)).catch(() => {});
          }
          return res;
        })
        .catch(() => caches.match('/'))
    );
    return;
  }

  // Cache-first for static PWA assets
  if (SHELL_ASSETS.includes(p)) {
    event.respondWith(
      caches.match(req).then((cached) => cached || fetch(req))
    );
  }
});
