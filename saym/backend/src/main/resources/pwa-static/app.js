/* =============================================================================
 * 说一嘴 · Web/PWA 应用逻辑（决策 D10，信息架构见需求文档 §5.2）
 *
 * 约束（务必保持）：
 *   1. 原生 JS，无 npm / 无构建工具 / 无任何外部 CDN 资源，纯离线可用。
 *   2. 所有插入 DOM 的用户内容一律走 textContent（h() 里的 text），禁止 innerHTML 拼接。
 *   3. 每个功能块都标注对应需求编号（F1.x / F2.x / F3.x / F4.x / F5.x / F6.x）。
 *
 * 后端契约为唯一事实来源（backend/src/main/kotlin/com/saym/backend/Handlers.kt）：
 *   - 请求头 X-Device-Id 标识设备；POST/PUT 带 Content-Type: application/json
 *   - /api/extract 返回的 drafts 可能是「JSON 字符串包着数组」，这里做了兼容解析
 * ========================================================================== */
'use strict';

(function () {

  // ==========================================================================
  // 0. 常量与本地存储键
  // ==========================================================================
  const STORE = {
    deviceId: 'saym.deviceId',        // F4.1 设备绑定用的本地设备 ID
    baseUrl: 'saym.baseUrl',          // 后端地址（空串 = 同源）
    lastSyncAt: 'saym.lastSyncAt',    // F4.3 上次同步时间戳
    boundDevice: 'saym.boundDevice',  // 已经成功 POST /api/devices 过的设备 ID
    cacheTodos: 'saym.cache.todos',   // F4.4 离线兜底：最近一次拉到的待办
    cacheStats: 'saym.cache.stats',   // F3.8 离线兜底：最近一次看板数据
    installDismissed: 'saym.installDismissed'
  };

  const IMAGE_MAX_EDGE = 1280;      // F1.3 图片最长边压缩到 1280px（与后端 routing.imageMaxEdge 一致）
  const IMAGE_QUALITY = 0.8;        // F1.3 JPEG 质量 0.8
  const CONFIRM_THRESHOLD = 0.7;    // F2.6 confidence < 0.7 → 高亮「⚠ 请确认」

  // F3.1 四个分组对应的后端地址：scope=all 表示「多端共享视图」（自用单人，手机/手表/Web 共用一份数据）
  const TODO_URLS = {
    today: '/api/todos/today?scope=all',
    week: '/api/todos/week?scope=all',
    overdue: '/api/todos/overdue?scope=all',
    all: '/api/todos?scope=all'
  };

  const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

  // ==========================================================================
  // 1. 全局状态
  // ==========================================================================
  const state = {
    deviceId: '',
    baseUrl: '',                 // '' = 同源
    view: 'input',               // input / todos / stats / settings
    scope: 'today',              // F3.1 当前分组
    todos: [],                   // 当前列表
    selected: new Set(),         // F3.2 多选集合
    selectMode: false,           // 多选模式开关
    drafts: [],                  // F2.5 提炼草稿
    images: [],                  // 待上传图片（已压缩）
    lastRequest: null,           // 最近一次提炼请求（供「重新提炼 / 重试」复用）
    lastExtractMeta: null,       // 提炼返回的模型信息
    savedByInput: {},            // { inputId: [todoId...] } 用于回填 F1.4
    extractErrors: [],           // 本轮提炼的部分失败信息
    lastInputIds: [],            // 本轮提炼涉及的暂存输入 id
    editTarget: null,            // F3.3 正在编辑的待办
    installEvent: null,          // beforeinstallprompt 事件
    speechBase: '',              // F1.1 语音输入前已存在的文本
    wide: false                  // 当前是否宽屏（≥900px 两列布局）
  };

  const $ = function (id) { return document.getElementById(id); };

  // ==========================================================================
  // 2. 通用工具
  // ==========================================================================

  /** 统一的 DOM 构造器：所有文本都通过 textContent 写入，从根上避免 XSS */
  function h(tag, attrs) {
    const node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        const v = attrs[k];
        if (v === null || v === undefined || v === false) return;
        if (k === 'class') node.className = v;
        else if (k === 'text') node.textContent = String(v);
        else if (k === 'value') node.value = v;
        else if (k === 'checked' || k === 'disabled' || k === 'selected') node[k] = !!v;
        else if (k === 'dataset') Object.keys(v).forEach(function (d) { node.dataset[d] = v[d]; });
        else if (k.indexOf('on') === 0 && typeof v === 'function') node.addEventListener(k.slice(2).toLowerCase(), v);
        else node.setAttribute(k, v);
      });
    }
    for (let i = 2; i < arguments.length; i++) {
      const child = arguments[i];
      if (child === null || child === undefined || child === false) continue;
      if (Array.isArray(child)) {
        child.forEach(function (c) { if (c) node.appendChild(c); });
      } else if (typeof child === 'string' || typeof child === 'number') {
        node.appendChild(document.createTextNode(String(child)));
      } else {
        node.appendChild(child);
      }
    }
    return node;
  }

  function clear(node) { while (node && node.firstChild) node.removeChild(node.firstChild); }

  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  /** 解析后端可能给出的 "yyyy-MM-dd" 或完整 ISO 时间 */
  function parseDateLike(raw) {
    if (!raw) return null;
    const s = String(raw);
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(s);
    if (!m) return null;
    const t = /T(\d{2}):(\d{2})/.exec(s);
    return {
      y: Number(m[1]), mo: Number(m[2]), d: Number(m[3]),
      hh: t ? Number(t[1]) : null, mm: t ? Number(t[2]) : null,
      raw: s
    };
  }

  /** 只取日期部分（yyyy-MM-dd）：后端 today/week/overdue 是「按日期字符串」比较的，带时间会漏筛 */
  function dateOnly(raw) {
    const p = parseDateLike(raw);
    if (!p) return null;
    return p.y + '-' + pad2(p.mo) + '-' + pad2(p.d);
  }

  /** 截止时间的友好展示 + 是否逾期（F3.1） */
  function deadlineInfo(raw, isCompleted) {
    const p = parseDateLike(raw);
    if (!p) return null;
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const d = new Date(p.y, p.mo - 1, p.d, 0, 0, 0, 0);
    const diff = Math.round((d.getTime() - today.getTime()) / 86400000);
    let label;
    if (diff === 0) label = '今天';
    else if (diff === 1) label = '明天';
    else if (diff === -1) label = '昨天';
    else if (diff > 1 && diff <= 7) label = WEEKDAYS[d.getDay()] + ' ' + (p.mo) + '/' + (p.d);
    else label = p.y + '-' + pad2(p.mo) + '-' + pad2(p.d);
    if (p.hh !== null) label += ' ' + pad2(p.hh) + ':' + pad2(p.mm);
    return { text: label, overdue: diff < 0 && !isCompleted };
  }

  /** 相对时间：刚刚 / N 分钟前 / N 小时前 / yyyy-MM-dd HH:mm */
  function relativeTime(raw) {
    const t = Date.parse(raw);
    if (isNaN(t)) return raw ? String(raw) : '';
    const diff = Date.now() - t;
    if (diff < 60000) return '刚刚';
    if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
    if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前';
    if (diff < 7 * 86400000) return Math.floor(diff / 86400000) + ' 天前';
    const d = new Date(t);
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) +
      ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }

  function truncate(s, n) {
    s = s === null || s === undefined ? '' : String(s);
    return s.length > n ? s.slice(0, n) + '…' : s;
  }

  /** datetime-local 输入值 → ISO 字符串（后端 remindAt 用 ISO-8601） */
  function localInputToIso(v) {
    if (!v) return null;
    const d = new Date(v);
    if (isNaN(d.getTime())) return null;
    return d.toISOString();
  }

  /** ISO 字符串 → datetime-local 输入值 */
  function isoToLocalInput(raw) {
    if (!raw) return '';
    const d = new Date(raw);
    if (isNaN(d.getTime())) {
      const p = parseDateLike(raw);
      if (!p) return '';
      return p.y + '-' + pad2(p.mo) + '-' + pad2(p.d) + 'T' + pad2(p.hh === null ? 0 : p.hh) + ':' + pad2(p.mm === null ? 0 : p.mm);
    }
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) +
      'T' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }

  /** 按钮 loading 态包装：所有异步按钮共用，避免重复点击 */
  function withBusy(btn, fn) {
    if (!btn) return fn();
    if (btn.disabled) return Promise.resolve();
    btn.disabled = true;
    btn.classList.add('is-loading');
    return Promise.resolve()
      .then(fn)
      .catch(function () { /* 错误已在 showToast 里提示 */ })
      .then(function () {
        btn.disabled = false;
        btn.classList.remove('is-loading');
        // 批量按钮的可用性由「已选条数」决定，异步结束后要按当前选择重算，避免残留禁用态
        if (btn.id === 'btnBatchDone' || btn.id === 'btnBatchUndo') updateSelectionUi();
      });
  }

  // ==========================================================================
  // 3. 提示条（错误必须可见 + 可重试，不做静默失败）
  // ==========================================================================
  let toastRetryFn = null;

  function showToast(message, opts) {
    opts = opts || {};
    const box = $('toast');
    const txt = $('toastText');
    const retryBtn = $('toastRetry');
    box.classList.remove('hidden', 'ok', 'warn');
    if (opts.kind === 'ok') box.classList.add('ok');
    if (opts.kind === 'warn') box.classList.add('warn');
    txt.textContent = (opts.kind === 'ok' ? '✅ ' : (opts.kind === 'warn' ? '⚠️ ' : '❌ ')) + message;
    toastRetryFn = opts.retry || null;
    retryBtn.classList.toggle('hidden', !toastRetryFn);
    if (opts.kind === 'ok') {
      window.clearTimeout(showToast._timer);
      showToast._timer = window.setTimeout(hideToast, 2600);
    }
  }

  function hideToast() {
    $('toast').classList.add('hidden');
    toastRetryFn = null;
  }

  // ==========================================================================
  // 4. API 层（统一带 X-Device-Id，统一错误处理）
  // ==========================================================================
  function ApiError(message, extra) {
    this.name = 'ApiError';
    this.message = message;
    this.status = (extra && extra.status) || 0;
    this.network = !!(extra && extra.network);
  }
  ApiError.prototype = Object.create(Error.prototype);

  function apiUrl(path) {
    const base = (state.baseUrl || '').replace(/\/+$/, '');
    return base + path;
  }

  async function api(path, opts) {
    opts = opts || {};
    const headers = {};
    headers['X-Device-Id'] = state.deviceId || 'web-anonymous';
    const init = { method: opts.method || 'GET', headers: headers, cache: 'no-store' };
    if (opts.body !== undefined) {
      headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(opts.body);
    }
    let res;
    try {
      res = await fetch(apiUrl(path), init);
    } catch (e) {
      throw new ApiError('连不上后端（' + apiUrl(path) + '）：' + (e && e.message ? e.message : '网络错误'), { network: true });
    }
    let text = '';
    try { text = await res.text(); } catch (e) { text = ''; }
    let data = null;
    if (text) {
      try { data = JSON.parse(text); } catch (e) { data = null; }
    }
    if (!res.ok) {
      let msg = data && (data.error || data.message);
      if (!msg) msg = text ? truncate(text, 160) : ('HTTP ' + res.status);
      throw new ApiError('接口 ' + path + ' 返回 ' + res.status + '：' + msg, { status: res.status });
    }
    if (res.status === 204 || text === '') return null;
    return data;
  }

  /** /api/extract 的 drafts 可能是「数组」也可能是「字符串包着数组」，两种都兼容（契约明确要求） */
  function normalizeDrafts(raw) {
    if (raw === null || raw === undefined) return [];
    let v = raw;
    if (typeof v === 'string') {
      try { v = JSON.parse(v); } catch (e) { throw new ApiError('提炼结果解析失败：drafts 既不是数组也不是合法 JSON'); }
    }
    if (typeof v === 'string') {
      try { v = JSON.parse(v); } catch (e) { /* 保持原样，下面统一报错 */ }
    }
    return Array.isArray(v) ? v : [];
  }

  // ==========================================================================
  // 5. 设备绑定（F4.1，纯自用免账号 D4）+ 后端地址（设置项）
  // ==========================================================================
  function genDeviceId() {
    let rand = '';
    const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
    for (let i = 0; i < 10; i++) rand += chars.charAt(Math.floor(Math.random() * chars.length));
    return 'web-' + rand;
  }

  function loadLocalSettings() {
    state.deviceId = localStorage.getItem(STORE.deviceId) || '';
    if (!state.deviceId) {
      state.deviceId = genDeviceId();
      localStorage.setItem(STORE.deviceId, state.deviceId);
    }
    state.baseUrl = localStorage.getItem(STORE.baseUrl) || '';
    $('deviceIdInput').value = state.deviceId;
    $('baseUrlInput').value = state.baseUrl;
  }

  /** POST /api/devices 绑定（有设备 ID 就绑定；失败只提示，不阻塞使用） */
  async function bindDevice(showResult) {
    try {
      await api('/api/devices', {
        method: 'POST',
        body: { deviceId: state.deviceId, name: 'Web 浏览器', kind: 'web' }
      });
      localStorage.setItem(STORE.boundDevice, state.deviceId);
      if (showResult) showToast('设备已绑定：' + state.deviceId, { kind: 'ok' });
      loadDevices();
      return true;
    } catch (e) {
      if (showResult) showToast(e.message, { retry: function () { bindDevice(true); } });
      // 非手动绑定时不覆盖顶栏连接状态（避免与 /api/health 的结果互相打架），只在设置区提示
      if (!showResult) $('bindOut').textContent = '设备绑定失败：' + e.message;
      return false;
    }
  }

  async function loadDevices() {
    try {
      const list = await api('/api/devices');
      const arr = Array.isArray(list) ? list : [];
      const mine = arr.filter(function (d) { return d.deviceId === state.deviceId; })[0];
      const names = arr.map(function (d) { return (d.name || d.kind || '设备') + '(' + d.deviceId + ')'; }).join('、');
      $('bindOut').textContent = (mine ? '已绑定（' + (mine.boundAt ? relativeTime(mine.boundAt) : '') + '）' : '当前设备未在后端登记') +
        (names ? '｜已登记：' + truncate(names, 120) : '');
    } catch (e) {
      $('bindOut').textContent = '设备列表加载失败：' + e.message;
    }
  }

  function saveBaseUrl() {
    const v = $('baseUrlInput').value.trim().replace(/\/+$/, '');
    state.baseUrl = v;
    localStorage.setItem(STORE.baseUrl, v);
    showToast(v ? ('后端地址已保存：' + v) : '后端地址已恢复为同源', { kind: 'ok' });
    setNetStatus(null, '连接中…');
    return refreshAll();
  }

  // ==========================================================================
  // 6. 连接状态（顶栏胶囊）
  // ==========================================================================
  function setNetStatus(ok, text) {
    const el = $('netStatus');
    el.classList.remove('pill-ok', 'pill-err');
    if (ok === true) el.classList.add('pill-ok');
    if (ok === false) el.classList.add('pill-err');
    el.textContent = text;
  }

  async function checkHealth(showToastOnFail) {
    try {
      const r = await api('/api/health');
      setNetStatus(r && r.status === 'ok', '已连接 · v' + ((r && r.version) || '?') + (r && r.dbReady === false ? ' · 库异常' : ''));
      $('healthOut').textContent = '状态：' + ((r && r.status) || '?') +
        '｜版本：' + ((r && r.version) || '?') +
        '｜数据库：' + (r && r.dbReady ? '正常' : '不可用') +
        '｜服务器时间：' + ((r && r.time) || '?');
      if (showToastOnFail) showToast('连接正常', { kind: 'ok' });
      return r;
    } catch (e) {
      setNetStatus(false, '离线');
      $('healthOut').textContent = '连接失败：' + e.message;
      if (showToastOnFail) showToast(e.message, { retry: function () { checkHealth(true); } });
      return null;
    }
  }

  // ==========================================================================
  // 7. F1.2 / F1.3 输入区：文字 + 图片压缩 + 语音
  // ==========================================================================
  function updateTextCount() {
    $('textCount').textContent = $('textInput').value.length + ' 字';
  }

  /** F1.3 前端压缩：canvas 缩到最长边 1280px，导出 JPEG 0.8，再去掉 data: 前缀 */
  function compressImage(file) {
    return new Promise(function (resolve, reject) {
      const url = URL.createObjectURL(file);
      const img = new Image();
      img.onload = function () {
        try {
          const natW = img.naturalWidth || img.width;
          const natH = img.naturalHeight || img.height;
          if (!natW || !natH) throw new Error('图片尺寸异常');
          const scale = Math.min(1, IMAGE_MAX_EDGE / Math.max(natW, natH));
          const w = Math.max(1, Math.round(natW * scale));
          const hh = Math.max(1, Math.round(natH * scale));
          const canvas = document.createElement('canvas');
          canvas.width = w;
          canvas.height = hh;
          const ctx = canvas.getContext('2d');
          // JPEG 无透明通道：先铺白底，避免 PNG 透明区域变黑
          ctx.fillStyle = '#ffffff';
          ctx.fillRect(0, 0, w, hh);
          ctx.drawImage(img, 0, 0, w, hh);
          const dataUrl = canvas.toDataURL('image/jpeg', IMAGE_QUALITY);
          const base64 = dataUrl.indexOf(',') >= 0 ? dataUrl.split(',')[1] : '';
          if (!base64) throw new Error('图片编码失败');
          resolve({
            id: 'img-' + Date.now() + '-' + Math.floor(Math.random() * 1000),
            name: file.name || 'photo.jpg',
            dataUrl: dataUrl,          // 仅本地预览用
            base64: base64,            // 上传用（不带 data: 前缀）
            mimeType: 'image/jpeg',
            width: w,
            height: hh,
            origin: natW + '×' + natH
          });
        } catch (e) {
          reject(e);
        } finally {
          URL.revokeObjectURL(url);
        }
      };
      img.onerror = function () {
        URL.revokeObjectURL(url);
        reject(new Error('图片解码失败：' + (file.name || '未知文件')));
      };
      img.src = url;
    });
  }

  async function handleFiles(fileList) {
    const files = Array.prototype.slice.call(fileList || []).filter(function (f) {
      return f && /^image\//i.test(f.type || '');
    });
    if (!files.length) {
      showToast('请选择图片文件（png / jpg / webp 等）', { kind: 'warn' });
      return;
    }
    const failed = [];
    for (let i = 0; i < files.length; i++) {
      try {
        const item = await compressImage(files[i]);
        state.images.push(item);
      } catch (e) {
        failed.push(files[i].name + '：' + e.message);
      }
    }
    renderImages();
    if (failed.length) {
      showToast('部分图片处理失败：' + failed.join('；'), {
        retry: function () { handleFiles(fileList); }
      });
    }
  }

  function renderImages() {
    const box = $('imagePreview');
    clear(box);
    state.images.forEach(function (img) {
      const thumb = h('li', { class: 'thumb' },
        h('img', { src: img.dataUrl, alt: img.name }),
        h('span', { class: 'thumb-size', text: img.width + '×' + img.height })
      );
      const del = h('button', {
        type: 'button', text: '×', title: '移除',
        onclick: function () {
          state.images = state.images.filter(function (x) { return x.id !== img.id; });
          renderImages();
        }
      });
      thumb.appendChild(del);
      box.appendChild(thumb);
    });
  }

  /** F1.1 语音输入：D6 决策优先复用输入法语音；浏览器支持 Web Speech API 时额外提供（不支持则隐藏按钮） */
  function setupSpeech() {
    const Rec = window.SpeechRecognition || window.webkitSpeechRecognition;
    const btn = $('btnMic');
    if (!Rec) {
      btn.classList.add('hidden');
      return;
    }
    btn.classList.remove('hidden');
    let rec = null;
    let listening = false;
    btn.addEventListener('click', function () {
      if (listening && rec) { rec.stop(); return; }
      rec = new Rec();
      rec.lang = 'zh-CN';
      rec.continuous = false;
      rec.interimResults = true;
      let finalText = '';
      rec.onstart = function () { listening = true; btn.textContent = '■ 停止'; btn.classList.add('btn-primary'); };
      rec.onresult = function (ev) {
        let interim = '';
        for (let i = ev.resultIndex; i < ev.results.length; i++) {
          const r = ev.results[i];
          if (r.isFinal) finalText += r[0].transcript;
          else interim += r[0].transcript;
        }
        const ta = $('textInput');
        ta.value = (state.speechBase || '') + finalText + interim;
        updateTextCount();
      };
      rec.onerror = function (ev) {
        showToast('语音识别失败（' + (ev && ev.error ? ev.error : '未知') + '），可直接用输入法语音或手动输入', { kind: 'warn' });
      };
      rec.onend = function () {
        listening = false;
        btn.textContent = '🎤 语音输入';
        btn.classList.remove('btn-primary');
        state.speechBase = $('textInput').value;
      };
      state.speechBase = $('textInput').value ? $('textInput').value + ' ' : '';
      try { rec.start(); } catch (e) { showToast('无法启动语音识别：' + e.message, { kind: 'warn' }); }
    });
  }

  function clearInput() {
    $('textInput').value = '';
    state.images = [];
    state.speechBase = '';
    renderImages();
    updateTextCount();
  }

  // ==========================================================================
  // 8. F2 AI 提炼（先预览再入库 F2.5；模糊度标注 F2.6）
  // ==========================================================================
  /**
   * 走 /api/extract 提炼。
   * @param btn      触发按钮（用于 loading 态）
   * @param opts     { inputId } —— 传 inputId 表示这是 F1.4「暂存重提」，不重复落暂存
   */
  async function runExtract(btn, opts) {
    opts = opts || {};
    const text = $('textInput').value.trim();
    const images = state.images.slice();
    const inputId = opts.inputId || null;

    if (!text && !images.length) {
      showToast('先输入文字或选择图片，再点「AI 提炼」', { kind: 'warn' });
      return;
    }
    state.lastRequest = { text: text, images: images, inputId: inputId || null };
    state.drafts = [];
    state.savedByInput = {};
    state.extractErrors = [];

    const btnEl = btn || $('btnExtract');
    await withBusy(btnEl, async function () {
      const collected = [];
      const metas = [];
      const inputIds = [];

      // F1.2 文字提炼（一段话里多条任务由后端拆成多条 draft，F2.2）
      if (text) {
        const body = { type: 'TEXT', text: text };
        if (inputId) { body.inputId = inputId; body.saveInput = false; } // 暂存重提：不重复落暂存
        try {
          const r = await api('/api/extract', { method: 'POST', body: body });
          const drafts = normalizeDrafts(r && r.drafts);
          drafts.forEach(function (d) { collected.push(Object.assign({}, d, { _sourceType: 'TEXT', _inputId: (r && r.inputId) || inputId || null })); });
          if (r && r.inputId) inputIds.push(r.inputId);
          metas.push(metaOf(r));
        } catch (e) {
          state.extractErrors.push('文字提炼失败：' + e.message);
        }
      }

      // F1.3 图片提炼：后端一次只收一张图，多张图逐张调用后合并草稿
      for (let i = 0; i < images.length; i++) {
        const img = images[i];
        try {
          const r = await api('/api/extract', {
            method: 'POST',
            body: { type: 'IMAGE', imageBase64: img.base64, mimeType: img.mimeType || 'image/jpeg' }
          });
          const drafts = normalizeDrafts(r && r.drafts);
          drafts.forEach(function (d) { collected.push(Object.assign({}, d, { _sourceType: 'IMAGE', _inputId: (r && r.inputId) || null })); });
          if (r && r.inputId) inputIds.push(r.inputId);
          metas.push(metaOf(r, i + 1));
        } catch (e) {
          state.extractErrors.push('第 ' + (i + 1) + ' 张图片提炼失败：' + e.message);
        }
      }

      state.drafts = collected;
      state.lastExtractMeta = metas;
      state.lastInputIds = inputIds;
      renderDrafts();

      if (state.extractErrors.length) {
        showToast(state.extractErrors.join('；') + (collected.length ? '（已保留成功的 ' + collected.length + ' 条草稿）' : ''), {
          retry: function () { runExtract($('btnExtract')); }
        });
      } else if (!collected.length) {
        showToast('AI 没提炼出待办，换个说法或重新拍一张更清楚的图试试', { kind: 'warn' });
      } else {
        showToast('提炼出 ' + collected.length + ' 条草稿，请确认后入库', { kind: 'ok' });
      }
    });
  }

  function metaOf(r, index) {
    return {
      index: index || null,
      modelUsed: (r && r.modelUsed) || '?',
      modelKind: (r && r.modelKind) || '',
      fallbackFrom: (r && r.fallbackFrom) || '',
      needsConfirmCount: (r && r.needsConfirmCount) || 0
    };
  }

  /** F2.5 预览可改 + F2.6 模糊度高亮 */
  function renderDrafts() {
    const card = $('draftCard');
    const list = $('draftList');
    clear(list);

    if (!state.drafts.length) {
      card.classList.add('hidden');
      return;
    }
    card.classList.remove('hidden');

    const metas = state.lastExtractMeta || [];
    const models = metas.map(function (m) { return m.modelUsed + (m.fallbackFrom ? '（回退自 ' + m.fallbackFrom + '）' : ''); });
    const uniqModels = models.filter(function (m, i) { return models.indexOf(m) === i; });
    const confirmCount = state.drafts.filter(isNeedConfirm).length;
    $('draftMeta').textContent = '共 ' + state.drafts.length + ' 条草稿' +
      (confirmCount ? '，其中 ' + confirmCount + ' 条需要确认' : '') +
      '｜模型：' + (uniqModels.join(' / ') || '未知') +
      '｜预览修改后再入库（F2.5）';

    state.drafts.forEach(function (d, i) {
      list.appendChild(renderDraftItem(d, i));
    });
  }

  function isNeedConfirm(d) {
    const conf = typeof d.confidence === 'number' ? d.confidence : 1;
    return d.needsConfirm === true || conf < CONFIRM_THRESHOLD;
  }

  function renderDraftItem(draft, index) {
    // 归一化草稿字段：AI 可能返回 "null" 字符串或缺失字段
    if (draft.deadline === 'null') draft.deadline = null;
    if (draft.subject === 'null') draft.subject = null;
    if (draft.notes === 'null') draft.notes = null;
    if (!draft.title) draft.title = '';

    const need = isNeedConfirm(draft);
    const conf = typeof draft.confidence === 'number' ? draft.confidence : 1;

    const wrap = h('div', { class: 'draft ' + (need ? 'needs-confirm' : 'ok') });
    wrap.appendChild(h('div', { class: 'draft-head' },
      h('span', { class: 'draft-index', text: '草稿 ' + (index + 1) + (draft._sourceType === 'IMAGE' ? '（来自图片）' : '（来自文字）') }),
      h('span', { class: 'draft-flag', text: need ? '⚠ 请确认（置信度 ' + Math.round(conf * 100) + '%）' : '✓ 置信度 ' + Math.round(conf * 100) + '%' })
    ));

    const titleInput = h('input', {
      type: 'text', value: draft.title, placeholder: '任务标题',
      oninput: function () { draft.title = titleInput.value; }
    });
    const deadlineInput = h('input', {
      type: 'date', value: dateOnly(draft.deadline) || '',
      onchange: function () { draft.deadline = deadlineInput.value || null; }
    });
    const subjectInput = h('input', {
      type: 'text', value: draft.subject || '', placeholder: '学科 / 分类',
      oninput: function () { draft.subject = subjectInput.value; }
    });
    const notesInput = h('textarea', {
      rows: 2, value: draft.notes || '', placeholder: '备注（可空）',
      oninput: function () { draft.notes = notesInput.value; }
    });

    const grid = h('div', { class: 'draft-grid two' },
      h('div', null, h('label', { class: 'field-label', text: '标题' }), titleInput),
      h('div', null, h('label', { class: 'field-label', text: '截止日期' }), deadlineInput),
      h('div', null, h('label', { class: 'field-label', text: '学科' }), subjectInput),
      h('div', null, h('label', { class: 'field-label', text: '备注' }), notesInput)
    );
    wrap.appendChild(grid);

    const keyLabel = h('label', { class: 'check-inline' },
      h('input', {
        type: 'checkbox', checked: !!draft.isKeyTask,
        onchange: function (e) { draft.isKeyTask = e.target.checked; }
      }),
      h('span', { text: '关键任务（可设定点提醒）' })
    );

    const saveBtn = h('button', { class: 'btn btn-sm btn-primary', type: 'button', text: '入库' });
    saveBtn.addEventListener('click', function () {
      withBusy(saveBtn, function () { return saveDraft(draft, wrap, saveBtn); });
    });

    wrap.appendChild(h('div', { class: 'draft-actions' }, keyLabel, saveBtn));

    if (draft.rawExtraction) {
      wrap.appendChild(h('p', { class: 'draft-raw', text: '原始提炼：' + truncate(draft.rawExtraction, 220) }));
    }
    return wrap;
  }

  /** F2.5 → POST /api/todos 入库单条草稿 */
  async function saveDraft(draft, wrap, btn) {
    if (draft._saved) { showToast('这条已经入库过了', { kind: 'warn' }); return; }
    const body = {
      title: (draft.title || '').trim() || '未命名待办',
      deadline: dateOnly(draft.deadline),                 // 统一成 yyyy-MM-dd，保证今日/本周分组正确
      subject: (draft.subject || '').trim() || null,
      notes: (draft.notes || '').trim() || null,
      isKeyTask: !!draft.isKeyTask,
      remindAt: null,
      remindOffsetMinutes: 0,
      sourceInputType: draft._sourceType === 'IMAGE' ? 'IMAGE' : 'TEXT',
      sourceInputRaw: draft.rawExtraction || (state.lastRequest && state.lastRequest.text) || null
    };
    try {
      const item = await api('/api/todos', { method: 'POST', body: body });
      draft._saved = true;
      draft._todoId = item && item.id;
      if (draft._inputId && item && item.id) {
        if (!state.savedByInput[draft._inputId]) state.savedByInput[draft._inputId] = [];
        state.savedByInput[draft._inputId].push(item.id);
      }
      if (wrap && btn) {
        btn.textContent = '已入库';
        btn.disabled = true;
        btn.classList.add('is-done');   // withBusy 结束后仍保持「已入库」不可再点
        wrap.classList.remove('needs-confirm');
        wrap.classList.add('ok');
      }
      showToast('已入库：' + body.title, { kind: 'ok' });
      await flushInputProcessed();
      await Promise.all([loadTodos(), loadStats(), loadInputs()]);
    } catch (e) {
      showToast(e.message, { retry: function () { saveDraft(draft, wrap, btn); } });
    }
  }

  /** F2.5 全部入库：逐条提交（后端没有批量创建接口，逐条 POST /api/todos） */
  async function saveAllDrafts() {
    const pending = state.drafts.filter(function (d) { return !d._saved; });
    if (!pending.length) {
      showToast('没有待入库的草稿了', { kind: 'warn' });
      return;
    }
    let okCount = 0;
    const failed = [];
    for (let i = 0; i < pending.length; i++) {
      const d = pending[i];
      try {
        const item = await api('/api/todos', {
          method: 'POST',
          body: {
            title: (d.title || '').trim() || '未命名待办',
            deadline: dateOnly(d.deadline),
            subject: (d.subject || '').trim() || null,
            notes: (d.notes || '').trim() || null,
            isKeyTask: !!d.isKeyTask,
            remindAt: null,
            remindOffsetMinutes: 0,
            sourceInputType: d._sourceType === 'IMAGE' ? 'IMAGE' : 'TEXT',
            sourceInputRaw: d.rawExtraction || (state.lastRequest && state.lastRequest.text) || null
          }
        });
        d._saved = true;
        d._todoId = item && item.id;
        if (d._inputId && item && item.id) {
          if (!state.savedByInput[d._inputId]) state.savedByInput[d._inputId] = [];
          state.savedByInput[d._inputId].push(item.id);
        }
        okCount++;
      } catch (e) {
        failed.push((d.title || '未命名') + '：' + e.message);
      }
    }
    renderDrafts();
    await flushInputProcessed();
    await Promise.all([loadTodos(), loadStats(), loadInputs()]);
    if (failed.length) {
      showToast('入库成功 ' + okCount + ' 条，失败 ' + failed.length + ' 条：' + failed.join('；'), {
        retry: function () { saveAllDrafts(); }
      });
    } else {
      showToast('全部入库完成，共 ' + okCount + ' 条', { kind: 'ok' });
    }
  }

  /** F1.4 回填：把本批次产出的待办 id 写回暂存输入（标记已处理） */
  async function flushInputProcessed() {
    const ids = Object.keys(state.savedByInput);
    for (let i = 0; i < ids.length; i++) {
      const inputId = ids[i];
      const todoIds = state.savedByInput[inputId];
      if (!inputId || !todoIds || !todoIds.length) continue;
      try {
        await api('/api/inputs/' + encodeURIComponent(inputId), {
          method: 'PUT',
          body: { todoIds: todoIds.join(','), processed: true }
        });
      } catch (e) {
        // 回填失败不影响入库结果，但要可见
        showToast('入库成功，但「输入历史」回填失败：' + e.message, {
          retry: function () { flushInputProcessed(); }
        });
      }
    }
    state.savedByInput = {};
  }

  // ==========================================================================
  // 9. F1.4 输入暂存（未处理可重提）
  // ==========================================================================
  async function loadInputs() {
    const box = $('inputHistory');
    try {
      const list = await api('/api/inputs');
      const arr = Array.isArray(list) ? list : [];
      clear(box);
      if (!arr.length) {
        box.appendChild(h('p', { class: 'empty', text: '还没有暂存输入。' }));
        return;
      }
      arr.forEach(function (rec) {
        const isText = (rec.type || 'TEXT') === 'TEXT';
        const text = isText ? (rec.text || '(空文本)') : '图片输入（' + (rec.imageRef || '未存云端') + '，不可重提）';
        const meta = relativeTime(rec.createdAt) + ' · ' + (rec.type || 'TEXT') +
          (rec.processed
            ? ' · 已处理，产出 ' + ((rec.extractedTodoIds && rec.extractedTodoIds.length) || 0) + ' 条待办'
            : ' · 未处理') +
          (rec.expired ? ' · 已过期' : '');

        const actions = h('div', { class: 'todo-actions' });
        if (!rec.processed && isText) {
          // 重提：把原文填回输入框并立刻重新提炼（带 inputId，不重复落暂存）
          const btn = h('button', { class: 'btn btn-sm btn-ghost', type: 'button', text: '重提' });
          btn.addEventListener('click', function () {
            $('textInput').value = rec.text || '';
            updateTextCount();
            setView('input', true);
            withBusy(btn, function () { return runExtract($('btnExtract'), { inputId: rec.id }); });
          });
          actions.appendChild(btn);
        } else if (!rec.processed && !isText) {
          actions.appendChild(h('span', { class: 'chip', text: '图片不支持重提' }));
        }

        box.appendChild(h('div', { class: 'history-item' },
          h('div', { class: 'history-text' },
            h('div', { text: truncate(text, 140) }),
            h('div', { class: 'history-meta', text: meta })
          ),
          actions
        ));
      });
    } catch (e) {
      clear(box);
      box.appendChild(h('p', { class: 'empty', text: '输入历史加载失败：' + e.message }));
      showToast(e.message, { retry: function () { loadInputs(); } });
    }
  }

  // ==========================================================================
  // 10. F3.1 待办列表 / F3.2 完成与批量 / F3.3 编辑删除
  // ==========================================================================
  async function loadTodos() {
    const url = TODO_URLS[state.scope] || TODO_URLS.all;
    try {
      const list = await api(url);
      state.todos = Array.isArray(list) ? list : [];
      localStorage.setItem(STORE.cacheTodos, JSON.stringify({ scope: state.scope, at: new Date().toISOString(), items: state.todos }));
      renderTodos();
    } catch (e) {
      // F4.4 离线兜底：展示上次缓存，同时明确提示（不静默失败）
      let cached = null;
      try { cached = JSON.parse(localStorage.getItem(STORE.cacheTodos) || 'null'); } catch (err) { cached = null; }
      if (cached && Array.isArray(cached.items)) {
        state.todos = cached.items;
        renderTodos();
        showToast('列表加载失败，已显示本地缓存（' + relativeTime(cached.at) + '）：' + e.message, {
          retry: function () { loadTodos(); }
        });
      } else {
        state.todos = [];
        renderTodos();
        showToast('列表加载失败：' + e.message, { retry: function () { loadTodos(); } });
      }
    }
  }

  function visibleTodos() {
    return state.todos;
  }

  function renderTodos() {
    const listEl = $('todoList');
    clear(listEl);
    const items = visibleTodos();
    $('todoEmpty').classList.toggle('hidden', items.length > 0);

    items.forEach(function (t) {
      const li = h('li', { class: 'todo' + (t.isCompleted ? ' done' : '') });

      // 勾选框：多选模式下 = 选中；否则 = 完成开关（F3.2 单条完成）
      const cb = h('input', { type: 'checkbox' });
      cb.checked = state.selectMode ? state.selected.has(t.id) : !!t.isCompleted;
      cb.title = state.selectMode ? '选中用于批量操作' : '标记完成';
      cb.addEventListener('change', function () {
        if (state.selectMode) {
          if (cb.checked) state.selected.add(t.id); else state.selected.delete(t.id);
          updateSelectionUi();
        } else {
          toggleComplete(t, cb.checked, cb);
        }
      });
      li.appendChild(cb);

      const body = h('div', { class: 'todo-body' }, h('div', { class: 'todo-title', text: t.title }));

      const due = deadlineInfo(t.deadline, t.isCompleted);
      const meta = h('div', { class: 'todo-meta' });
      if (due) meta.appendChild(h('span', { class: 'chip ' + (due.overdue ? 'chip-overdue' : 'chip-due'), text: (due.overdue ? '逾期 · ' : '截止 · ') + due.text }));
      if (t.subject) meta.appendChild(h('span', { class: 'chip', text: t.subject }));
      meta.appendChild(h('span', { class: 'chip', text: '来源·' + (t.sourceInputType === 'IMAGE' ? '图片' : '文字') }));
      if (t.isKeyTask) meta.appendChild(h('span', { class: 'chip chip-key', text: t.remindAt ? '关键 · 定点提醒 ' + relativeTime(t.remindAt) : '关键任务' }));
      if (t.isCompleted) meta.appendChild(h('span', { class: 'chip chip-done', text: '已完成' + (t.completedAt ? ' · ' + relativeTime(t.completedAt) : '') }));
      body.appendChild(meta);

      if (t.notes) body.appendChild(h('p', { class: 'todo-notes', text: t.notes }));
      body.appendChild(h('p', { class: 'todo-notes', text: '创建于 ' + relativeTime(t.createdAt) }));
      li.appendChild(body);

      // F3.3 编辑（弹窗）+ 删除（软删除 DELETE）
      const actions = h('div', { class: 'todo-actions' });
      const editBtn = h('button', { class: 'btn btn-sm btn-ghost', type: 'button', text: '编辑' });
      editBtn.addEventListener('click', function () { openEdit(t); });
      const delBtn = h('button', { class: 'btn btn-sm btn-danger-ghost', type: 'button', text: '删除' });
      delBtn.addEventListener('click', function () {
        if (!window.confirm('确定删除「' + t.title + '」？（后端为软删除，可同步下发）')) return;
        withBusy(delBtn, function () { return deleteTodo(t); });
      });
      actions.appendChild(editBtn);
      actions.appendChild(delBtn);
      li.appendChild(actions);

      listEl.appendChild(li);
    });
    updateSelectionUi();
  }

  function updateSelectionUi() {
    const count = state.selected.size;
    $('selCount').textContent = '已选 ' + count + ' 条';
    $('checkAll').checked = state.todos.length > 0 && count === state.todos.length;
    $('btnSelectMode').textContent = state.selectMode ? '退出多选' : '多选';
    $('btnSelectMode').classList.toggle('btn-primary', state.selectMode);
    $('btnSelectMode').classList.toggle('btn-ghost', !state.selectMode);
    $('btnBatchDone').disabled = count === 0;
    $('btnBatchUndo').disabled = count === 0;
  }

  function toggleSelectMode(on) {
    state.selectMode = on === undefined ? !state.selectMode : !!on;
    state.selected.clear();
    renderTodos();
  }

  /** F3.2 单条完成 → PUT /api/todos/{id} body {"isCompleted":true} */
  async function toggleComplete(todo, checked, cb) {
    try {
      const item = await api('/api/todos/' + encodeURIComponent(todo.id), {
        method: 'PUT',
        body: { isCompleted: !!checked }
      });
      const idx = state.todos.findIndex(function (x) { return x.id === todo.id; });
      if (idx >= 0 && item) state.todos[idx] = item;
      renderTodos();
      loadStats();
    } catch (e) {
      if (cb) cb.checked = !checked;
      showToast(e.message, {
        retry: function () { toggleComplete(todo, checked, null); }
      });
    }
  }

  /** F3.2 批量完成 → POST /api/todos/batch body {"ids":[...],"isCompleted":true} */
  async function batchComplete(completed, btn) {
    const ids = Array.from(state.selected);
    if (!ids.length) {
      showToast('先在多选模式下勾选待办', { kind: 'warn' });
      return;
    }
    await withBusy(btn, async function () {
      try {
        const r = await api('/api/todos/batch', { method: 'POST', body: { ids: ids, isCompleted: !!completed } });
        showToast('已' + (completed ? '完成' : '取消完成') + ((r && r.affected) || ids.length) + ' 条', { kind: 'ok' });
        state.selected.clear();
        await Promise.all([loadTodos(), loadStats()]);
      } catch (e) {
        showToast(e.message, { retry: function () { batchComplete(completed, btn); } });
      }
    });
  }

  /** F3.3 删除 → DELETE /api/todos/{id}（204） */
  async function deleteTodo(todo) {
    try {
      await api('/api/todos/' + encodeURIComponent(todo.id), { method: 'DELETE' });
      state.todos = state.todos.filter(function (x) { return x.id !== todo.id; });
      state.selected.delete(todo.id);
      renderTodos();
      loadStats();
      showToast('已删除：' + todo.title, { kind: 'ok' });
    } catch (e) {
      showToast(e.message, { retry: function () { deleteTodo(todo); } });
    }
  }

  // ---------- F3.3 编辑弹窗 ----------
  function openEdit(todo) {
    state.editTarget = todo;
    $('editTitleInput').value = todo.title || '';
    $('editDeadline').value = dateOnly(todo.deadline) || '';
    $('editSubject').value = todo.subject || '';
    $('editNotes').value = todo.notes || '';
    $('editRemindAt').value = isoToLocalInput(todo.remindAt);
    $('editOffset').value = typeof todo.remindOffsetMinutes === 'number' ? todo.remindOffsetMinutes : 0;
    $('editKeyTask').checked = !!todo.isKeyTask;
    $('editModal').classList.remove('hidden');
  }

  function closeEdit() {
    $('editModal').classList.add('hidden');
    state.editTarget = null;
  }

  /** PUT /api/todos/{id}：清空截止用 clearDeadline，清空定点提醒用 clearRemindAt */
  async function saveEdit() {
    const t = state.editTarget;
    if (!t) return;
    const deadlineVal = $('editDeadline').value || '';
    const remindVal = $('editRemindAt').value || '';
    const body = {
      title: $('editTitleInput').value.trim() || t.title,
      subject: $('editSubject').value.trim(),
      notes: $('editNotes').value,
      isKeyTask: $('editKeyTask').checked,
      remindOffsetMinutes: Number($('editOffset').value || 0)
    };
    if (deadlineVal) body.deadline = deadlineVal;
    else body.clearDeadline = true;      // 清空截止时间
    if (remindVal) body.remindAt = localInputToIso(remindVal);
    else body.clearRemindAt = true;      // 清空定点提醒
    try {
      const item = await api('/api/todos/' + encodeURIComponent(t.id), { method: 'PUT', body: body });
      const idx = state.todos.findIndex(function (x) { return x.id === t.id; });
      if (idx >= 0 && item) state.todos[idx] = item;
      closeEdit();
      renderTodos();
      loadStats();
      showToast('已保存修改', { kind: 'ok' });
    } catch (e) {
      showToast('保存失败：' + e.message, {
        retry: function () { saveEdit(); }
      });
    }
  }

  // ==========================================================================
  // 11. F3.8 完成率看板
  // ==========================================================================
  async function loadStats() {
    try {
      const s = await api('/api/stats/completion?scope=all');
      if (!s) return;
      localStorage.setItem(STORE.cacheStats, JSON.stringify({ at: new Date().toISOString(), data: s }));
      renderStats(s);
    } catch (e) {
      let cached = null;
      try { cached = JSON.parse(localStorage.getItem(STORE.cacheStats) || 'null'); } catch (err) { cached = null; }
      if (cached && cached.data) {
        renderStats(cached.data);
        showToast('看板加载失败，显示 ' + relativeTime(cached.at) + ' 的缓存：' + e.message, { retry: function () { loadStats(); } });
      } else {
        showToast('看板加载失败：' + e.message, { retry: function () { loadStats(); } });
      }
    }
  }

  function renderStats(s) {
    $('statWeekDone').textContent = String(s.weekCompleted === undefined ? '–' : s.weekCompleted);
    $('statWeekOverdue').textContent = String(s.weekOverdue === undefined ? '–' : s.weekOverdue);
    $('statStreak').textContent = String(s.streakDays === undefined ? '–' : s.streakDays);
    $('statPending').textContent = String(s.totalPending === undefined ? '–' : s.totalPending);
    // 后端 completionRate 是 0~1 的小数；若后端改为百分数（>1）也能兼容展示
    let rate = typeof s.completionRate === 'number' ? s.completionRate : 0;
    if (rate <= 1) rate = rate * 100;
    rate = Math.max(0, Math.min(100, rate));
    $('statRate').textContent = rate.toFixed(rate % 1 === 0 ? 0 : 1) + '%';
    $('statRateBar').style.width = rate + '%';
  }

  // ==========================================================================
  // 12. F6 配置中心（只读部分）+ F5 提醒设置展示
  // ==========================================================================
  async function loadConfig() {
    const modelBox = $('modelInfo');
    try {
      const cfg = await api('/api/config');
      // F5.1 / F5.5 每日推送时间
      if (cfg) {
        const hour = typeof cfg.dailyPushHour === 'number' ? cfg.dailyPushHour : 8;
        const min = typeof cfg.dailyPushMinute === 'number' ? cfg.dailyPushMinute : 0;
        $('pushInfo').textContent = '每日推送：' + (cfg.dailyPushEnabled === false ? '已关闭' : '已启用') +
          '｜时间：' + pad2(hour) + ':' + pad2(min) +
          '｜关键任务定点提醒：' + (cfg.keyTaskRemindEnabled === false ? '关闭' : '开启') +
          '｜默认提前量：' + (cfg.defaultRemindOffsetMinutes || 0) + ' 分钟' +
          '（本页只读，修改在手机端配置中心 F6.1 主密码门禁后操作）';
      }

      // F6.2 API Key 只显示「已配置 / 未配置」，绝不显示明文（后端只回 *** 或 ""）
      clear(modelBox);
      const models = (cfg && cfg.models) || {};
      const keys = Object.keys(models);
      if (!keys.length) {
        modelBox.appendChild(h('p', { class: 'empty', text: '后端没有返回模型配置。' }));
      }
      keys.forEach(function (k) {
        const m = models[k] || {};
        const configured = !!m.apiKey;   // "***" 或 ""，两者都绝不明文展示
        modelBox.appendChild(h('div', { class: 'model-item' },
          h('div', null,
            h('div', { class: 'model-name', text: k + ' · ' + (m.modelName || '未设置模型名') }),
            h('div', { class: 'model-sub', text: 'baseUrl: ' + (m.baseUrl || '-') + '｜视觉: ' + (m.supportsVision ? '支持' : '不支持') + '｜温度 ' + (m.temperature === undefined ? '-' : m.temperature) + ' / maxTokens ' + (m.maxTokens === undefined ? '-' : m.maxTokens) })
          ),
          h('span', { class: 'badge ' + (configured ? 'badge-ok' : 'badge-off'), text: configured ? '已配置' : '未配置' })
        ));
      });
      if (cfg && cfg.routing) {
        modelBox.appendChild(h('p', { class: 'text-muted small', text: 'F6.3 分流：文本→' + (cfg.routing.textModel || '-') + '｜图片→' + (cfg.routing.imageModel || '-') + '｜图片分流开关：' + (cfg.routing.enableImageRouting ? '启用' : '关闭') + '｜图片最长边 ' + (cfg.routing.imageMaxEdge || IMAGE_MAX_EDGE) + 'px' }));
      }
    } catch (e) {
      clear(modelBox);
      modelBox.appendChild(h('p', { class: 'empty', text: '配置加载失败：' + e.message }));
      showToast(e.message, { retry: function () { loadConfig(); } });
    }
  }

  // ==========================================================================
  // 13. F4.3 增量同步 / F4.4 离线缓存
  // ==========================================================================
  async function syncNow(btn) {
    await withBusy(btn, async function () {
      try {
        const r = await api('/api/sync', {
          method: 'POST',
          body: {
            deviceId: state.deviceId,
            sinceUpdatedAt: null,        // null = 全量；增量可传上次同步时间戳
            includeCompleted: true,
            includeDeleted: true,
            limit: 500
          }
        });
        const at = new Date().toISOString();
        localStorage.setItem(STORE.lastSyncAt, at);
        if (r && Array.isArray(r.items)) {
          localStorage.setItem(STORE.cacheTodos, JSON.stringify({ scope: state.scope, at: at, items: r.items }));
        }
        renderLastSync();
        showToast('同步完成：' + ((r && r.items && r.items.length) || 0) + ' 条' +
          (r && r.hasMore ? '（还有更多，可再次同步）' : '') +
          (r && r.pendingInputs ? '｜待补提交输入 ' + r.pendingInputs + ' 条' : ''), { kind: 'ok' });
        await Promise.all([loadTodos(), loadStats()]);
      } catch (e) {
        showToast('同步失败：' + e.message, { retry: function () { syncNow(btn); } });
      }
    });
  }

  function renderLastSync() {
    const at = localStorage.getItem(STORE.lastSyncAt);
    $('lastSyncOut').textContent = at ? ('上次同步：' + relativeTime(at) + '（' + at + '）') : '上次同步：无记录';
  }

  // ==========================================================================
  // 14. PWA：Service Worker 注册 + 「安装到桌面」
  // ==========================================================================
  function setupPwa() {
    if ('serviceWorker' in navigator) {
      window.addEventListener('load', function () {
        navigator.serviceWorker.register('./sw.js').then(function () {
          // 注册成功：离线可打开（app shell 已缓存）
        }).catch(function (err) {
          showToast('离线缓存（sw.js）注册失败：' + err.message, { kind: 'warn' });
        });
      });
    }

    const standalone = window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true;
    if (standalone) return;

    window.addEventListener('beforeinstallprompt', function (e) {
      e.preventDefault();
      state.installEvent = e;
      showInstallButtons();
    });
    window.addEventListener('appinstalled', function () {
      state.installEvent = null;
      hideInstallButtons();
      showToast('已添加到桌面，可离线打开', { kind: 'ok' });
    });
  }

  function showInstallButtons() {
    $('btnInstall').classList.remove('hidden');
    $('btnInstall2').classList.remove('hidden');
  }

  function hideInstallButtons() {
    $('btnInstall').classList.add('hidden');
    $('btnInstall2').classList.add('hidden');
  }

  async function promptInstall(btn) {
    if (!state.installEvent) {
      showToast('当前浏览器未提供安装提示：iOS Safari 请点「分享 → 添加到主屏幕」；Chrome 请点右上角菜单 →「安装应用」', { kind: 'warn' });
      return;
    }
    await withBusy(btn, async function () {
      state.installEvent.prompt();
      const choice = await state.installEvent.userChoice;
      if (choice && choice.outcome === 'accepted') showToast('正在安装…', { kind: 'ok' });
      state.installEvent = null;
      hideInstallButtons();
    });
  }

  // ==========================================================================
  // 15. 视图切换（窄屏单列 / 宽屏两列）
  // ==========================================================================
  function setView(name, quiet) {
    state.view = name;
    document.querySelectorAll('.view').forEach(function (v) { v.classList.remove('active'); });
    const target = $('view-' + name);
    if (target) target.classList.add('active');

    // 宽屏下左列常驻输入区；右列需要有内容，所以在「输入」视图时右侧默认展示待办
    const wide = window.matchMedia('(min-width: 900px)').matches;
    if (wide && name === 'input') $('view-todos').classList.add('active');

    document.querySelectorAll('.tabbar-btn').forEach(function (b) {
      b.classList.toggle('active', b.dataset.view === name);
    });

    if (!quiet) {
      if (name === 'todos') loadTodos();
      if (name === 'stats') loadStats();
      if (name === 'settings') { checkHealth(false); loadDevices(); loadConfig(); renderLastSync(); }
      if (name === 'input') loadInputs();
    }
    try { window.scrollTo({ top: 0, behavior: 'smooth' }); } catch (e) { window.scrollTo(0, 0); }
  }

  // ==========================================================================
  // 16. 事件绑定
  // ==========================================================================
  function bindEvents() {
    // 导航
    document.querySelectorAll('.tabbar-btn').forEach(function (b) {
      b.addEventListener('click', function () { setView(b.dataset.view); });
    });

    // F1.2 文字输入
    $('textInput').addEventListener('input', updateTextCount);
    $('btnClear').addEventListener('click', clearInput);

    // F1.3 图片：点选 / 拍照 / 拖拽
    const zone = $('dropZone');
    zone.addEventListener('click', function (e) {
      if (e.target && e.target.tagName === 'BUTTON') return;
      $('filePicker').click();
    });
    zone.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); $('filePicker').click(); }
    });
    $('btnPick').addEventListener('click', function (e) { e.stopPropagation(); $('filePicker').click(); });
    $('btnCamera').addEventListener('click', function (e) { e.stopPropagation(); $('cameraPicker').click(); });
    $('filePicker').addEventListener('change', function (e) { handleFiles(e.target.files); e.target.value = ''; });
    $('cameraPicker').addEventListener('change', function (e) { handleFiles(e.target.files); e.target.value = ''; });

    ['dragenter', 'dragover'].forEach(function (ev) {
      zone.addEventListener(ev, function (e) { e.preventDefault(); e.stopPropagation(); zone.classList.add('is-over'); });
    });
    ['dragleave', 'dragend'].forEach(function (ev) {
      zone.addEventListener(ev, function (e) { e.preventDefault(); e.stopPropagation(); zone.classList.remove('is-over'); });
    });
    zone.addEventListener('drop', function (e) {
      e.preventDefault();
      e.stopPropagation();
      zone.classList.remove('is-over');
      if (e.dataTransfer && e.dataTransfer.files) handleFiles(e.dataTransfer.files);
    });
    // 防止把图片拖到页面其他位置时浏览器直接打开图片
    window.addEventListener('dragover', function (e) { e.preventDefault(); });
    window.addEventListener('drop', function (e) { e.preventDefault(); });

    // F2 提炼
    $('btnExtract').addEventListener('click', function () { runExtract($('btnExtract')); });
    $('btnSaveAll').addEventListener('click', function () { withBusy($('btnSaveAll'), saveAllDrafts); });
    $('btnReloadDrafts').addEventListener('click', function () {
      if (!state.lastRequest) { showToast('没有可重提的输入', { kind: 'warn' }); return; }
      runExtract($('btnReloadDrafts'), { inputId: state.lastRequest.inputId });
    });
    $('btnDiscardDrafts').addEventListener('click', function () {
      state.drafts = [];
      renderDrafts();
      showToast('已丢弃草稿', { kind: 'ok' });
    });

    // F1.4 输入历史
    $('btnReloadInputs').addEventListener('click', function () { loadInputs(); });

    // F3.1 分组 Tab
    document.querySelectorAll('#todoTabs .tab').forEach(function (tab) {
      tab.addEventListener('click', function () {
        document.querySelectorAll('#todoTabs .tab').forEach(function (t) { t.classList.remove('active'); });
        tab.classList.add('active');
        state.scope = tab.dataset.scope;
        state.selected.clear();
        loadTodos();
      });
    });

    // F3.2 批量完成 / 取消完成 / 多选
    $('btnSelectMode').addEventListener('click', function () { toggleSelectMode(); });
    $('checkAll').addEventListener('change', function (e) {
      if (!state.selectMode) toggleSelectMode(true);
      state.selected.clear();
      if (e.target.checked) state.todos.forEach(function (t) { state.selected.add(t.id); });
      renderTodos();
    });
    $('btnBatchDone').addEventListener('click', function () { batchComplete(true, $('btnBatchDone')); });
    $('btnBatchUndo').addEventListener('click', function () { batchComplete(false, $('btnBatchUndo')); });
    $('btnReloadTodos').addEventListener('click', function () { loadTodos(); loadStats(); });

    // F3.8 看板
    $('btnReloadStats').addEventListener('click', function () { loadStats(); });

    // 设置：后端地址 / 设备 / 同步 / 安装
    $('btnSaveBase').addEventListener('click', function () { withBusy($('btnSaveBase'), saveBaseUrl); });
    $('btnTestHealth').addEventListener('click', function () { withBusy($('btnTestHealth'), function () { return checkHealth(true); }); });
    $('deviceIdInput').addEventListener('change', function () {
      const v = $('deviceIdInput').value.trim();
      if (!v) { $('deviceIdInput').value = state.deviceId; return; }
      state.deviceId = v;
      localStorage.setItem(STORE.deviceId, v);
      showToast('设备 ID 已改为 ' + v + '，建议点「绑定 / 更新」', { kind: 'warn' });
    });
    $('btnBindDevice').addEventListener('click', function () { withBusy($('btnBindDevice'), function () { return bindDevice(true); }); });
    $('btnRegenDevice').addEventListener('click', function () {
      if (!window.confirm('重新生成设备 ID 后，本设备将看不到原来设备名下的数据（云端数据仍在）。继续？')) return;
      state.deviceId = genDeviceId();
      localStorage.setItem(STORE.deviceId, state.deviceId);
      $('deviceIdInput').value = state.deviceId;
      bindDevice(true);
    });
    $('btnSyncNow').addEventListener('click', function () { syncNow($('btnSyncNow')); });
    $('btnClearLocal').addEventListener('click', function () {
      if (!window.confirm('清空本地缓存（设备 ID、后端地址、离线缓存）？')) return;
      Object.keys(STORE).forEach(function (k) { localStorage.removeItem(STORE[k]); });
      state.deviceId = genDeviceId();
      localStorage.setItem(STORE.deviceId, state.deviceId);
      state.baseUrl = '';
      $('deviceIdInput').value = state.deviceId;
      $('baseUrlInput').value = '';
      renderLastSync();
      showToast('本地缓存已清空，已生成新的设备 ID', { kind: 'ok' });
    });
    $('btnInstall').addEventListener('click', function () { promptInstall($('btnInstall')); });
    $('btnInstall2').addEventListener('click', function () { promptInstall($('btnInstall2')); });

    // 提示条
    $('toastClose').addEventListener('click', hideToast);
    $('toastRetry').addEventListener('click', function () {
      const fn = toastRetryFn;
      hideToast();
      if (fn) fn();
    });

    // F3.3 编辑弹窗
    $('editModal').addEventListener('click', function (e) {
      if (e.target && e.target.dataset && e.target.dataset.close) closeEdit();
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') { closeEdit(); hideToast(); }
    });
    $('btnEditSave').addEventListener('click', function () { withBusy($('btnEditSave'), saveEdit); });
    $('btnEditDelete').addEventListener('click', function () {
      const t = state.editTarget;
      if (!t) return;
      if (!window.confirm('确定删除「' + t.title + '」？')) return;
      closeEdit();
      deleteTodo(t);
    });

    // 网络状态变化：给出可见提示（F4.4）
    window.addEventListener('online', function () { showToast('网络已恢复，正在重新加载…', { kind: 'warn' }); refreshAll(); });
    window.addEventListener('offline', function () { setNetStatus(false, '离线'); showToast('网络已断开，将显示本地缓存', { kind: 'warn' }); });

    // 宽窄屏切换时重新计算布局（宽屏左列常驻输入区）；只在真正跨越断点时处理，避免频繁重载
    state.wide = window.matchMedia('(min-width: 900px)').matches;
    window.addEventListener('resize', function () {
      const wide = window.matchMedia('(min-width: 900px)').matches;
      if (wide === state.wide) return;
      state.wide = wide;
      setView(state.view);
    });
  }

  // ==========================================================================
  // 17. 启动
  // ==========================================================================
  async function refreshAll() {
    await Promise.all([checkHealth(false), loadTodos(), loadStats(), loadInputs(), loadConfig()]);
  }

  async function boot() {
    loadLocalSettings();
    setupSpeech();
    setupPwa();
    bindEvents();
    updateTextCount();
    renderImages();
    renderLastSync();
    setView('input', true);

    // F4.1：有设备 ID 就绑定（首次进入必须绑定一次；换 ID 后会自动重绑）
    if (localStorage.getItem(STORE.boundDevice) !== state.deviceId) {
      bindDevice(false);
    } else {
      loadDevices();
    }

    await refreshAll();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
