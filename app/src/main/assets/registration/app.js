import {zipFiles} from './zip.js';

const $ = id => document.getElementById(id);
const bridge = window.AndroidRegistration;
const imageKeys = ['homography', 'geometry', 'global', 'aligned', 'reference', 'mask', 'training_mask'];
const stageNames = {aligned: '最终结果', global: '全局调色', geometry: '稠密几何', homography: '全局透视'};
const state = {
  files: {main: null, tele: null}, urls: {main: null, tele: null}, dimensions: {},
  selections: {main: 0, tele: 0}, incoming: {main: 0, tele: 0}, worker: null,
  importing: {main: false, tele: false},
  busy: false, runId: 0, result: null, unsavedData: null, pendingResult: null,
  bitmaps: {}, stage: 'aligned', mode: 'split', split: 50, zoom: 1,
  panX: 0, panY: 0, rotation: 0, mask: false, diffCache: null, maskCache: null,
  timer: null, runtimeTimer: null, start: 0, options: null, inputMetadata: null,
};
let toastTimer;
const pause = () => new Promise(resolve => setTimeout(resolve, 0));
const active = id => id === state.runId && state.busy;
const importing = () => Object.values(state.importing).some(Boolean);

function nativeCall(method, ...args) {
  const raw = bridge[method](...args);
  const result = typeof raw === 'string' ? JSON.parse(raw) : raw;
  if (!result?.ok) throw new Error(result?.error || '本机文件操作未完成，请重试。');
  return result;
}
function toast(message) {
  $('toast').textContent = message;
  $('toast').hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $('toast').hidden = true, 3800);
}
function error(message) {
  $('errorText').textContent = message;
  $('errorPanel').hidden = false;
}
function updateInputs() {
  for (const key of ['main', 'tele']) {
    const file = state.files[key], d = state.dimensions[key];
    $(key + 'Thumb').hidden = !file;
    $(key + 'Placeholder').hidden = !!file;
    if (file) {
      $(key + 'Thumb').src = state.urls[key];
      $(key + 'Name').textContent = file.name;
      $(key + 'Meta').textContent = `${d.width} × ${d.height} · ${(file.size / 1048576).toFixed(1)} MB` +
        (d.originalWidth && (d.originalWidth !== d.width || d.originalHeight !== d.height) ? ' · 已适配手机尺寸' : '');
    } else {
      $(key + 'Thumb').removeAttribute('src');
      $(key + 'Name').textContent = '选择拍摄记录或手机文件';
      $(key + 'Meta').textContent = 'JPEG · PNG · WebP';
    }
  }
  const inputLocked = state.busy || importing();
  for (const button of document.querySelectorAll('[data-pick], .history-entry')) button.disabled = inputLocked;
  for (const id of ['mainFile', 'teleFile', 'clearBtn', 'refreshHistory', 'retrySave']) $(id).disabled = inputLocked;
  $('importStatus').hidden = !importing();
  $('swapBtn').disabled = inputLocked || !(state.files.main && state.files.tele);
  $('runBtn').disabled = inputLocked || !(state.files.main && state.files.tele);
  $('runBtn').textContent = state.busy ? '正在处理…' : importing() ? '正在导入图片…' : state.result ? '重新配准' : '开始配准';
  if (!state.result && !state.busy) {
    $('emptyView').hidden = false;
    $('canvasView').hidden = true;
    $('resultBadge').textContent = state.files.main || state.files.tele ? '等待配准' : '等待选图';
    $('resultBadge').classList.remove('ready');
  }
  updateExport();
}
function freeResult() {
  for (const bitmap of Object.values(state.bitmaps)) bitmap.close?.();
  state.bitmaps = {};
  state.result = null;
  state.unsavedData = null;
  state.diffCache = null;
  state.maskCache = null;
  $('metricsPanel').hidden = $('downloadPanel').hidden = $('resultControls').hidden = true;
  $('outputSize').textContent = '';
  $('retrySave').hidden = true;
}
async function selectFile(key, file, imported = {}) {
  if (state.busy || !file) return;
  const token = ++state.selections[key];
  if (file.size > 30 * 1024 * 1024) { error('每张图片不能超过 30 MB，请选择较小文件。'); return; }
  if (!/\.(jpe?g|png|webp)$/i.test(file.name) && !['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
    error('请选择 JPEG、PNG 或 WebP 图片。RAW 原片请先导出为 JPEG。'); return;
  }
  let bitmap, dimensions;
  try {
    bitmap = await createImageBitmap(file);
    if (bitmap.width * bitmap.height > 60e6) throw new Error('图片超过 6000 万像素，请先缩小后再试。');
    if (Math.min(bitmap.width, bitmap.height) < 128) throw new Error('图片过小，请使用短边至少 128 像素的照片。');
    dimensions = {...imported, width: bitmap.width, height: bitmap.height};
  } catch (e) {
    if (token === state.selections[key]) error(e.message?.includes('像素') ? e.message : '无法读取图片，请尝试重新导出为 JPEG 或 PNG。');
    return;
  } finally { bitmap?.close(); }
  if (token !== state.selections[key] || state.busy) return;
  state.dimensions[key] = dimensions;
  if (state.urls[key]) URL.revokeObjectURL(state.urls[key]);
  state.files[key] = file;
  state.urls[key] = URL.createObjectURL(file);
  freeResult();
  $('errorPanel').hidden = true;
  updateInputs();
}
window.PairShotNative = {
  onImporting(slot) {
    if (!['main', 'tele'].includes(slot)) return;
    state.importing[slot] = true;
    state.incoming[slot]++;
    updateInputs();
  },
  async onInput(slot, info) {
    if (!['main', 'tele'].includes(slot)) return;
    if (state.busy) { state.importing[slot] = false; updateInputs(); return; }
    const token = ++state.incoming[slot];
    state.importing[slot] = true;
    updateInputs();
    try {
      const url = new URL(info.url, location.href);
      if (url.origin !== location.origin || !url.pathname.startsWith('/inputs/')) throw new Error('无法读取所选图片。');
      const response = await fetch(url.href);
      if (!response.ok) throw new Error('所选图片已失效，请重新选择。');
      const blob = await response.blob();
      if (token !== state.incoming[slot] || state.busy) return;
      await selectFile(slot, new File([blob], info.name || 'photo.png', {type: info.mime || 'image/png'}), info);
    } catch (e) { if (token === state.incoming[slot]) error(e.message || '导入图片失败。'); }
    finally {
      if (token === state.incoming[slot]) { state.importing[slot] = false; updateInputs(); }
    }
  },
  onError(message, slot) {
    for (const key of ['main', 'tele']) if (!slot || slot === key) state.importing[key] = false;
    updateInputs(); error(String(message));
  },
  cancel() { cancelWork(); },
};
for (const button of document.querySelectorAll('[data-pick]')) {
  button.onclick = () => {
    if (state.busy || importing()) return;
    const {pick, source} = button.dataset;
    if (bridge) {
      try { bridge.pickImage(pick, source); } catch { error('无法打开选图器，请重新打开工具页。'); }
    } else if (source === 'device') $(pick + 'File').click();
    else toast('拍摄记录可在 PairShot App 中选择');
  };
}
for (const key of ['main', 'tele']) {
  $(key + 'File').addEventListener('change', async event => {
    if (state.busy || state.importing[key] || !event.target.files[0]) return;
    state.importing[key] = true; updateInputs();
    try { await selectFile(key, event.target.files[0]); }
    finally { state.importing[key] = false; updateInputs(); }
    event.target.value = '';
  });
}
$('swapBtn').onclick = () => {
  if (state.busy || importing()) return;
  for (const key of ['main', 'tele']) { state.selections[key]++; state.incoming[key]++; }
  [state.files.main, state.files.tele] = [state.files.tele, state.files.main];
  [state.urls.main, state.urls.tele] = [state.urls.tele, state.urls.main];
  [state.dimensions.main, state.dimensions.tele] = [state.dimensions.tele, state.dimensions.main];
  freeResult(); updateInputs(); toast('已交换待配准图与参考图');
};
function setBusy(value) {
  state.busy = value;
  for (const id of ['mainFile', 'teleFile', 'resolution', 'localColor', 'clearBtn', 'refreshHistory', 'retrySave']) $(id).disabled = value;
  for (const button of document.querySelectorAll('[data-pick], .history-entry')) button.disabled = value;
  $('progressPanel').hidden = !value;
  $('runBtn').textContent = value ? '正在处理…' : state.result ? '重新配准' : '开始配准';
  if (value) { $('resultBadge').textContent = '正在处理'; $('errorPanel').hidden = true; }
  else clearInterval(state.timer);
  if (bridge) { try { bridge.setBusy(value); } catch { /* Page remains cancellable if the host is closing. */ } }
  updateInputs(); updateExport();
}
function updateProgress(data) {
  const progress = Math.max(0, Math.min(100, Number(data.progress) || 0));
  $('progressLabel').textContent = data.label || '正在处理';
  $('progressPercent').textContent = Math.round(progress) + '%';
  $('progressBar').value = progress;
}
function stopWorker() {
  clearTimeout(state.runtimeTimer);
  state.runtimeTimer = null;
  state.worker?.terminate();
  state.worker = null;
}
function abandonPending() {
  const id = state.pendingResult;
  state.pendingResult = null;
  if (id && bridge) { try { bridge.abandonResult(id); } catch { /* Uncommitted results are cleaned by the native store. */ } }
}
function cancelWork() {
  if (!state.busy) return;
  stopWorker();
  state.runId++;
  abandonPending();
  setBusy(false);
  if (state.result) $('resultBadge').textContent = state.result.persistedId ? '已保存' : '尚未保存';
  toast('已取消，已选照片仍保留');
}
function normalizeImages(images) {
  const normalized = {...images};
  if (normalized.training) { normalized.training_mask = normalized.training; delete normalized.training; }
  for (const key of imageKeys) if (!(normalized[key] instanceof ArrayBuffer)) throw new Error('计算结果不完整，请重新配准。');
  return normalized;
}
function startWorker(runId, main, tele) {
  const worker = new Worker('./compute-worker.js', {type: 'module'});
  state.worker = worker;
  // Broken/incompatible WebAssembly initialization can leave Pyodide awaiting
  // forever. Bound only initialization; real registration has no time limit.
  state.runtimeTimer = setTimeout(() => {
    if (!active(runId) || state.worker !== worker) return;
    stopWorker(); setBusy(false);
    error('离线计算引擎加载超时。请重新打开工具页；若仍失败，请更新 Android System WebView 后重试。');
  }, 180000);
  worker.onmessage = async ({data}) => {
    if (!active(runId) || data.id !== runId) return;
    if (data.type === 'progress') {
      if (data.stage && data.stage !== 'runtime') {
        clearTimeout(state.runtimeTimer); state.runtimeTimer = null;
      }
      updateProgress(data);
    }
    if (data.type === 'error') {
      stopWorker(); setBusy(false);
      error(data.message || '配准失败，请确认图片有足够的场景重叠。');
    }
    if (data.type === 'result') {
      // Release the large WASM heap once its transferred PNGs have arrived.
      // A subsequent job creates a fresh worker, avoiding retained mobile memory.
      stopWorker();
      try {
        data.images = normalizeImages(data.images);
        data.metadata = {metrics: data.metrics, inputs: state.inputMetadata, options: state.options};
        let persistedId = null, saveError = null;
        if (bridge) {
          try { persistedId = await persistResult(data, runId); }
          catch (e) { if (active(runId)) saveError = e; }
        }
        if (!active(runId)) return;
        await showResult(data, runId, persistedId);
        if (saveError && runId === state.runId) {
          state.unsavedData = data;
          updateExport();
          error('结果已计算，但未保存：' + saveError.message);
        }
        refreshHistory();
      } catch (e) {
        if (runId !== state.runId) return;
        abandonPending(); freeResult(); setBusy(false);
        error(e.message || '结果显示失败，请降低到 1024 px 后重试。');
      }
    }
  };
  worker.onerror = () => {
    if (!active(runId)) return;
    stopWorker(); setBusy(false);
    error('计算线程意外停止，可能是可用内存不足。请选 1024 px，或关闭其他应用后重试。');
  };
  worker.postMessage({type: 'run', id: runId, main, tele, options: state.options}, [main, tele]);
}
$('runBtn').onclick = async () => {
  if (state.busy || importing() || !state.files.main || !state.files.tele) return;
  freeResult();
  state.runId++;
  const runId = state.runId;
  state.options = {maxSide: Number($('resolution').value), localColor: $('localColor').checked, flowRefine: true};
  state.inputMetadata = Object.fromEntries(['main', 'tele'].map(key => [key, {
    name: state.files[key].name, width: state.dimensions[key].width, height: state.dimensions[key].height,
    originalWidth: state.dimensions[key].originalWidth, originalHeight: state.dimensions[key].originalHeight,
  }]));
  setBusy(true);
  state.start = Date.now();
  updateProgress({progress: 0, label: '启动离线计算引擎'});
  state.timer = setInterval(() => {
    $('progressDetail').textContent = `已用时 ${Math.floor((Date.now() - state.start) / 1000)} 秒 · 可随时取消`;
  }, 1000);
  try {
    const [main, tele] = await Promise.all([state.files.main.arrayBuffer(), state.files.tele.arrayBuffer()]);
    if (active(runId)) startWorker(runId, main, tele);
  } catch (e) {
    if (active(runId)) { stopWorker(); setBusy(false); error(e.message || '无法启动计算，请重新打开工具页。'); }
  }
};
$('cancelBtn').onclick = cancelWork;

async function persistResult(data, runId) {
  if (!active(runId)) throw new Error('已取消保存');
  const id = nativeCall('beginResult', JSON.stringify(data.metadata)).id;
  state.pendingResult = id;
  try {
    let done = 0;
    const total = imageKeys.reduce((sum, key) => sum + data.images[key].byteLength, 0);
    for (const key of imageKeys) {
      const bytes = new Uint8Array(data.images[key]);
      for (let offset = 0; offset < bytes.length; offset += 49152) {
        if (!active(runId)) throw new Error('已取消保存');
        const chunk = bytes.subarray(offset, Math.min(offset + 49152, bytes.length));
        const encoded = btoa(String.fromCharCode(...chunk));
        nativeCall('appendResult', id, key + '.png', encoded);
        done += chunk.length;
        updateProgress({label: '保存结果到手机', progress: 96 + 3 * done / Math.max(1, total)});
        await pause();
      }
    }
    if (!active(runId)) throw new Error('已取消保存');
    nativeCall('commitResult', id);
    state.pendingResult = null;
    return id;
  } catch (e) {
    if (state.pendingResult === id) abandonPending();
    throw e;
  }
}
async function showResult(data, runId, persistedId = null) {
  const bitmaps = {};
  try {
    for (const key of imageKeys) {
      bitmaps[key] = await createImageBitmap(new Blob([data.images[key]], {type: 'image/png'}));
      if (runId !== state.runId) { Object.values(bitmaps).forEach(bitmap => bitmap.close()); return; }
    }
  } catch (e) { Object.values(bitmaps).forEach(bitmap => bitmap.close()); throw e; }
  for (const bitmap of Object.values(state.bitmaps)) bitmap.close?.();
  state.bitmaps = bitmaps;
  state.result = {metrics: data.metrics, images: bridge && persistedId ? {} : data.images, persistedId};
  state.stage = 'aligned'; state.mode = 'split'; state.mask = false; state.rotation = 0; state.split = 50;
  state.diffCache = state.maskCache = null;
  $('stageSelect').value = 'aligned'; $('splitRange').value = 50; $('maskToggle').checked = false;
  $('emptyView').hidden = true;
  $('canvasView').hidden = $('metricsPanel').hidden = $('downloadPanel').hidden = $('resultControls').hidden = false;
  $('resultBadge').classList.add('ready');
  const m = data.metrics;
  $('metricArea').textContent = Number.isFinite(m.geometric_heuristic_fraction) ? (m.geometric_heuristic_fraction * 100).toFixed(1) + '%' : '—';
  $('metricMatches').textContent = Number.isFinite(m.ransac_inliers) ? m.ransac_inliers.toLocaleString() : '—';
  $('metricTime').textContent = Number.isFinite(m.seconds) ? m.seconds.toFixed(1) + ' s' : '—';
  $('outputSize').textContent = `${bitmaps.reference.width} × ${bitmaps.reference.height}`;
  updateMetricColor(); resetView(); setMode('split'); resizeCanvas();
  setBusy(false);
  $('resultBadge').textContent = persistedId ? '已保存' : bridge ? '尚未保存' : '计算完成';
  $('viewerPanel').scrollIntoView({behavior: 'smooth', block: 'start'});
  if (m.geometric_heuristic_fraction < .5) toast('部分区域未通过几何筛选，可开启“不可靠区域”检查');
  else toast(persistedId ? '结果已保存，可对比或导出' : '配准完成');
}
function updateMetricColor() {
  const m = state.result?.metrics;
  const name = {aligned: 'local_color', global: 'global_color', geometry: 'dense_geometry', homography: 'homography'}[state.stage];
  const before = m?.metrics_common_support?.homography?.lowpass_mae_255;
  const after = m?.metrics_common_support?.[name]?.lowpass_mae_255;
  $('metricColor').textContent = Number.isFinite(before) && Number.isFinite(after) ? `${before.toFixed(2)} → ${after.toFixed(2)}` : '—';
  const trusted = m?.metrics_geometric_common?.[name]?.lowpass_mae_255;
  $('metricTrusted').textContent = Number.isFinite(trusted) ? `几何筛选区 ${trusted.toFixed(2)}` : '几何筛选区支持不足';
}
function updateExport() {
  const exportable = !!state.result && (!bridge || !!state.result.persistedId) && !state.busy && !importing();
  for (const id of ['downloadAligned', 'downloadAll', 'downloadMask']) $(id).disabled = !exportable;
  $('retrySave').hidden = !state.unsavedData;
  $('savedLabel').textContent = state.result?.persistedId ? '结果已保存到工具历史，原图未修改' : bridge ? '结果尚未保存，可重试保存' : '浏览器预览：可下载结果，在 App 内会自动保存';
}
$('retrySave').onclick = async () => {
  if (state.busy || importing() || !state.unsavedData) return;
  state.runId++;
  const runId = state.runId, data = state.unsavedData;
  setBusy(true);
  try {
    const id = await persistResult(data, runId);
    if (!active(runId)) return;
    state.result.persistedId = id;
    state.result.images = {};
    state.unsavedData = null;
    setBusy(false);
    $('resultBadge').textContent = '已保存';
    refreshHistory(); toast('结果已保存');
  } catch (e) {
    if (active(runId)) { setBusy(false); error('保存失败：' + e.message); }
  }
};

async function loadHistory(id) {
  if (state.busy || importing()) return;
  state.runId++;
  const runId = state.runId;
  setBusy(true); updateProgress({label: '读取历史结果', progress: 10});
  try {
    const saved = nativeCall('getResult', id);
    const images = {};
    for (const key of imageKeys) {
      const url = new URL(saved.images[key], location.href);
      if (url.origin !== location.origin || !url.pathname.startsWith('/results/')) throw new Error('结果文件路径无效。');
      const response = await fetch(url.href);
      if (!response.ok) throw new Error('结果文件缺失，请重新配准。');
      images[key] = await response.arrayBuffer();
      if (!active(runId)) return;
    }
    freeResult();
    await showResult({metrics: saved.metadata.metrics, images}, runId, id);
  } catch (e) {
    if (active(runId)) { setBusy(false); error(e.message || '无法读取历史结果。'); }
  }
}
function refreshHistory() {
  if (!bridge) { $('historyList').textContent = '在 App 内完成配准后，历史结果会自动保留。'; $('historyList').classList.add('small-note'); return; }
  try {
    const {results = []} = nativeCall('listResults');
    $('historyList').replaceChildren();
    if (!results.length) {
      const p = document.createElement('p'); p.className = 'small-note'; p.textContent = '还没有保存的结果'; $('historyList').appendChild(p);
    }
    for (const result of results) {
      const button = document.createElement('button');
      button.className = 'history-entry'; button.disabled = state.busy || importing();
      const info = document.createElement('span'); info.className = 'history-info';
      const title = document.createElement('strong'); title.textContent = result.label || '配准结果';
      const detail = document.createElement('small');
      detail.textContent = new Date(result.createdAt).toLocaleString('zh-CN', {month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'}) +
        (result.width && result.height ? ` · ${result.width} × ${result.height}` : '');
      info.append(title, detail);
      const arrow = document.createElement('span'); arrow.textContent = '查看 ›';
      button.append(info, arrow); button.onclick = () => loadHistory(result.id); $('historyList').appendChild(button);
    }
  } catch (e) { error('无法读取历史结果：' + e.message); }
}
$('refreshHistory').onclick = refreshHistory;

const canvas = $('viewer'), ctx = canvas.getContext('2d');
let width = 1, height = 1;
function resizeCanvas() {
  if (!state.result) return;
  const box = $('canvasView').getBoundingClientRect();
  width = Math.max(1, box.width); height = Math.max(1, box.height);
  const dpr = Math.min(devicePixelRatio || 1, 2);
  canvas.width = Math.round(width * dpr); canvas.height = Math.round(height * dpr);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0); draw();
}
function transformInfo() {
  const image = state.bitmaps.reference;
  if (!image) return null;
  const rotated = state.rotation % 180 !== 0;
  const rw = rotated ? image.height : image.width, rh = rotated ? image.width : image.height;
  const fit = Math.min((width - 20) / rw, (height - 20) / rh);
  return {image, scale: fit * state.zoom, cx: width / 2 + state.panX, cy: height / 2 + state.panY};
}
function drawImage(image, alpha = 1) {
  const t = transformInfo(); if (!t || !image) return;
  ctx.save(); ctx.globalAlpha = alpha;
  ctx.translate(t.cx, t.cy); ctx.rotate(state.rotation * Math.PI / 180); ctx.scale(t.scale, t.scale);
  ctx.imageSmoothingEnabled = true; ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(image, -t.image.width / 2, -t.image.height / 2, t.image.width, t.image.height); ctx.restore();
}
function offscreen(image) {
  const c = document.createElement('canvas'); c.width = image.width; c.height = image.height;
  c.getContext('2d').drawImage(image, 0, 0); return c;
}
function getDifference() {
  if (state.diffCache) return state.diffCache;
  const ref = offscreen(state.bitmaps.reference), other = offscreen(state.bitmaps[state.stage]);
  const r = ref.getContext('2d').getImageData(0, 0, ref.width, ref.height), a = other.getContext('2d').getImageData(0, 0, ref.width, ref.height);
  for (let i = 0; i < r.data.length; i += 4) {
    for (let c = 0; c < 3; c++) r.data[i + c] = Math.min(255, 4 * Math.abs(r.data[i + c] - a.data[i + c]));
    r.data[i + 3] = 255;
  }
  ref.getContext('2d').putImageData(r, 0, 0); state.diffCache = ref; return ref;
}
function getMask() {
  if (state.maskCache) return state.maskCache;
  const c = offscreen(state.bitmaps.mask), context = c.getContext('2d'), image = context.getImageData(0, 0, c.width, c.height);
  for (let i = 0; i < image.data.length; i += 4) {
    const bad = image.data[i] < 128;
    image.data[i] = 244; image.data[i + 1] = 94; image.data[i + 2] = 156; image.data[i + 3] = bad ? 100 : 0;
  }
  context.putImageData(image, 0, 0); state.maskCache = c; return c;
}
function draw() {
  if (!state.result) return;
  ctx.clearRect(0, 0, width, height); ctx.fillStyle = '#090e0f'; ctx.fillRect(0, 0, width, height);
  if (state.mode === 'diff') drawImage(getDifference());
  else {
    drawImage(state.bitmaps.reference);
    if (state.mode === 'split') {
      ctx.save(); ctx.beginPath(); ctx.rect(0, 0, width * state.split / 100, height); ctx.clip();
      drawImage(state.bitmaps[state.stage]); if (state.mask) drawImage(getMask()); ctx.restore();
    } else { drawImage(state.bitmaps[state.stage], .5); if (state.mask) drawImage(getMask()); }
  }
  if (state.mode === 'diff' && state.mask) drawImage(getMask());
  $('splitHandle').style.left = state.split + '%'; $('splitHandle').setAttribute('aria-valuenow', String(Math.round(state.split)));
  $('zoomLabel').textContent = state.zoom === 1 ? '适应' : Math.round(state.zoom * 100) + '%';
  $('viewRotation').textContent = `视图旋转 ${state.rotation}°`; $('viewRotation').hidden = !state.rotation;
}
function resetView() { state.zoom = 1; state.panX = state.panY = 0; draw(); }
function setMode(mode) {
  state.mode = mode;
  $('splitHandle').hidden = $('splitRange').hidden = mode !== 'split';
  document.querySelectorAll('[data-mode]').forEach(button => button.classList.toggle('active', button.dataset.mode === mode));
  $('leftLabel').textContent = mode === 'diff' ? 'RGB 绝对差 ×4' : mode === 'blend' ? '结果与参考图 · 50% 叠加' : stageNames[state.stage];
  $('rightLabel').hidden = mode !== 'split';
  $('canvasHint').textContent = mode === 'split' ? '滑动分界线对比 · 双指缩放 · 拖动平移' : '双指缩放 · 拖动平移';
  draw();
}
function zoomBy(factor, px = width / 2, py = height / 2) {
  const old = state.zoom; state.zoom = Math.min(12, Math.max(.5, state.zoom * factor));
  const ratio = state.zoom / old;
  state.panX = px - width / 2 - (px - width / 2 - state.panX) * ratio;
  state.panY = py - height / 2 - (py - height / 2 - state.panY) * ratio; draw();
}
document.querySelectorAll('[data-mode]').forEach(button => button.onclick = () => setMode(button.dataset.mode));
$('stageSelect').onchange = event => { state.stage = event.target.value; state.diffCache = null; updateMetricColor(); setMode(state.mode); };
$('maskToggle').onchange = event => { state.mask = event.target.checked; draw(); };
$('splitRange').oninput = event => { state.split = Number(event.target.value); draw(); };
$('rotateBtn').onclick = () => { state.rotation = (state.rotation + 90) % 360; resetView(); };
$('zoomLabel').onclick = resetView;
$('zoomIn').onclick = () => zoomBy(1.3); $('zoomOut').onclick = () => zoomBy(1 / 1.3);
canvas.addEventListener('wheel', event => {
  if (!state.result) return;
  event.preventDefault(); const rect = canvas.getBoundingClientRect();
  zoomBy(Math.exp(-event.deltaY * .002), event.clientX - rect.left, event.clientY - rect.top);
}, {passive: false});
const pointers = new Map();
let gesture = null;
function rememberGesture() {
  const points = [...pointers.values()];
  if (!points.length) { gesture = null; return; }
  if (points.length === 1) gesture = {x: points[0].x, y: points[0].y, panX: state.panX, panY: state.panY};
  else gesture = {distance: Math.hypot(points[1].x - points[0].x, points[1].y - points[0].y)};
}
canvas.addEventListener('pointerdown', event => {
  if (!state.result) return;
  pointers.set(event.pointerId, {x: event.clientX, y: event.clientY}); canvas.setPointerCapture(event.pointerId); rememberGesture();
});
canvas.addEventListener('pointermove', event => {
  if (!pointers.has(event.pointerId) || !gesture) return;
  pointers.set(event.pointerId, {x: event.clientX, y: event.clientY});
  if (pointers.size === 1) {
    state.panX = gesture.panX + event.clientX - gesture.x; state.panY = gesture.panY + event.clientY - gesture.y; draw();
  } else {
    const [a, b] = [...pointers.values()], distance = Math.hypot(b.x - a.x, b.y - a.y), rect = canvas.getBoundingClientRect();
    if (gesture.distance > 0) zoomBy(distance / gesture.distance, (a.x + b.x) / 2 - rect.left, (a.y + b.y) / 2 - rect.top);
    gesture.distance = distance;
  }
});
for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) canvas.addEventListener(type, event => { pointers.delete(event.pointerId); rememberGesture(); });
let splitting = false;
const handle = $('splitHandle');
handle.addEventListener('pointerdown', event => { splitting = true; handle.setPointerCapture(event.pointerId); event.preventDefault(); });
handle.addEventListener('pointermove', event => {
  if (!splitting) return;
  state.split = Math.max(0, Math.min(100, (event.clientX - canvas.getBoundingClientRect().left) / width * 100));
  $('splitRange').value = state.split; draw();
});
for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) handle.addEventListener(type, () => splitting = false);
handle.addEventListener('keydown', event => {
  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
  event.preventDefault(); state.split = Math.max(0, Math.min(100, state.split + (event.key === 'ArrowLeft' ? -2 : 2)));
  $('splitRange').value = state.split; draw();
});
new ResizeObserver(resizeCanvas).observe($('canvasView'));

function download(blob, name) {
  const url = URL.createObjectURL(blob), a = document.createElement('a');
  a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}
function exportImage(asset) {
  if (!state.result || state.busy) return;
  if (bridge) {
    if (!state.result.persistedId) return;
    try { bridge.exportResult(state.result.persistedId, asset); } catch { error('无法打开保存位置，请重试。'); }
  } else download(new Blob([state.result.images[asset.replace(/\.png$/, '')]], {type: 'image/png'}), 'pairshot_' + asset);
}
$('downloadAligned').onclick = () => exportImage(state.stage + '.png');
$('downloadMask').onclick = () => exportImage('training_mask.png');
$('downloadAll').onclick = () => {
  if (!state.result || state.busy) return;
  if (bridge) { exportImage('zip'); return; }
  const files = {};
  for (const key of imageKeys) files[key + '.png'] = state.result.images[key];
  files['metrics.json'] = new TextEncoder().encode(JSON.stringify(state.result.metrics, null, 2));
  files['README.txt'] = new TextEncoder().encode('PairShot 配准结果\nreference.png：参考图\nhomography.png：全局透视\ngeometry.png：稠密几何\nglobal.png：几何与全局调色\naligned.png：最终校正\nmask.png：几何质量筛选\ntraining_mask.png：训练候选掩码\n原图未修改；掩码不代表准确率。视图旋转和缩放不改变导出图像。');
  download(zipFiles(files), 'pairshot_results.zip');
};
for (const id of ['resolution', 'localColor']) $(id).addEventListener('change', () => { if (state.result) toast('新设置将在下一次配准时生效'); });
$('clearBtn').onclick = () => {
  if (state.busy || importing()) return;
  stopWorker(); state.runId++; freeResult();
  for (const key of ['main', 'tele']) {
    state.selections[key]++; state.incoming[key]++;
    if (state.urls[key]) URL.revokeObjectURL(state.urls[key]); state.files[key] = state.urls[key] = null;
  }
  state.dimensions = {}; $('errorPanel').hidden = true;
  updateInputs(); toast('当前选图已清空，历史结果仍保留');
};
$('dismissError').onclick = () => $('errorPanel').hidden = true;
$('helpBtn').onclick = () => $('helpDialog').showModal();
$('closeHelp').onclick = () => $('helpDialog').close();
window.addEventListener('pagehide', () => { stopWorker(); abandonPending(); if (bridge) { try { bridge.setBusy(false); } catch {} } });
updateInputs(); refreshHistory();
// The host queues image imports until the complete module has registered all
// callbacks. onPageFinished alone can precede async module execution.
if (bridge) { try { bridge.pageReady?.(); } catch { /* Browser QA may provide a partial bridge. */ } }
