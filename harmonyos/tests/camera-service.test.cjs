// Executes the actual CameraService with mocked HarmonyOS native APIs.
// These checks validate ownership/cancellation, not device camera compatibility.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const { test } = require('node:test');

const toolsHome = process.env.DEVECO_HOME || '/workspace/.harmony-environment/command-line-tools';
const compiler = require(path.join(toolsHome, 'codelinter/node_modules/typescript'));

function loadSource(name, dependencies, injected = {}) {
  const filename = path.resolve(__dirname, '../entry/src/main/ets', name);
  const source = compiler.transpileModule(fs.readFileSync(filename, 'utf8'), {
    fileName: filename.replace(/\.ets$/, '.ts'),
    compilerOptions: { module: compiler.ModuleKind.CommonJS, target: compiler.ScriptTarget.ES2020 },
  }).outputText;
  const loaded = new Module(filename, module);
  loaded.filename = filename;
  loaded.require = specifier => {
    if (Object.hasOwn(dependencies, specifier)) return dependencies[specifier];
    throw new Error(`Unexpected native import: ${specifier}`);
  };
  const wrapper = new Function('exports', 'require', 'module', 'setTimeout', 'clearTimeout', source);
  wrapper(loaded.exports, loaded.require, loaded, injected.setTimeout || setTimeout,
    injected.clearTimeout || clearTimeout);
  return loaded.exports;
}

const models = loadSource('model/Models.ets', {});

function makeHarness() {
  const state = { inputs: [], outputs: [], sessions: [], order: [], timers: new Map(), nextTimer: 0,
    openGate: undefined, displayRotation: 0, displayListeners: new Set() };
  const devices = [
    { cameraId: 'back', cameraPosition: 1, cameraType: 1 },
    { cameraId: 'front', cameraPosition: 2, cameraType: 0 },
    { cameraId: 'video-only', cameraPosition: 1, cameraType: 0 },
    { cameraId: 'no-jpeg', cameraPosition: 1, cameraType: 0 },
  ];
  const photoProfile = { format: 2000, size: { width: 4000, height: 3000 } };
  const previewProfile = { format: 1003, size: { width: 1280, height: 960 } };
  function emitter(base = {}) {
    const callbacks = new Map();
    return Object.assign(base, {
      callbacks,
      on(type, ...rest) { callbacks.set(type, rest.at(-1)); },
      off(type) { callbacks.delete(type); },
    });
  }
  const manager = {
    getSupportedCameras: () => devices,
    getSupportedSceneModes: device => device.cameraId === 'video-only' ? [2] : [1],
    getSupportedOutputCapability: device => ({ previewProfiles: [previewProfile],
      photoProfiles: device.cameraId === 'no-jpeg' ? [] : [photoProfile] }),
    createCameraInput(device) {
      const input = emitter({ closed: 0,
        async open() { state.order.push(`open:${device.cameraId}`); await state.openGate; },
        async close() { input.closed++; state.order.push(`close:${device.cameraId}`); },
      });
      state.inputs.push(input);
      return input;
    },
    createPreviewOutput: () => emitter({
      async release() {}, getPreviewRotation: degrees => ((90 - degrees) % 360 + 360) % 360,
      setPreviewRotation() {},
      getActiveProfile: () => previewProfile,
    }),
    createPhotoOutput() {
      const output = emitter({ captures: 0, released: 0,
        async capture() { output.captures++; },
        async release() { output.released++; }, getPhotoRotation: () => 90,
      });
      state.outputs.push(output);
      return output;
    },
    createSession() {
      let zoom = 1;
      const session = emitter({
        beginConfig() {}, canAddInput: () => true, addInput() {}, canAddOutput: () => true,
        addOutput() {}, async commitConfig() {}, async start() {}, async stop() {}, async release() {},
        isFocusModeSupported: () => false, isExposureModeSupported: () => false,
        getZoomRatioRange: () => [0.5, 6], getZoomRatio: () => zoom,
        setZoomRatio(value) { zoom = value; },
      });
      state.sessions.push(session);
      return session;
    },
  };
  const camera = {
    getCameraManager: () => manager,
    SceneMode: { NORMAL_PHOTO: 1 }, CameraFormat: { CAMERA_FORMAT_JPEG: 2000, CAMERA_FORMAT_YUV_420_SP: 1003 },
    CameraPosition: { CAMERA_POSITION_BACK: 1, CAMERA_POSITION_FRONT: 2 },
    CameraType: { CAMERA_TYPE_DEFAULT: 0, CAMERA_TYPE_WIDE_ANGLE: 1, CAMERA_TYPE_ULTRA_WIDE: 2,
      CAMERA_TYPE_TELEPHOTO: 3, CAMERA_TYPE_TRUE_DEPTH: 4 },
    FocusMode: { FOCUS_MODE_CONTINUOUS_AUTO: 1 }, ExposureMode: { EXPOSURE_MODE_CONTINUOUS_AUTO: 2 },
    QualityLevel: { QUALITY_LEVEL_HIGH: 0 }, ImageRotation: { ROTATION_90: 90, ROTATION_270: 270 },
  };
  const exports = loadSource('services/CameraService.ets', {
    '@kit.CameraKit': { camera }, '@kit.ImageKit': { image: { ComponentType: { JPEG: 4 } } },
    '@kit.ArkUI': { display: {
      getDefaultDisplaySync: () => ({ rotation: state.displayRotation }),
      on(type, callback) { if (type === 'change') state.displayListeners.add(callback); },
      off(type, callback) { if (type === 'change') state.displayListeners.delete(callback); },
    } },
    '../model/Models': models,
  }, {
    setTimeout(fn) { const id = ++state.nextTimer; state.timers.set(id, fn); return id; },
    clearTimeout(id) { state.timers.delete(id); },
  });
  return { service: new exports.CameraService({}), state };
}

function nativePhoto(bytes = [0xff, 0xd8, 0x11, 0xff, 0xd9]) {
  const buffer = new Uint8Array(bytes).buffer;
  const result = { imageReleased: 0, photoReleased: 0,
    main: {
      async getComponent() { return { byteBuffer: buffer }; },
      async release() { result.imageReleased++; new Uint8Array(buffer).fill(0); },
    },
    async release() { result.photoReleased++; },
  };
  return result;
}

async function flush() {
  for (let index = 0; index < 12; index++) await Promise.resolve();
}

test('only photo-capable JPEG cameras are listed, zoom uses the published range', async () => {
  const { service } = makeHarness();
  assert.deepEqual(service.listCameras().map(lens => lens.id), ['back', 'front']);
  await service.startPreview('back', 'surface');
  assert.deepEqual(service.getZoomRange(), [0.5, 6]);
  assert.equal(service.setZoom(30), 6);
  assert.equal(service.setZoom(0.1), 0.5);
  assert.throws(() => service.setZoom(NaN), /倍率无效/);
  await service.stop();
});

test('JPEG is copied before native buffers are released, with capture metadata intact', async () => {
  const { service, state } = makeHarness();
  await service.startPreview('back', 'surface');
  service.setZoom(2);
  const pending = service.capture();
  const native = nativePhoto();
  state.outputs[0].callbacks.get('photoAvailable')(null, native);
  const result = await pending;
  assert.deepEqual(Array.from(new Uint8Array(result.jpeg)), [0xff, 0xd8, 0x11, 0xff, 0xd9]);
  assert.equal(result.lensId, 'back');
  assert.equal(result.zoom, 2);
  assert.ok(result.capturedAt > 0);
  assert.equal(native.imageReleased, 1);
  assert.equal(native.photoReleased, 1);
  assert.equal(state.timers.size, 0);
  await service.stop();
});

test('stop rejects a pending capture and releases late-arriving native images', async () => {
  const { service, state } = makeHarness();
  await service.startPreview('back', 'surface');
  const lateCallback = state.outputs[0].callbacks.get('photoAvailable');
  const pending = assert.rejects(service.capture(), /停止/);
  await service.stop();
  await pending;
  const late = nativePhoto();
  lateCallback(null, late);
  await flush();
  assert.equal(service.getCurrentLensId(), '');
  assert.equal(late.imageReleased, 1);
  assert.equal(late.photoReleased, 1);
  assert.equal(state.timers.size, 0);
  assert.equal(state.inputs[0].closed, 1);
});

test('timed-out capture cannot satisfy a later request after the camera is reopened', async () => {
  const { service, state } = makeHarness();
  await service.startPreview('back', 'surface');
  const staleCallback = state.outputs[0].callbacks.get('photoAvailable');
  const failed = assert.rejects(service.capture(), /超时/);
  Array.from(state.timers.values())[0]();
  await failed;
  await service.startPreview('front', 'surface');
  let completed = false;
  const freshPending = service.capture().then(photo => { completed = true; return photo; });
  const stale = nativePhoto();
  staleCallback(null, stale);
  await flush();
  assert.equal(completed, false);
  assert.equal(stale.photoReleased, 1);
  state.outputs.at(-1).callbacks.get('photoAvailable')(null, nativePhoto([0xff, 0xd8, 0x22, 0xff, 0xd9]));
  const fresh = await freshPending;
  assert.equal(fresh.lensId, 'front');
  assert.equal(new Uint8Array(fresh.jpeg)[2], 0x22);
  await service.stop();
});

test('background stop during asynchronous input opening cancels initialization', async () => {
  const { service, state } = makeHarness();
  let open;
  state.openGate = new Promise(resolve => { open = resolve; });
  const starting = assert.rejects(service.startPreview('back', 'surface'), /取消/);
  await flush();
  const stopping = service.stop();
  open();
  await starting;
  await stopping;
  assert.equal(state.inputs[0].closed, 1);
  assert.equal(state.sessions.length, 0);
  assert.equal(service.getCurrentLensId(), '');
});

test('sequential lens switching closes the first input before opening the second', async () => {
  const { service, state } = makeHarness();
  await service.startPreview('back', 'surface');
  await service.startPreview('front', 'surface');
  assert.deepEqual(state.order, ['open:back', 'close:back', 'open:front']);
  assert.equal(service.getCurrentLensId(), 'front');
  await service.stop();
});

test('display rotation notifies layout only when its aspect ratio changes and unsubscribes on stop', async () => {
  const { service, state } = makeHarness();
  const ratios = [];
  service.onPreviewLayoutChanged = ratio => ratios.push(ratio);
  await service.startPreview('back', 'surface');
  assert.equal(service.getPreviewAspectRatio(), 3 / 4);
  assert.deepEqual(ratios, []);

  const changeDisplay = rotation => {
    state.displayRotation = rotation;
    state.displayListeners.forEach(callback => callback());
  };
  changeDisplay(1);
  assert.equal(service.getPreviewAspectRatio(), 4 / 3);
  assert.deepEqual(ratios, [4 / 3]);
  changeDisplay(1);
  changeDisplay(3);
  assert.deepEqual(ratios, [4 / 3], 'same ratio must not trigger another render');
  changeDisplay(0);
  assert.equal(service.getPreviewAspectRatio(), 3 / 4);
  assert.deepEqual(ratios, [4 / 3, 3 / 4]);
  await service.stop();
  assert.equal(state.displayListeners.size, 0);
  changeDisplay(1);
  assert.deepEqual(ratios, [4 / 3, 3 / 4]);
});

test('malformed image rejects capture, frees ownership, and invalidates the session', async () => {
  const { service, state } = makeHarness();
  await service.startPreview('back', 'surface');
  const failed = assert.rejects(service.capture(), /JPEG/);
  const native = nativePhoto([0, 1, 2, 3]);
  state.outputs[0].callbacks.get('photoAvailable')(null, native);
  await failed;
  await flush();
  assert.equal(native.imageReleased, 1);
  assert.equal(native.photoReleased, 1);
  assert.equal(service.getCurrentLensId(), '');
  assert.equal(state.timers.size, 0);
});
