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

function makeHarness(options = {}) {
  const state = { inputs: [], outputs: [], sessions: [], order: [], timers: new Map(), nextTimer: 0,
    openGate: undefined, openHook: null, openCalls: [], captureErrors: new Map(),
    displayRotation: 0, displayListeners: new Set(), rejectPhotoFor: '' };
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
    getCameraConcurrentInfos: () => [],
    createCameraInput(device) {
      const input = emitter({ closed: 0, device,
        async open(mode) {
          state.order.push(`open:${device.cameraId}`); state.openCalls.push({ id: device.cameraId, mode });
          await state.openGate;
          if (state.openHook) await state.openHook(device.cameraId, mode);
        },
        async close() { input.closed++; state.order.push(`close:${device.cameraId}`); },
      });
      state.inputs.push(input);
      return input;
    },
    createPreviewOutput: (profile, surfaceId) => emitter({
      profile, surfaceId, kind: 'preview',
      async release() {}, getPreviewRotation: degrees => ((90 - degrees) % 360 + 360) % 360,
      setPreviewRotation() {},
      getActiveProfile: () => previewProfile,
    }),
    createPhotoOutput(profile) {
      const id = state.inputs.at(-1).device.cameraId;
      const output = emitter({ captures: 0, released: 0, id, profile, kind: 'photo',
        async capture() {
          output.captures++; state.order.push(`capture:${id}`);
          if (state.captureErrors.has(id)) throw state.captureErrors.get(id);
        },
        async release() { output.released++; }, getPhotoRotation: () => 90,
      });
      state.outputs.push(output);
      return output;
    },
    createSession() {
      let zoom = 1;
      const id = state.inputs.at(-1).device.cameraId;
      const session = emitter({
        beginConfig() {}, canAddInput: () => true, addInput() {},
        canAddOutput: output => !(output.kind === 'photo' && state.rejectPhotoFor === id),
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
    CameraConcurrentType: { CAMERA_LIMITED_CAPABILITY: 0, CAMERA_FULL_CAPABILITY: 1 },
    ConnectionType: { CAMERA_CONNECTION_BUILT_IN: 0 },
    CameraType: { CAMERA_TYPE_DEFAULT: 0, CAMERA_TYPE_WIDE_ANGLE: 1, CAMERA_TYPE_ULTRA_WIDE: 2,
      CAMERA_TYPE_TELEPHOTO: 3, CAMERA_TYPE_TRUE_DEPTH: 4 },
    FocusMode: { FOCUS_MODE_CONTINUOUS_AUTO: 1 }, ExposureMode: { EXPOSURE_MODE_CONTINUOUS_AUTO: 2 },
    QualityLevel: { QUALITY_LEVEL_HIGH: 0 }, ImageRotation: { ROTATION_90: 90, ROTATION_270: 270 },
  };
  const native = {
    '@kit.CameraKit': { camera }, '@kit.ImageKit': { image: { ComponentType: { JPEG: 4 } } },
    '@kit.ArkUI': { display: {
      getDefaultDisplaySync: () => ({ rotation: state.displayRotation }),
      on(type, callback) { if (type === 'change') state.displayListeners.add(callback); },
      off(type, callback) { if (type === 'change') state.displayListeners.delete(callback); },
    } },
    '../model/Models': models,
  };
  const injected = {
    setTimeout(fn) { const id = ++state.nextTimer; state.timers.set(id, fn); return id; },
    clearTimeout(id) { state.timers.delete(id); },
  };
  const channels = loadSource('services/CameraChannel.ets', native, injected);
  const catalog = loadSource('services/CameraCatalog.ets', native, injected);
  const exports = loadSource('services/CameraService.ets', {
    ...native, './CameraChannel': channels, './CameraCatalog': catalog,
  }, injected);
  if (options.configure) options.configure(manager, devices, photoProfile, previewProfile);
  return { service: new exports.CameraService({}), state, manager, devices, photoProfile, previewProfile, camera };
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
  for (let index = 0; index < 32; index++) await Promise.resolve();
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

function concurrentHarness() {
  const h = makeHarness();
  h.devices.push({ cameraId: 'tele', cameraPosition: 1, cameraType: 3 });
  h.devices.push({ cameraId: 'ultra', cameraPosition: 1, cameraType: 2 });
  const caps = {
    previewProfiles: [h.previewProfile],
    photoProfiles: [{ format: 2000, size: { width: 1600, height: 1200 } }],
  };
  h.manager.getCameraConcurrentInfos = devices => devices.map(device => ({
    device, type: 0, modes: [2, 1],
    outputCapabilities: [{ previewProfiles: [], photoProfiles: [] }, caps],
  }));
  return { ...h, concurrentCaps: caps };
}

test('extended and constituent queries discover physical lenses absent from the basic list and preserve native identity', async () => {
  const tele = { cameraId: 'rear-tele', cameraPosition: 1, cameraType: 3 };
  const ultra = { cameraId: 'rear-ultra', cameraPosition: 1, cameraType: 2 };
  const h = makeHarness({ configure(manager, devices) {
    devices[0].constituentCameraDevices = [tele, ultra];
    manager.getCameraDevices = function(position, types, connection) {
      assert.equal(this, manager, 'The native method must retain its receiver');
      assert.equal(connection, 0);
      assert.ok(types.includes(3));
      return position === 1 ? [devices[0], tele, ultra] : [devices[1]];
    };
  } });
  const lenses = h.service.listCameras();
  assert.deepEqual(lenses.map(lens => lens.id), ['back', 'rear-tele', 'rear-ultra', 'front']);
  assert.equal(lenses.find(lens => lens.id === 'rear-tele').physical, true);
  assert.equal(lenses.find(lens => lens.id === 'rear-tele').parentId, 'back');
  assert.equal(lenses.find(lens => lens.id === 'back').logical, true);
  await h.service.startPreview('rear-tele', 'tele-surface');
  assert.equal(h.state.inputs[0].device, tele,
    'Opening an extra lens must use its returned CameraDevice, not search only the basic list');
  assert.match(h.service.getDiagnosticReport(), /无 JPEG|未开放 JPEG/);
  await h.service.stop();
});

test('API 18 type queries discover an extra lens when the newer list API is absent', async () => {
  const tele = { cameraId: 'type-tele', cameraPosition: 1, cameraType: 3 };
  const h = makeHarness({ configure(manager) {
    manager.getCameraDevice = (position, type) => position === 1 && type === 3 ? tele : undefined;
  } });
  assert.ok(h.service.listCameras().some(lens => lens.id === 'type-tele'));
  assert.match(h.service.getDiagnosticReport(), /运行系统未提供/);
  await h.service.startPreview('type-tele', 'surface');
  assert.equal(h.state.inputs[0].device, tele);
  await h.service.stop();
});

test('one failed extended query or capability probe does not hide the remaining usable cameras', () => {
  const h = makeHarness({ configure(manager, devices) {
    manager.getCameraDevices = () => { throw new Error('Extended query unavailable'); };
    manager.getSupportedSceneModes = device => {
      if (device.cameraId === 'video-only') throw new Error('Unsupported scene query');
      return [1];
    };
    devices[0].constituentCameraDevices = [devices[0]];
  } });
  assert.deepEqual(h.service.listCameras().map(lens => lens.id), ['back', 'front']);
  assert.match(h.service.getDiagnosticReport(), /Extended query unavailable/);
  assert.match(h.service.getDiagnosticReport(), /Unsupported scene query/);
});

test('concurrent setup uses the matching photo-mode capabilities and returned open mode for every camera', async () => {
  const h = concurrentHarness();
  await h.service.startConcurrent(['back', 'tele', 'ultra'], ['s-back', 's-tele', 's-ultra']);
  assert.equal(h.service.isConcurrentReady(['back', 'tele', 'ultra']), true);
  assert.deepEqual(h.state.openCalls, [{ id: 'back', mode: 0 }, { id: 'tele', mode: 0 }, { id: 'ultra', mode: 0 }]);
  assert.ok(h.state.outputs.every(output => output.profile === h.concurrentCaps.photoProfiles[0]));
  assert.equal(h.state.inputs.filter(input => input.closed === 0).length, 3,
    'Earlier inputs must remain open when the next physical camera starts');
  await h.service.stop();
  assert.ok(h.state.inputs.every(input => input.closed === 1));
});

test('missing concurrent support, preview-only support, and mismatched device IDs are rejected before opening', async () => {
  for (const variant of ['none', 'preview-only', 'wrong-id']) {
    const h = concurrentHarness();
    if (variant === 'none') h.manager.getCameraConcurrentInfos = () => [];
    if (variant === 'preview-only') {
      h.manager.getCameraConcurrentInfos = devices => devices.map(device => ({
        device, type: 0, modes: [1], outputCapabilities: [{ previewProfiles: [h.previewProfile], photoProfiles: [] }],
      }));
    }
    if (variant === 'wrong-id') {
      h.manager.getCameraConcurrentInfos = () => ['other-a', 'other-b'].map(cameraId => ({
        device: { cameraId }, type: 0, modes: [1], outputCapabilities: [h.concurrentCaps],
      }));
    }
    await assert.rejects(h.service.startConcurrent(['back', 'tele'], ['a', 'b']), /并发|不一致/);
    assert.equal(h.state.inputs.length, 0, variant);
    await assert.rejects(h.service.captureConcurrent(async () => {}), /未发出快门/);
  }
});

test('two native shutters are submitted before the first JPEG and each result retains its own lens and timestamp', async () => {
  const h = concurrentHarness();
  await h.service.startConcurrent(['back', 'tele'], ['a', 'b']);
  const saved = [];
  const capture = h.service.captureConcurrent(async photo => { saved.push(photo); });
  assert.deepEqual(h.state.outputs.map(output => output.captures), [1, 1]);
  assert.equal(saved.length, 0, 'No JPEG callback has happened yet');
  h.state.outputs[1].callbacks.get('frameShutter')(null, { captureId: 22, timestamp: 123456.7 });
  h.state.outputs[1].callbacks.get('photoAvailable')(null, nativePhoto([0xff, 0xd8, 22, 0xff, 0xd9]));
  await flush();
  assert.deepEqual(saved.map(photo => photo.lensId), ['tele']);
  h.state.outputs[0].callbacks.get('photoAvailable')(null, nativePhoto());
  assert.deepEqual(await capture, []);
  assert.equal(saved.find(photo => photo.lensId === 'tele').shutterTimestampMs, 123456.7);
  assert.ok(saved.every(photo => photo.captureMode === 'CONCURRENT'));
  await h.service.stop();
});

test('a third physical camera is also armed before any JPEG is received', async () => {
  const h = concurrentHarness();
  await h.service.startConcurrent(['back', 'tele', 'ultra'], ['a', 'b', 'c']);
  const photos = [];
  const pending = h.service.captureConcurrent(async photo => { photos.push(photo); });
  assert.deepEqual(h.state.outputs.map(output => output.captures), [1, 1, 1]);
  for (const output of [...h.state.outputs].reverse()) output.callbacks.get('photoAvailable')(null, nativePhoto());
  assert.deepEqual(await pending, []);
  assert.deepEqual(new Set(photos.map(photo => photo.lensId)), new Set(['back', 'tele', 'ultra']));
  await h.service.stop();
});

test('a failed JPEG preserves already delivered photos, closes the group, and never retries either shutter', async () => {
  const h = concurrentHarness();
  await h.service.startConcurrent(['back', 'tele'], ['a', 'b']);
  const saved = [];
  const pending = h.service.captureConcurrent(async photo => { saved.push(photo.lensId); });
  h.state.outputs[0].callbacks.get('photoAvailable')(null, nativePhoto());
  await flush();
  h.state.outputs[1].callbacks.get('error')({ code: 7400201, message: 'tele failed' });
  const failures = await pending;
  assert.deepEqual(saved, ['back']);
  assert.ok(failures.some(message => /tele failed/.test(message)));
  assert.deepEqual(h.state.outputs.map(output => output.captures), [1, 1]);
  assert.equal(h.service.isConcurrentReady(['back', 'tele']), false);
  assert.ok(h.state.inputs.every(input => input.closed === 1));
});

test('background cancellation closes every concurrent input and discards late JPEGs without satisfying another shot', async () => {
  const h = concurrentHarness();
  await h.service.startConcurrent(['back', 'tele'], ['a', 'b']);
  const staleCallbacks = h.state.outputs.map(output => output.callbacks.get('photoAvailable'));
  const saved = [];
  const capture = h.service.captureConcurrent(async photo => { saved.push(photo.lensId); });
  await h.service.stop();
  assert.equal((await capture).length, 2);
  const late = staleCallbacks.map(callback => { const photo = nativePhoto(); callback(null, photo); return photo; });
  await flush();
  assert.deepEqual(saved, []);
  assert.ok(late.every(photo => photo.photoReleased === 1));
  assert.equal(h.state.timers.size, 0);
  assert.ok(h.state.inputs.every(input => input.closed === 1));
});

test('cancellation during the first concurrent open prevents opening or firing later lenses', async () => {
  const h = concurrentHarness();
  let allowOpen;
  h.state.openGate = new Promise(resolve => { allowOpen = resolve; });
  const starting = assert.rejects(h.service.startConcurrent(['back', 'tele'], ['a', 'b']), /取消/);
  await flush();
  const stopping = h.service.stop();
  allowOpen();
  await starting;
  await stopping;
  assert.deepEqual(h.state.openCalls.map(call => call.id), ['back']);
  assert.equal(h.state.outputs.length, 0);
});

test('an advertised profile rejected at session configuration releases both inputs before any photo request', async () => {
  const h = concurrentHarness();
  h.state.rejectPhotoFor = 'tele';
  await assert.rejects(h.service.startConcurrent(['back', 'tele'], ['a', 'b']), /JPEG/);
  assert.ok(h.state.inputs.every(input => input.closed === 1));
  assert.ok(h.state.outputs.every(output => output.captures === 0));
  assert.match(h.service.getDiagnosticReport(), /打开镜头失败/);
});

test('duplicate surfaces are rejected and a failed file save cannot discard the other concurrent result', async () => {
  const h = concurrentHarness();
  await assert.rejects(h.service.startConcurrent(['back', 'tele'], ['a', 'a']), /独立/);
  assert.equal(h.state.inputs.length, 0);
  await h.service.startConcurrent(['back', 'tele'], ['a', 'b']);
  const saved = [];
  const pending = h.service.captureConcurrent(async photo => {
    if (photo.lensId === 'back') throw new Error('Disk full');
    saved.push(photo.lensId);
  });
  h.state.outputs.forEach(output => output.callbacks.get('photoAvailable')(null, nativePhoto()));
  const failures = await pending;
  assert.deepEqual(saved, ['tele']);
  assert.equal(failures.length, 1);
  assert.match(failures[0], /Disk full/);
  assert.deepEqual(h.state.outputs.map(output => output.captures), [1, 1]);
});

test('a repeated group shutter is rejected without cancelling or duplicating the original requests', async () => {
  const h = concurrentHarness();
  await h.service.startConcurrent(['back', 'tele'], ['a', 'b']);
  const saved = [];
  const first = h.service.captureConcurrent(async photo => { saved.push(photo.lensId); });
  await assert.rejects(h.service.captureConcurrent(async () => {}), /未重复发出快门/);
  assert.deepEqual(h.state.outputs.map(output => output.captures), [1, 1]);
  h.state.outputs.forEach(output => output.callbacks.get('photoAvailable')(null, nativePhoto()));
  assert.deepEqual(await first, []);
  assert.deepEqual(new Set(saved), new Set(['back', 'tele']));
  await h.service.stop();
});

test('a late persistence failure from an old group cannot close newly opened camera channels', async () => {
  const h = concurrentHarness();
  await h.service.startConcurrent(['back', 'tele'], ['a', 'b']);
  let finishSave;
  const gate = new Promise(resolve => { finishSave = resolve; });
  const first = h.service.captureConcurrent(async photo => {
    if (photo.lensId === 'back') { await gate; throw new Error('Old write failed'); }
  });
  h.state.outputs.forEach(output => output.callbacks.get('photoAvailable')(null, nativePhoto()));
  await flush();
  await h.service.stop();
  await h.service.startConcurrent(['back', 'tele'], ['new-a', 'new-b']);
  finishSave();
  assert.match((await first)[0], /Old write failed/);
  assert.equal(h.service.isConcurrentReady(['back', 'tele']), true,
    'Cleanup from the older capture must be scoped to its own generation');
  assert.ok(h.state.inputs.slice(-2).every(input => input.closed === 0));
  await h.service.stop();
});
