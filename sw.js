// TexJAHC — service worker
// Objetivo: abrir al instante, gastar cero datos después de la primera vez
// y actualizarse sola cuando hay versión nueva.
const APP = 'texjahc-app-v24';      // la app (súbele el número si cambias este archivo)
const LIB = 'prompter-lib-v1';       // librerías pesadas (vosk.js, ffmpeg): se bajan UNA vez y se quedan
const CORE = ['./', 'index.html', 'manifest.json', 'icons/icon-192.png', 'icons/icon-512.png'];

self.addEventListener('install', e => {
  // cache:'reload' = siempre la versión publicada en GitHub Pages, nunca una copia vieja del navegador
  e.waitUntil(caches.open(APP).then(c => c.addAll(CORE.map(u => new Request(u, { cache: 'reload' })))));
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(
    ks.filter(k => k !== APP && k !== LIB).map(k => caches.delete(k)))));
  self.clients.claim();
});

let pendingUpdate = false;
const notifyUpdate = () => {
  pendingUpdate = true;
  // la página recién abierta puede no estar lista: avisamos ahora y otra vez en un momento
  const send = () => self.clients.matchAll({ type: 'window', includeUncontrolled: true })
    .then(cs => cs.forEach(c => c.postMessage({ type: 'app-updated' })));
  send(); setTimeout(send, 1500); setTimeout(send, 4000);
};
// la página pregunta al abrir: "¿hay versión nueva?"
self.addEventListener('message', e => {
  if (e.data === 'hello' && pendingUpdate && e.source) e.source.postMessage({ type: 'app-updated' });
  if (e.data === 'skip-waiting') self.skipWaiting();
  if (e.data === 'check-update') e.waitUntil(checkForUpdate());
});

// Revisa en GitHub Pages si index.html cambió (para la app que se queda abierta mucho tiempo)
let checking = null;
const checkForUpdate = () => checking || (checking = (async () => {
  try {
    const c = await caches.open(APP);
    const old = (await c.match('./')) || (await c.match('index.html'));
    const res = await fetch('index.html', { cache: 'no-store' });
    if (!res.ok) return;
    const newT = await res.clone().text();
    const oldT = old ? await old.text() : null;
    if (oldT === newT) return;
    const hdr = { headers: { 'Content-Type': 'text/html; charset=utf-8' } };
    await c.put('./', new Response(newT, hdr));
    await c.put('index.html', new Response(newT, hdr));
    if (oldT !== null) notifyUpdate();
  } catch (_) {} finally { checking = null; }
})());

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;
  if (url.pathname.includes('/models/') || url.pathname.includes('/descargas/')) return;

  // Librerías pesadas: primero lo guardado (no gasta datos)
  if (url.pathname.includes('/lib/')) {
    e.respondWith(caches.open(LIB).then(async c => {
      const hit = await c.match(req);
      if (hit) return hit;
      const res = await fetch(req);
      if (res.ok) c.put(req, res.clone());
      return res;
    }));
    return;
  }

  // La app: abre al instante con lo guardado; en segundo plano baja la nueva
  // y si cambió, avisa para recargar.
  e.respondWith(caches.open(APP).then(async c => {
    const hit = await c.match(req, { ignoreSearch: true });
    const hitCopy = hit ? hit.clone() : null;     // para comparar después (el original se entrega a la página)
    const net = fetch(req, { cache: 'no-cache' }).then(async res => {
      if (!res.ok) return res;
      if (hit && req.mode === 'navigate') {
        const [oldT, newT] = await Promise.all([hitCopy.text(), res.clone().text()]);
        await c.put(req, res.clone());
        if (oldT !== newT) notifyUpdate();
      } else {
        await c.put(req, res.clone());
      }
      return res;
    }).catch(() => null);
    if (hit) { e.waitUntil(net); return hit; }
    return (await net) || (await c.match('index.html')) || Response.error();
  }));
});
