// Service tests run the actual ArkTS module against a protocol-speaking fake USB device.
// They do not claim device/OS compatibility. Run: node --test harmonyos/tests/ptp-camera.test.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const crypto = require('node:crypto');
const { test } = require('node:test');
const toolsHome = process.env.DEVECO_HOME || '/workspace/.harmony-environment/command-line-tools';
const compiler = require(path.join(toolsHome, 'codelinter/node_modules/typescript'));

function u16(value) { const bytes = Buffer.alloc(2); bytes.writeUInt16LE(value); return bytes; }
function u32(value) { const bytes = Buffer.alloc(4); bytes.writeUInt32LE(value); return bytes; }
function string(value) {
  const encoded = Buffer.from(value, 'utf16le');
  return Buffer.concat([Buffer.from([encoded.length / 2 + 1]), encoded, Buffer.alloc(2)]);
}
function array16(values) { return Buffer.concat([u32(values.length), ...values.map(u16)]); }
function container(type, code, transaction, payload = Buffer.alloc(0)) {
  return Buffer.concat([u32(payload.length + 12), u16(type), u16(code), u32(transaction), payload]);
}
function deviceData(fake) {
  return Buffer.concat([u16(100), u32(fake.vendor), u16(100), string(''), u16(0),
    array16(fake.operations), array16([0x4002]), array16([]), array16([]), array16([]),
    string(fake.manufacturer), string(fake.model), string('1.00'), string(fake.serial)]);
}
function objectData(photo) {
  return Buffer.concat([u32(photo.storage), u16(0xb101), u16(0), u32(photo.size), Buffer.alloc(26),
    u32(photo.parent), Buffer.alloc(10), string(photo.filename), string(photo.captureDate), string(''), string('')]);
}
function usbDevice(name = 'camera-1') {
  return {
    name, manufacturerName: 'Nikon', productName: 'Test camera', busNum: 1, devAddress: 2,
    configs: [{ id: 1, interfaces: [{ id: 0, clazz: 6, subClass: 1, protocol: 1,
      endpoints: [{ address: 0x81, type: 2 }, { address: 0x02, type: 2 }] }] }],
  };
}

class FakeUsb {
  constructor() {
    this.vendor = 10;
    this.manufacturer = 'Nikon Corporation';
    this.model = 'Z6';
    this.serial = 'FIXTURE-SERIAL';
    this.operations = [0x1001, 0x1002, 0x1003, 0x1007, 0x1008, 0x1009, 0x100e];
    this.photoBytes = Buffer.from(Array.from({ length: 257 }, (_, index) => index & 0xff));
    this.photo = { handle: 42, filename: 'DSC_0042.NEF', size: this.photoBytes.length,
      storage: 0x10001, parent: 1, captureDate: '20261113T222000' };
    this.devices = [usbDevice()];
    this.hasPermission = true;
    this.grantsPermission = true;
    this.permissionRequests = 0;
    this.connectedPipes = 0;
    this.closedPipes = 0;
    this.claims = 0;
    this.releases = 0;
    this.commands = [];
    this.pending = Buffer.alloc(0);
    this.chunkSizes = [1, 2, 9, 5, 64];
    this.readIndex = 0;
    this.onCommand = () => null;
    this.onReadReady = async () => {};
    this.failNextRead = false;
    this.usbManager = {
      getDevices: () => this.devices,
      hasRight: () => this.hasPermission,
      requestRight: async () => { this.permissionRequests++; return this.grantsPermission; },
      connectDevice: () => { this.connectedPipes++; return { busNum: 1, devAddress: 2 }; },
      setConfiguration: () => 0,
      claimInterface: () => { this.claims++; return 0; },
      releaseInterface: () => { this.releases++; return 0; },
      closePipe: () => { this.closedPipes++; this.pending = Buffer.alloc(0); return 0; },
      bulkTransfer: async (pipe, endpoint, bytes) => {
        await new Promise(resolve => setImmediate(resolve));
        if ((endpoint.address & 0x80) === 0) {
          assert.equal(this.pending.length, 0, 'New command must not interrupt an outstanding response');
          const wire = Buffer.from(bytes);
          assert.equal(wire.readUInt16LE(4), 1);
          assert.equal(wire.readUInt32LE(0), wire.length);
          const command = { code: wire.readUInt16LE(6), transaction: wire.readUInt32LE(8), parameters: [] };
          for (let index = 12; index < wire.length; index += 4) command.parameters.push(wire.readUInt32LE(index));
          this.commands.push(command);
          const custom = this.onCommand(command, this);
          let data;
          let response = 0x2001;
          let transaction = command.transaction;
          if (custom) {
            data = custom.data;
            response = custom.response ?? response;
            transaction = custom.transaction ?? transaction;
          } else if (command.code === 0x1001) {
            data = deviceData(this);
          } else if (command.code === 0x1007) {
            data = Buffer.concat([u32(1), u32(this.photo.handle)]);
          } else if (command.code === 0x1008) {
            data = objectData(this.photo);
          } else if (command.code === 0x1009) {
            data = this.photoBytes;
          } else if (command.code === 0x90c7) {
            data = Buffer.concat([u16(1), u16(0x4002), u32(42)]);
          }
          const reply = container(3, response, transaction);
          this.pending = data === undefined ? reply : Buffer.concat([container(2, command.code, transaction, data), reply]);
          return bytes.length;
        }
        if (this.failNextRead) {
          this.failNextRead = false;
          throw new Error('Fixture USB disconnected during read');
        }
        assert.ok(this.pending.length > 0, 'Unexpected USB read');
        const count = Math.min(bytes.length, this.pending.length, this.chunkSizes[this.readIndex++ % this.chunkSizes.length]);
        bytes.set(this.pending.subarray(0, count));
        this.pending = this.pending.subarray(count);
        await this.onReadReady(this.commands[this.commands.length - 1]);
        return count;
      },
    };
  }
}

class MemoryFiles {
  constructor() {
    this.files = new Map();
    this.handles = new Map();
    this.nextFd = 10;
    this.writeLimit = Infinity;
    this.failWrites = false;
    this.statDelta = 0;
    this.closed = 0;
    this.fileIo = {
      OpenMode: { CREATE: 1, READ_WRITE: 2, TRUNC: 4 },
      openSync: pathname => {
        const fd = this.nextFd++;
        this.files.set(pathname, Buffer.alloc(0));
        this.handles.set(fd, pathname);
        return { fd };
      },
      writeSync: (fd, arrayBuffer) => {
        if (this.failWrites) throw new Error('Fixture disk full');
        const pathname = this.handles.get(fd);
        assert.ok(pathname);
        const bytes = Buffer.from(arrayBuffer);
        const length = Math.min(bytes.length, this.writeLimit);
        this.files.set(pathname, Buffer.concat([this.files.get(pathname), bytes.subarray(0, length)]));
        return length;
      },
      fsyncSync: fd => assert.ok(this.handles.has(fd)),
      statSync: fd => ({ size: this.files.get(this.handles.get(fd)).length + this.statDelta }),
      closeSync: file => { assert.ok(this.handles.delete(file.fd)); this.closed++; },
      renameSync: (from, to) => { assert.ok(this.files.has(from)); this.files.set(to, this.files.get(from)); this.files.delete(from); },
      accessSync: pathname => this.files.has(pathname),
      unlinkSync: pathname => this.files.delete(pathname),
    };
  }
}

function environment() {
  const usb = new FakeUsb();
  const files = new MemoryFiles();
  const kits = {
    '@kit.BasicServicesKit': { usbManager: usb.usbManager },
    '@kit.CoreFileKit': { fileIo: files.fileIo },
    '@kit.CryptoArchitectureKit': { cryptoFramework: { createMd: () => {
      const hash = crypto.createHash('sha256');
      return { update: async ({ data }) => { hash.update(data); }, digest: async () => ({ data: new Uint8Array(hash.digest()) }) };
    } } },
    '@kit.ArkTS': { util: { TextEncoder: class { encodeInto(value) { return new TextEncoder().encode(value); } } } },
  };
  const cache = new Map();
  function compile(sourcePath) {
    if (cache.has(sourcePath)) return cache.get(sourcePath).exports;
    const source = fs.readFileSync(sourcePath, 'utf8');
    const result = compiler.transpileModule(source, { fileName: sourcePath.replace(/\.ets$/, '.ts'),
      compilerOptions: { module: compiler.ModuleKind.CommonJS, target: compiler.ScriptTarget.ES2020 }, reportDiagnostics: true });
    assert.equal((result.diagnostics || []).filter(d => d.category === compiler.DiagnosticCategory.Error).length, 0);
    const loaded = new Module(sourcePath, module);
    loaded.filename = sourcePath;
    loaded.require = request => kits[request] || (request.startsWith('.') ? compile(path.resolve(path.dirname(sourcePath), `${request}.ets`)) : require(request));
    cache.set(sourcePath, loaded);
    loaded._compile(result.outputText, sourcePath);
    return loaded.exports;
  }
  const { PtpCamera } = compile(path.resolve(__dirname, '../entry/src/main/ets/services/PtpCamera.ets'));
  return { camera: new PtpCamera(), usb, files };
}

test('USB discovery requires still-image PTP and both bulk endpoints; rejected authorization opens nothing', async () => {
  const { camera, usb } = environment();
  const other = usbDevice('not-ptp');
  other.configs[0].interfaces[0].clazz = 8;
  const missingOutput = usbDevice('missing-output');
  missingOutput.configs[0].interfaces[0].endpoints.pop();
  usb.devices.push(other, missingOutput);
  assert.deepEqual(camera.listDevices().map(item => item.name), ['camera-1']);
  usb.hasPermission = false;
  usb.grantsPermission = false;
  await assert.rejects(camera.connect('camera-1'));
  assert.equal(usb.permissionRequests, 1);
  assert.equal(usb.connectedPipes, 0);
  assert.equal(usb.commands.length, 0);
  assert.equal(camera.isConnected(), false);
});

test('parallel service calls execute complete transactions serially and identity is hashed', async () => {
  const { camera, usb } = environment();
  await camera.connect('camera-1');
  assert.equal(camera.isConnected(), true);
  assert.equal(camera.supportsCapture(), true);
  assert.equal(camera.hasReliableIdentity(), true);
  assert.equal(camera.getIdentity(), crypto.createHash('sha256').update('10|Nikon Corporation|Z6|FIXTURE-SERIAL').digest('hex'));
  const results = await Promise.all([camera.listPhotoHandles(), camera.getPhotoInfo(42), camera.capture()]);
  assert.deepEqual(results[0], [42]);
  assert.equal(results[1].filename, usb.photo.filename);
  assert.deepEqual(usb.commands.map(command => command.code), [0x1001, 0x1002, 0x1007, 0x1008, 0x100e]);
  assert.deepEqual(usb.commands.map(command => command.transaction), [0, 0, 1, 2, 3]);
  await camera.disconnect();
  assert.equal(usb.closedPipes, 1);
  assert.equal(usb.releases, 1);
  assert.equal(camera.getIdentity(), '');
});

test('a busy remote shutter is sent exactly once and the next user operation can still proceed', async () => {
  const { camera, usb } = environment();
  usb.onCommand = command => command.code === 0x100e ? { response: 0x2019 } : null;
  await camera.connect('camera-1');
  await assert.rejects(camera.capture(), error => error.responseCode === 0x2019);
  assert.equal(usb.commands.filter(command => command.code === 0x100e).length, 1);
  assert.deepEqual(await camera.listPhotoHandles(), [42]);
  assert.equal(camera.isConnected(), true);
  await camera.disconnect();
});

test('stale OpenSession recovers by one CloseSession and one retry, then stops if still stale', async () => {
  for (const permanentlyStale of [false, true]) {
    const { camera, usb } = environment();
    let opens = 0;
    usb.onCommand = command => {
      if (command.code === 0x1002 && (++opens === 1 || permanentlyStale)) return { response: 0x201e };
      if (command.code === 0x1003) return { response: 0x2003 };
      return null;
    };
    if (permanentlyStale) await assert.rejects(camera.connect('camera-1'), error => error.responseCode === 0x201e);
    else await camera.connect('camera-1');
    assert.deepEqual(usb.commands.map(command => command.code), [0x1001, 0x1002, 0x1003, 0x1002]);
    assert.deepEqual(usb.commands.map(command => command.transaction), [0, 0, 1, 0]);
    assert.equal(opens, 2);
    assert.equal(camera.isConnected(), !permanentlyStale);
    assert.equal(usb.closedPipes, permanentlyStale ? 1 : 0);
    if (!permanentlyStale) await assert.rejects(camera.disconnect());
  }
});

test('first-generation Z6 uses Nikon focus-preserving storage shutter and restores local control', async () => {
  const { camera, usb } = environment();
  usb.operations.push(0x90c2, 0x90c8, 0x9207);
  await camera.connect('camera-1');
  await camera.capture();
  await camera.useLocalShutter();
  await camera.capture();
  await camera.disconnect();
  const commands = usb.commands.slice(2);
  assert.deepEqual(commands.map(command => command.code), [0x90c2, 0x90c8, 0x9207, 0x90c2,
    0x90c2, 0x90c8, 0x9207, 0x90c2, 0x1003]);
  assert.deepEqual(commands[0].parameters, [1]);
  assert.deepEqual(commands[2].parameters, [0xffffffff, 0]);
  assert.deepEqual(commands[3].parameters, [0]);
  assert.deepEqual(commands[7].parameters, [0]);
  assert.equal(usb.closedPipes, 1);
});

test('Nikon busy readiness prevents shutter entirely and failed shutter is never resent', async () => {
  for (const failCode of [0x90c8, 0x9207]) {
    const { camera, usb } = environment();
    usb.operations.push(0x90c2, 0x90c8, 0x9207);
    usb.onCommand = command => command.code === failCode ? { response: 0x2019 } : null;
    await camera.connect('camera-1');
    await assert.rejects(camera.capture(), error => error.responseCode === 0x2019);
    assert.equal(usb.commands.filter(command => command.code === 0x9207).length, failCode === 0x90c8 ? 0 : 1);
    await camera.disconnect();
    assert.deepEqual(usb.commands.slice(-2).map(command => [command.code, command.parameters]), [[0x90c2, [0]], [0x1003, []]]);
  }
});

test('changed photo metadata prevents GetObject and leaves the existing connection usable', async () => {
  for (const key of ['filename', 'size', 'storage', 'parent', 'captureDate']) {
    const { camera, usb, files } = environment();
    await camera.connect('camera-1');
    const photo = await camera.getPhotoInfo(42);
    usb.photo[key] = typeof usb.photo[key] === 'number' ? usb.photo[key] + 1 : `${usb.photo[key]}changed`;
    await assert.rejects(camera.downloadPhoto(photo, '/fixture/photo.nef'));
    assert.equal(usb.commands.some(command => command.code === 0x1009), false, key);
    assert.equal(files.files.size, 0);
    assert.equal(camera.isConnected(), true);
    await camera.disconnect();
  }
});

test('photo downloads handle partial disk writes and rename only a complete verified stream', async () => {
  const { camera, usb, files } = environment();
  files.writeLimit = 3;
  await camera.connect('camera-1');
  const photo = await camera.getPhotoInfo(42);
  await camera.downloadPhoto(photo, '/fixture/photo.nef');
  assert.deepEqual(files.files.get('/fixture/photo.nef'), usb.photoBytes);
  assert.equal(files.files.has('/fixture/photo.nef.part'), false);
  assert.equal(files.handles.size, 0);
  assert.equal(files.closed, 1);
  assert.equal(camera.isConnected(), true);
  await camera.disconnect();
});

test('incorrect wire size, disk failure, and stat mismatch discard partial files and release USB', async () => {
  for (const failure of ['wire-size', 'disk', 'stat']) {
    const { camera, usb, files } = environment();
    if (failure === 'wire-size') usb.onCommand = command => command.code === 0x1009 ? { data: Buffer.from([1]) } : null;
    if (failure === 'disk') files.failWrites = true;
    if (failure === 'stat') files.statDelta = 1;
    await camera.connect('camera-1');
    const photo = await camera.getPhotoInfo(42);
    await assert.rejects(camera.downloadPhoto(photo, '/fixture/photo.nef'));
    assert.equal(files.files.size, 0, failure);
    assert.equal(files.handles.size, 0, failure);
    assert.equal(camera.isConnected(), false, failure);
    assert.equal(usb.closedPipes, 1, failure);
    assert.equal(usb.releases, 1, failure);
    await assert.rejects(camera.listPhotoHandles());
  }
});

test('USB and transaction failures invalidate the pipe; failed Nikon events disable only event polling', async () => {
  const broken = environment();
  await broken.camera.connect('camera-1');
  broken.usb.failNextRead = true;
  await assert.rejects(broken.camera.capture());
  assert.equal(broken.camera.isConnected(), false);
  assert.equal(broken.usb.commands.filter(command => command.code === 0x100e).length, 1);
  assert.equal(broken.usb.closedPipes, 1);

  const mismatch = environment();
  await mismatch.camera.connect('camera-1');
  mismatch.usb.onCommand = command => command.code === 0x1007 ? { data: Buffer.concat([u32(0)]), transaction: 999 } : null;
  await assert.rejects(mismatch.camera.listPhotoHandles());
  assert.equal(mismatch.camera.isConnected(), false);
  assert.equal(mismatch.usb.closedPipes, 1);

  const events = environment();
  events.usb.operations.push(0x90c7);
  events.usb.onCommand = command => command.code === 0x90c7 ? { response: 0x2005 } : null;
  await events.camera.connect('camera-1');
  assert.equal(events.camera.supportsNikonEvents(), true);
  assert.deepEqual(await events.camera.pollAddedHandles(), []);
  assert.equal(events.camera.supportsNikonEvents(), false);
  assert.deepEqual(await events.camera.pollAddedHandles(), []);
  assert.equal(events.usb.commands.filter(command => command.code === 0x90c7).length, 1);
  assert.deepEqual(await events.camera.listPhotoHandles(), [42]);
  await events.camera.disconnect();
});

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((accept, fail) => { resolve = accept; reject = fail; });
  return { promise, resolve, reject };
}

test('abort during pending USB authorization ignores a later grant and permits a fresh connect', async () => {
  const { camera, usb } = environment();
  const requested = deferred();
  const grant = deferred();
  usb.hasPermission = false;
  usb.usbManager.requestRight = async () => {
    usb.permissionRequests++;
    requested.resolve();
    return grant.promise;
  };
  const connecting = camera.connect('camera-1');
  const cancelled = assert.rejects(connecting);
  await requested.promise;
  camera.abort();
  grant.resolve(true);
  await cancelled;
  assert.equal(usb.connectedPipes, 0);
  assert.equal(usb.commands.length, 0);
  assert.equal(camera.isConnected(), false);
  usb.hasPermission = true;
  await camera.connect('camera-1');
  assert.equal(camera.isConnected(), true);
  assert.equal(usb.connectedPipes, 1);
  assert.equal(usb.closedPipes, 0);
  await camera.disconnect();
});

test('abort rejects pending and queued operations before their commands and cannot close a reconnect', async () => {
  const { camera, usb } = environment();
  await camera.connect('camera-1');
  const reading = deferred();
  const lateRead = deferred();
  let blocked = false;
  usb.onReadReady = async command => {
    if (command.code === 0x1007 && !blocked) {
      blocked = true;
      reading.resolve();
      await lateRead.promise;
    }
  };
  const running = camera.listPhotoHandles();
  const runningRejected = assert.rejects(running);
  await reading.promise;
  const queuedCapture = assert.rejects(camera.capture());
  const queuedInfo = assert.rejects(camera.getPhotoInfo(42));
  camera.abort();
  assert.equal(usb.closedPipes, 1, 'abort must close the pipe before the read resolves');
  assert.equal(camera.isConnected(), false);
  const reconnecting = camera.connect('camera-1');
  lateRead.resolve();
  await Promise.all([runningRejected, queuedCapture, queuedInfo]);
  await reconnecting;
  assert.equal(camera.isConnected(), true);
  assert.equal(usb.closedPipes, 1, 'old operation cleanup must not close the new pipe');
  assert.equal(usb.connectedPipes, 2);
  assert.deepEqual(usb.commands.map(command => command.code), [0x1001, 0x1002, 0x1007, 0x1001, 0x1002]);
  await camera.capture();
  assert.equal(usb.commands.filter(command => command.code === 0x100e).length, 1);
  await camera.disconnect();
  assert.equal(usb.closedPipes, 2);
});

test('abort during a photo transfer closes immediately and late data cannot save or retain a partial file', async () => {
  const { camera, usb, files } = environment();
  await camera.connect('camera-1');
  const photo = await camera.getPhotoInfo(42);
  const reading = deferred();
  const lateRead = deferred();
  let blocked = false;
  usb.onReadReady = async command => {
    const partial = files.files.get('/fixture/photo.nef.part');
    if (command.code === 0x1009 && partial && partial.length > 0 && !blocked) {
      blocked = true;
      reading.resolve();
      await lateRead.promise;
    }
  };
  const downloading = camera.downloadPhoto(photo, '/fixture/photo.nef');
  const cancelled = assert.rejects(downloading);
  await reading.promise;
  assert.ok(files.files.get('/fixture/photo.nef.part').length > 0);
  camera.abort();
  assert.equal(usb.closedPipes, 1);
  assert.equal(camera.isConnected(), false);
  const reconnecting = camera.connect('camera-1');
  lateRead.resolve();
  await cancelled;
  await reconnecting;
  assert.equal(files.files.has('/fixture/photo.nef'), false);
  assert.equal(files.files.has('/fixture/photo.nef.part'), false);
  assert.equal(files.handles.size, 0);
  assert.equal(camera.isConnected(), true);
  assert.equal(usb.closedPipes, 1);
  await camera.disconnect();
});
