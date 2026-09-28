/* =============================================================================
 * 说一嘴 · Service Worker（决策 D10：PWA 离线可打开）
 *
 * 策略：
 *   1. app shell（index.html / styles.css / app.js / manifest / 图标）预缓存 → 断网也能打开界面；
 *   2. 所有 /api/ 请求一律走网络，绝不缓存（待办数据必须实时，且不能把他人数据留缓存）；
 *   3. 其它同源 GET 资源：缓存优先 + 后台更新（stale-while-revalidate）；
 *   4. 导航请求：网络优先，失败时回落到缓存的 index.html（单页应用 fallback）。
 *
 * 修改静态资源后请把 CACHE 版本号 +1，否则客户端会一直用旧缓存。
 * ========================================================================== */
'use strict';

const CACHE = 'saym-pwa-v1';

// 预缓存清单：必须与 pwa-static/ 目录下真实存在的文件名完全一致
const APP_SHELL = [
  './',
  './index.html',
  './styles.css',
  './app.js',
  './manifest.webmanifest',
  './icon-192.png',
  './icon-512.png'
];

const INDEX_URL = new URL('./index.html', self.location.href).href;

// ---- 安装：预缓存 app shell（逐个缓存，单个失败不影响整体安装） ----
self.addEventListener('install', function (event) {
  event.waitUntil(
    caches.open(CACHE).then(function (cache) {
      return Promise.all(APP_SHELL.map(function (url) {
        return cache.add(new Request(url, { cache: 'reload' })).catch(function (err) {
          console.warn('[sw] 预缓存失败：' + url, err);
        });
      }));
    }).then(function () { return self.skipWaiting(); })
  );
});

// ---- 激活：清理旧版本缓存，立即接管页面 ----
self.addEventListener('activate', function (event) {
  event.waitUntil(
    caches.keys().then(function (keys) {
      return Promise.all(keys.map(function (k) {
        return k === CACHE ? null : caches.delete(k);
      }));
    }).then(function () { return self.clients.claim(); })
  );
});

self.addEventListener('fetch', function (event) {
  const req = event.request;

  // 只处理 GET；POST/PUT/DELETE 直接放行到网络
  if (req.method !== 'GET') return;

  const url = new URL(req.url);

  // 跨域请求不拦截（本项目无外部依赖，稳妥起见直接放过）
  if (url.origin !== self.location.origin) return;

  // 1) /api/ 一律走网络，不缓存、不回落缓存
  if (url.pathname.indexOf('/api/') === 0) return;

  // 2) 页面导航：网络优先，失败回落到缓存的 index.html（离线可打开）
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req).then(function (res) {
        // 网络可用时顺手更新 shell 里的 index.html
        const copy = res.clone();
        caches.open(CACHE).then(function (cache) { cache.put(INDEX_URL, copy); });
        return res;
      }).catch(function () {
        return caches.match(INDEX_URL).then(function (hit) {
          return hit || caches.match('./') || new Response(
            '<!DOCTYPE html><html lang="zh-CN"><meta charset="utf-8"><title>说一嘴</title>' +
            '<body style="font-family:sans-serif;padding:24px">离线且没有可用缓存，请连网后重试。</body></html>',
            { headers: { 'Content-Type': 'text/html; charset=utf-8' } }
          );
        });
      })
    );
    return;
  }

  // 3) 其它同源静态资源：缓存优先 + 后台更新
  event.respondWith(
    caches.match(req).then(function (hit) {
      const network = fetch(req).then(function (res) {
        if (res && res.status === 200 && res.type === 'basic') {
          const copy = res.clone();
          caches.open(CACHE).then(function (cache) { cache.put(req, copy); });
        }
        return res;
      }).catch(function () {
        return hit || Response.error();
      });
      return hit || network;
    })
  );
});
