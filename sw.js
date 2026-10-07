// Prompter Voz — service worker
// Objetivo: abrir al instante y gastar cero datos después de la primera vez.
const APP = 'prompter-app-v10';      // la app (se actualiza en segundo plano)
const LIB = 'prompter-lib-v1';       // librerías pesadas (vosk.js ~6 MB): se bajan UNA vez y se quedan
const CORE = ['./', 'index.html', 'manifest.json', 'icons/icon-192.png', 'icons/icon-512.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(APP).then(c => c.addAll(CORE)));
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(
    ks.filter(k => k !== APP && k !== LIB).map(k => caches.delete(k)))));
  self.clients.claim();
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;
  if (url.pathname.includes('/models/')) return;          // el modelo de voz lo guarda Vosk en el cel

  // Librerías e íconos: primero lo guardado (no gasta datos)
  if (url.pathname.includes('/lib/') || url.pathname.includes('/icons/')) {
    e.respondWith(caches.open(LIB).then(async c => {
      const hit = await c.match(req);
      if (hit) return hit;
      const res = await fetch(req);
      if (res.ok) c.put(req, res.clone());
      return res;
    }));
    return;
  }

  // La app: se abre al instante con lo guardado y se actualiza sola en segundo plano
  e.respondWith(caches.open(APP).then(async c => {
    const hit = await c.match(req, { ignoreSearch: true });
    const net = fetch(req).then(res => { if (res.ok) c.put(req, res.clone()); return res; }).catch(() => null);
    if (hit) { e.waitUntil(net); return hit; }
    return (await net) || (await c.match('index.html')) || Response.error();
  }));
});
