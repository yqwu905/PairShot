// Runs the real controller source with hardware and persistence boundaries mocked.
// No HarmonyOS device or camera behavior is emulated by these coordinator tests.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const toolsHome = process.env.DEVECO_HOME || '/workspace/.harmony-environment/command-line-tools';
const ts = require(path.join(toolsHome, 'codelinter/node_modules/typescript'));
const sources = path.resolve(__dirname, '../entry/src/main/ets');

function transpile(file, dependencies, globals = {}) {
  const sourcePath = path.join(sources, file);
  const result = ts.transpileModule(fs.readFileSync(sourcePath, 'utf8'), {
    fileName: file.replace(/\.ets$/, '.ts'),
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
    reportDiagnostics: true,
  });
  assert.equal((result.diagnostics || []).filter(d => d.category === ts.DiagnosticCategory.Error).length, 0,
    `${file} must transpile without syntax errors`);
  const exports = {};
  vm.runInNewContext(result.outputText, {
    exports,
    require(name) {
      assert.ok(Object.hasOwn(dependencies, name), `Unmocked controller dependency: ${name}`);
      return dependencies[name];
    },
    console, ArrayBuffer, Uint8Array, ...globals,
  }, { filename: sourcePath });
  return exports;
}

const model = transpile('model/Models.ets', {});
const flush = () => new Promise(resolve => setImmediate(resolve));
const plain = value => JSON.parse(JSON.stringify(value));
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

class Clock {
  constructor() { this.now = 1_800_000_000_000; this.nextId = 1; this.timers = new Map(); }
  setTimeout(callback, milliseconds) {
    const id = this.nextId++;
    this.timers.set(id, { at: this.now + milliseconds, callback });
    return id;
  }
  clearTimeout(id) { this.timers.delete(id); }
  async advance(milliseconds) {
    const target = this.now + milliseconds;
    let turns = 0;
    await flush();
    while (true) {
      const due = [...this.timers.entries()].filter(([, timer]) => timer.at <= target)
        .sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      assert.ok(turns++ < 1000, 'Controller timer loop failed to make progress');
      this.now = due[1].at;
      this.timers.delete(due[0]);
      due[1].callback();
      await flush();
    }
    this.now = target;
    await flush();
  }
}

function setup(options = {}) {
  const clock = new Clock();
  const events = [];
  const observedStates = [];
  const permissions = { granted: options.permission !== false };
  const phones = { currentLens: '', zoom: 1, captures: [], stops: 0, captureHook: null, previewHook: null };
  const usb = {
    connected: false, identity: 'camera-A:serial-A', reliable: true, supportsCapture: true,
    handles: [], photos: new Map(), captures: 0, downloads: [], infos: [], lists: 0,
    captureHook: null, listHook: null, infoHook: null, downloadHook: null,
  };
  const store = {
    current: { id: 'session-1', shots: [] }, saved: [], sequence: 0, saveHook: null,
    damagedHandles: new Set(), verified: [],
  };
  const lenses = ['back', 'front', 'tele'].map((id, position) => ({ id, label: id, position }));

  class CameraService {
    listCameras() { return lenses; }
    getCurrentLensId() { return phones.currentLens; }
    getZoomRange() { return [1, phones.currentLens === 'front' ? 2 : 6]; }
    getPreviewAspectRatio() { return 3 / 4; }
    setZoom(value) { phones.zoom = value; events.push(['zoom', phones.currentLens, value]); return value; }
    async startPreview(id, surface) {
      events.push(['preview', id, surface]);
      if (phones.previewHook) await phones.previewHook(id);
      phones.currentLens = id;
    }
    async stop() { phones.stops++; phones.currentLens = ''; events.push(['stop']); }
    async capture() {
      const id = phones.currentLens;
      phones.captures.push(id);
      events.push(['capture', id]);
      if (phones.captureHook) await phones.captureHook(id);
      const photo = new model.CapturedPhoto();
      photo.lensId = id; photo.lensLabel = id; photo.zoom = phones.zoom;
      photo.capturedAt = clock.now; photo.jpeg = new Uint8Array([0xff, 0xd8, 1, 2]).buffer;
      return photo;
    }
  }

  class PtpCamera {
    listDevices() { events.push(['usb-enumerate']); return [{ name: 'usb-1', label: 'Test camera' }]; }
    async connect(name) { events.push(['usb-connect', name]); usb.connected = true; return 'Test camera'; }
    async disconnect() { events.push(['usb-disconnect']); usb.connected = false; }
    isConnected() { return usb.connected; }
    getIdentity() { return usb.identity; }
    hasReliableIdentity() { return usb.reliable; }
    supportsCapture() { return usb.supportsCapture; }
    supportsNikonEvents() { return false; }
    async useLocalShutter() { events.push(['local-shutter']); }
    async capture() {
      usb.captures++; events.push(['usb-capture']);
      if (usb.captureHook) await usb.captureHook();
    }
    async listPhotoHandles() {
      usb.lists++; events.push(['usb-list']);
      if (usb.listHook) await usb.listHook();
      return usb.handles.slice();
    }
    async getPhotoInfo(handle) {
      usb.infos.push(handle);
      if (usb.infoHook) await usb.infoHook(handle);
      assert.ok(usb.photos.has(handle), `Unexpected photo handle ${handle}`);
      return Object.assign(new model.CameraPhoto(), usb.photos.get(handle));
    }
    async pollAddedHandles() { return []; }
    async downloadPhoto(photo, destination) {
      usb.downloads.push({ handle: photo.handle, destination });
      events.push(['download', photo.handle]);
      if (usb.downloadHook) await usb.downloadHook(photo);
    }
  }

  class SessionStore {
    async initialize() {}
    getCurrent() { return store.current; }
    listSessions() { return [{ id: store.current.id, createdAt: clock.now, shotCount: store.current.shots.length }]; }
    createShot(mode, lensIds, identity = '') {
      const shot = new model.ShotRecord();
      shot.id = `shot-${++store.sequence}`; shot.createdAt = clock.now;
      shot.mode = mode; shot.expectedLensIds = lensIds.slice(); shot.cameraIdentity = identity;
      store.current.shots.push(shot); events.push(['shot', shot.id]); return shot;
    }
    save() { store.saved.push(plain(store.current)); if (store.saveHook) store.saveHook(); }
    async addPhonePhoto(shot, photo) {
      const saved = Object.assign(new model.PhonePhoto(), {
        lensId: photo.lensId, lensLabel: photo.lensLabel, zoom: photo.zoom,
        capturedAt: photo.capturedAt, bytes: photo.jpeg.byteLength, sha256: 'a'.repeat(64),
        relativePath: `phone/${shot.id}/${photo.lensId}.jpg`,
      });
      shot.phonePhotos.push(saved); events.push(['save-phone', shot.id, photo.lensId]); this.save();
    }
    getCameraPath(shot, photo) { return `/session/${shot.id}/${photo.handle}_${photo.filename}`; }
    async verifyCameraPhoto(shot, photo) {
      store.verified.push(photo.handle);
      if (store.damagedHandles.has(photo.handle)) throw new Error('Saved original checksum mismatch');
    }
    async commitCameraPhoto(shot, photo) {
      photo.relativePath = `camera/${shot.id}/${photo.handle}_${photo.filename}`;
      photo.sha256 = 'b'.repeat(64); photo.importError = '';
      if (!shot.cameraPhotos.includes(photo)) shot.cameraPhotos.push(photo);
      store.damagedHandles.delete(photo.handle);
      events.push(['save-camera', shot.id, photo.handle]); this.save();
    }
    isComplete(shot) {
      return shot.expectedLensIds.every(id => shot.phonePhotos.some(photo => photo.lensId === id)) &&
        (shot.mode === 'PHONE_ONLY' || (shot.cameraPhotos.length > 0 &&
          shot.cameraPhotos.every(photo => photo.relativePath && !photo.importError)));
    }
    newSession() { store.current = { id: 'session-2', shots: [] }; return store.current; }
    loadSession(id) { assert.equal(id, store.current.id); return store.current; }
    async exportZip(allowIncomplete) {
      events.push(['export', allowIncomplete]);
      if (!allowIncomplete && store.current.shots.some(shot => !this.isComplete(shot))) {
        throw new Error('Cannot export incomplete records');
      }
      return '/export/PairShot.zip';
    }
  }

  class FakeDate extends Date { static now() { return clock.now; } }
  const { PairShotController } = transpile('services/PairShotController.ets', {
    '@kit.AbilityKit': { abilityAccessCtrl: {
      GrantStatus: { PERMISSION_GRANTED: 0 },
      createAtManager: () => ({
        requestPermissionsFromUser: async () => ({ authResults: [permissions.granted ? 0 : -1] }),
        checkAccessToken: async () => permissions.granted ? 0 : -1,
      }),
    } },
    '../model/Models': model,
    './CameraService': { CameraService },
    './PtpCamera': { PtpCamera },
    './SessionStore': { SessionStore },
  }, {
    Date: FakeDate,
    setTimeout: clock.setTimeout.bind(clock), clearTimeout: clock.clearTimeout.bind(clock),
  });
  const controller = new PairShotController({ applicationInfo: { accessTokenId: 1 } }, state => observedStates.push(state));
  const h = {
    controller, clock, events, observedStates, permissions, phones, usb, store,
    async ready() { await controller.initialize(); await controller.attachSurface('surface-1'); },
    async link(mode = 'CAMERA_TO_PHONE') {
      await controller.setMode(mode); await controller.connectUsb('usb-1'); await controller.startLink();
      assert.equal(controller.getState().linked, true, controller.getState().error);
    },
    photo(handle, name, overrides = {}) {
      usb.photos.set(handle, {
        handle, filename: name, size: 1024, storage: 1, parent: 10,
        captureDate: '20261113T225500', ...overrides,
      });
      if (!usb.handles.includes(handle)) usb.handles.push(handle);
    },
  };
  return h;
}

async function fixture(t, options) {
  const h = setup(options);
  t.after(() => h.controller.dispose());
  await h.ready();
  return h;
}

test('phone-only dual lens uses sequential captures and saves each JPEG before switching lenses', async t => {
  const h = await fixture(t);
  await h.controller.selectLens('front');
  h.events.length = 0;
  await h.controller.capture();
  assert.deepEqual(h.phones.captures, ['back', 'front']);
  assert.equal(h.store.current.shots.length, 1);
  const shot = h.store.current.shots[0];
  assert.deepEqual(plain(shot.expectedLensIds), ['back', 'front']);
  assert.deepEqual(plain(shot.phonePhotos.map(photo => photo.lensId)), ['back', 'front']);
  assert.equal(shot.status, 'complete');
  assert.equal(shot.mode, 'PHONE_ONLY');
  assert.equal(shot.cameraIdentity, '');
  assert.equal(h.usb.captures, 0);
  assert.equal(h.usb.lists, 0);
  assert.deepEqual(h.usb.downloads, []);
  assert.equal(h.events.some(event => event[0].startsWith('usb-')), false,
    'Phone capture must not need a USB camera or enumerate it during the shot');
  const firstSave = h.events.findIndex(event => event[0] === 'save-phone' && event[2] === 'back');
  const secondPreview = h.events.findIndex(event => event[0] === 'preview' && event[1] === 'front');
  assert.ok(firstSave >= 0 && firstSave < secondPreview, 'The first JPEG must be durable before opening the next lens');
});

test('failure of second lens preserves the first JPEG and marks its group incomplete', async t => {
  const h = await fixture(t);
  await h.controller.selectLens('front');
  h.phones.captureHook = async id => { if (id === 'front') throw new Error('Second lens unavailable'); };
  await h.controller.capture();
  const shot = h.store.current.shots[0];
  assert.equal(shot.status, 'incomplete');
  assert.deepEqual(plain(shot.phonePhotos.map(photo => photo.lensId)), ['back']);
  assert.match(shot.error, /Second lens unavailable/);
  assert.equal(h.controller.getState().busy, false);
});

test('repeated shutter and configuration changes while busy do not create a second group', async t => {
  const h = await fixture(t);
  const capture = deferred();
  h.phones.captureHook = () => capture.promise;
  const first = h.controller.capture();
  await flush();
  assert.equal(h.controller.getState().busy, true);
  await Promise.all([h.controller.capture(), h.controller.selectLens('front'), h.controller.newSession()]);
  assert.equal(h.store.current.shots.length, 1);
  assert.deepEqual(h.phones.captures, ['back']);
  assert.deepEqual(plain(h.controller.getState().selectedLensIds), ['back']);
  capture.resolve();
  await first;
  assert.equal(h.store.current.shots[0].status, 'complete');
});

test('camera-follow baseline ignores old files and groups new RAW plus JPEG into one delayed phone shot', async t => {
  const h = await fixture(t);
  h.photo(1, 'OLD_0001.NEF'); h.photo(2, 'OLD_0001.JPG');
  await h.link();
  await h.clock.advance(6000);
  assert.equal(h.store.current.shots.length, 0);
  assert.deepEqual(h.usb.infos, [], 'Existing baseline objects must not be re-read or followed');
  h.photo(3, 'DSC_0002.NEF'); h.photo(4, 'DSC_0002.JPG');
  await h.clock.advance(1500);
  assert.equal(h.store.current.shots.length, 0, 'Wait for a quiet group before following it');
  await h.clock.advance(3000);
  assert.equal(h.store.current.shots.length, 1);
  const shot = h.store.current.shots[0];
  assert.equal(shot.mode, 'CAMERA_TO_PHONE');
  assert.deepEqual(plain(shot.cameraPhotos.map(photo => photo.handle)), [3, 4]);
  assert.deepEqual(h.phones.captures, ['back']);
  assert.equal(h.usb.captures, 0, 'Camera-follow must never send a remote shutter command');
  await h.clock.advance(9000);
  assert.equal(h.store.current.shots.length, 1, 'The same RAW/JPEG pair must not trigger twice');
  await h.controller.importPhotos();
  assert.deepEqual(h.usb.downloads.map(photo => photo.handle), [3, 4]);
  assert.equal(shot.status, 'complete');
});

test('late RAW/JPEG companion joins its existing group without another phone exposure', async t => {
  const h = await fixture(t);
  await h.link();
  h.photo(11, 'DSC_0011.NEF');
  await h.clock.advance(4500);
  assert.equal(h.store.current.shots.length, 1);
  h.photo(12, 'DSC_0011.JPG');
  await h.clock.advance(6000);
  assert.equal(h.store.current.shots.length, 1);
  assert.deepEqual(h.phones.captures, ['back']);
  assert.deepEqual(plain(h.store.current.shots[0].cameraPhotos.map(photo => photo.handle)), [11, 12]);
});

test('same filenames in different card folders produce separate camera groups', async t => {
  const h = await fixture(t);
  await h.link();
  h.photo(1, 'DSC_0001.NEF', { parent: 10 });
  h.photo(2, 'DSC_0001.JPG', { parent: 11 });
  await h.clock.advance(4500);
  assert.equal(h.store.current.shots.length, 2);
  assert.deepEqual(h.phones.captures, ['back', 'back']);
});

test('phone-to-camera sends one shutter and rejects another until its RAW/JPEG group is matched', async t => {
  const h = await fixture(t);
  h.photo(1, 'OLD.JPG');
  await h.link('PHONE_TO_CAMERA');
  await h.controller.capture();
  await h.controller.capture();
  assert.equal(h.store.current.shots.length, 1);
  assert.equal(h.usb.captures, 1);
  assert.match(h.controller.getState().error, /上一组/);
  h.photo(2, 'NEW.NEF'); h.photo(3, 'NEW.JPG');
  await h.clock.advance(4500);
  const first = h.store.current.shots[0];
  assert.deepEqual(plain(first.cameraPhotos.map(photo => photo.handle)), [2, 3]);
  assert.equal(first.status, 'incomplete', 'Originals are not yet imported');
  await h.controller.capture();
  assert.equal(h.store.current.shots.length, 2);
  assert.equal(h.usb.captures, 2);
});

test('remote shutter failure is never retried automatically', async t => {
  const h = await fixture(t);
  await h.link('PHONE_TO_CAMERA');
  h.usb.captureHook = async () => { throw new Error('PTP capture timeout'); };
  await h.controller.capture();
  await h.clock.advance(46000);
  assert.equal(h.usb.captures, 1);
  assert.equal(h.store.current.shots.length, 1);
  assert.equal(h.store.current.shots[0].phonePhotos.length, 1);
  assert.equal(h.store.current.shots[0].status, 'incomplete');
  assert.equal(h.controller.getState().linked, false);
});

test('timed out linkage stops and a late file enters the next baseline, not the next shot', async t => {
  const h = await fixture(t);
  await h.link('PHONE_TO_CAMERA');
  await h.controller.capture();
  await h.clock.advance(45000);
  assert.equal(h.controller.getState().linked, false);
  assert.match(h.store.current.shots[0].error, /45|超时/);
  h.photo(9, 'LATE.JPG');
  await h.controller.startLink();
  await h.controller.capture();
  await h.clock.advance(6000);
  assert.equal(h.store.current.shots.length, 2);
  assert.equal(h.store.current.shots[1].cameraPhotos.length, 0);
  assert.equal(h.usb.infos.includes(9), false);
});

test('backgrounding between dual-lens exposures saves the completed photo without firing the next lens', async t => {
  const h = await fixture(t);
  await h.controller.selectLens('front');
  const firstPhoto = deferred();
  h.phones.captureHook = () => firstPhoto.promise;
  const capture = h.controller.capture();
  await flush();
  await h.controller.suspend();
  firstPhoto.resolve();
  await capture;
  assert.deepEqual(h.phones.captures, ['back']);
  assert.equal(h.store.current.shots[0].phonePhotos.length, 1);
  assert.equal(h.store.current.shots[0].status, 'incomplete');
  assert.ok(h.phones.stops > 0);
  assert.equal(h.usb.connected, false);
  await h.clock.advance(60000);
  assert.deepEqual(h.phones.captures, ['back']);
});

test('stopping an in-flight camera scan discards its metadata and never schedules a follow shot', async t => {
  const h = await fixture(t);
  await h.link();
  h.photo(1, 'NEW.JPG');
  const metadata = deferred();
  h.usb.infoHook = () => metadata.promise;
  await h.clock.advance(1500);
  assert.deepEqual(h.usb.infos, [1]);
  await h.controller.stopLink();
  metadata.resolve();
  await flush();
  await h.clock.advance(60000);
  assert.equal(h.store.current.shots.length, 0);
  assert.deepEqual(h.phones.captures, []);
  assert.equal(h.controller.getState().linked, false);
});

test('backgrounding a pending linkage prevents following new photos and resume does not rearm it', async t => {
  const h = await fixture(t);
  await h.link();
  h.photo(1, 'NEW.JPG');
  await h.clock.advance(1500);
  await h.controller.suspend();
  await h.clock.advance(60000);
  assert.equal(h.store.current.shots.length, 0);
  assert.equal(h.controller.getState().linked, false);
  await h.controller.resume();
  await h.clock.advance(60000);
  assert.equal(h.store.current.shots.length, 0);
  assert.equal(h.usb.connected, false);
});

test('import rejects an old file handle when a different camera is connected', async t => {
  const h = await fixture(t);
  await h.link();
  h.photo(7, 'NEW.NEF');
  await h.clock.advance(4500);
  const shot = h.store.current.shots[0];
  await h.controller.disconnectUsb();
  h.usb.identity = 'camera-B:serial-B';
  await h.controller.connectUsb('usb-1');
  await h.controller.importPhotos();
  assert.equal(h.usb.downloads.length, 0);
  assert.match(shot.cameraPhotos[0].importError, /身份/);
  assert.equal(shot.status, 'incomplete');
});

test('serial-less cameras may import in the original connection but never after reconnecting', async t => {
  const h = await fixture(t);
  h.usb.reliable = false;
  await h.link();
  h.photo(7, 'NEW.NEF');
  await h.clock.advance(4500);
  await h.controller.importPhotos();
  assert.deepEqual(h.usb.downloads.map(photo => photo.handle), [7]);
  assert.equal(h.store.current.shots[0].status, 'complete');
  await h.controller.startLink();
  h.photo(8, 'NEXT.NEF');
  await h.clock.advance(4500);
  await h.controller.disconnectUsb();
  await h.controller.connectUsb('usb-1');
  await h.controller.importPhotos();
  assert.deepEqual(h.usb.downloads.map(photo => photo.handle), [7]);
  assert.match(h.store.current.shots[1].cameraPhotos[0].importError, /序列号/);
});

test('camera permission denial creates no shot and never opens a preview', async t => {
  const h = await fixture(t, { permission: false });
  await h.controller.capture();
  assert.equal(h.store.current.shots.length, 0);
  assert.deepEqual(h.phones.captures, []);
  assert.equal(h.events.some(event => event[0] === 'preview'), false);
  assert.match(h.controller.getState().error, /授权/);
});

test('stop-link while baseline enumeration is in flight cannot rearm linkage when it completes', async t => {
  const h = await fixture(t);
  await h.controller.setMode('CAMERA_TO_PHONE');
  await h.controller.connectUsb('usb-1');
  const baseline = deferred();
  h.usb.listHook = () => baseline.promise;
  const starting = h.controller.startLink();
  await flush();
  await h.controller.stopLink();
  baseline.resolve();
  await starting;
  assert.equal(h.controller.getState().linked, false, 'An explicit stop must invalidate the pending baseline');
  assert.equal(h.clock.timers.size, 0);
});

test('a photo already being enumerated before a software shutter is never paired to that new request', async t => {
  const h = await fixture(t);
  await h.link('PHONE_TO_CAMERA');
  h.photo(20, 'BEFORE_SOFTWARE_SHUTTER.JPG');
  const metadata = deferred();
  h.usb.infoHook = () => metadata.promise;
  await h.clock.advance(1500);
  const capture = h.controller.capture();
  await flush();
  metadata.resolve();
  await capture;
  await flush();
  await h.clock.advance(4500);
  assert.equal(h.store.current.shots.some(shot => shot.cameraPhotos.some(photo => photo.handle === 20)), false,
    'Preexisting metadata must not be consumed by a capture request that started during the scan');
});

test('retrying a partial import downloads only failed originals and never fires either shutter', async t => {
  const h = await fixture(t);
  await h.link();
  h.photo(1, 'FRAME.NEF'); h.photo(2, 'FRAME.JPG');
  await h.clock.advance(4500);
  h.usb.downloadHook = async photo => {
    if (photo.handle === 2) throw new Error('USB transfer interrupted');
  };
  await h.controller.importPhotos();
  const shot = h.store.current.shots[0];
  assert.equal(shot.status, 'incomplete');
  assert.equal(shot.cameraPhotos[0].importError, '');
  assert.match(shot.cameraPhotos[1].importError, /interrupted/);
  const exposures = h.phones.captures.length;
  h.usb.downloadHook = null;
  await h.controller.importPhotos();
  assert.deepEqual(h.usb.downloads.map(photo => photo.handle), [1, 2, 2]);
  assert.equal(shot.status, 'complete');
  assert.equal(h.phones.captures.length, exposures);
  assert.equal(h.usb.captures, 0);
});

test('unsupported remote capture blocks phone-to-camera but still permits physical-camera follow mode', async t => {
  const h = await fixture(t);
  h.usb.supportsCapture = false;
  await h.controller.setMode('PHONE_TO_CAMERA');
  await h.controller.connectUsb('usb-1');
  await h.controller.startLink();
  assert.equal(h.controller.getState().linked, false);
  assert.match(h.controller.getState().error, /远程快门/);
  assert.equal(h.usb.captures, 0);
  await h.controller.setMode('CAMERA_TO_PHONE');
  await h.controller.startLink();
  assert.equal(h.controller.getState().linked, true);
  await h.controller.capture();
  assert.equal(h.store.current.shots.length, 0, 'The app shutter cannot fake a physical-camera exposure');
  assert.equal(h.usb.captures, 0);
  h.photo(1, 'PHYSICAL_SHUTTER.JPG');
  await h.clock.advance(4500);
  assert.equal(h.store.current.shots.length, 1);
  assert.deepEqual(h.phones.captures, ['back']);
});

test('an existing original is verified and only a missing or damaged original is downloaded again', async t => {
  const h = await fixture(t);
  await h.link();
  h.photo(1, 'FRAME.NEF'); h.photo(2, 'FRAME.JPG');
  await h.clock.advance(4500);
  await h.controller.importPhotos();
  h.store.damagedHandles.add(1);
  await h.controller.importPhotos();
  assert.deepEqual(h.store.verified, [1, 2]);
  assert.deepEqual(h.usb.downloads.map(photo => photo.handle), [1, 2, 1]);
  assert.equal(h.store.current.shots[0].status, 'complete');
  assert.equal(h.store.damagedHandles.has(1), false);
});

test('import is rejected while a software shutter is waiting for its matching camera files', async t => {
  const h = await fixture(t);
  await h.link('PHONE_TO_CAMERA');
  await h.controller.capture();
  await h.controller.importPhotos();
  assert.match(h.controller.getState().error, /上一组/);
  assert.equal(h.controller.getState().linked, true, 'Refusing import must preserve the pending match');
  assert.equal(h.usb.downloads.length, 0);
  h.photo(1, 'EXPECTED.JPG');
  await h.clock.advance(4500);
  await h.controller.importPhotos();
  assert.deepEqual(h.usb.downloads.map(photo => photo.handle), [1]);
  assert.equal(h.store.current.shots[0].status, 'complete');
});
