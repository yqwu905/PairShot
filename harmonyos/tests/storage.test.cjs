// Host-side behavioral tests. SDK APIs are mocked; these do not claim HarmonyOS device validation.
const { test, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');
const toolsHome = process.env.DEVECO_HOME || '/workspace/.harmony-environment/command-line-tools';
const ts = require(process.env.PAIRSHOT_TYPESCRIPT || path.join(toolsHome, 'codelinter/node_modules/typescript'));

const work = path.resolve(__dirname, '../../../work/storage-tests');
fs.mkdirSync(work, { recursive: true });
const root = fs.mkdtempSync(path.join(work, 'run-'));
let fixtureNumber = 0;
let selectedUri = '';
let rejectManifestRename = false;
let pretendLargePath = '';
const sdkFs = {
  OpenMode: { READ_ONLY: fs.constants.O_RDONLY, WRITE_ONLY: fs.constants.O_WRONLY, CREATE: fs.constants.O_CREAT, TRUNC: fs.constants.O_TRUNC },
  accessSync: p => fs.existsSync(p),
  mkdirSync: (p, recursive) => fs.mkdirSync(p, { recursive: !!recursive }),
  openSync: (p, flags) => ({ fd: fs.openSync(p, flags) }),
  closeSync: f => fs.closeSync(typeof f === 'number' ? f : f.fd),
  writeSync: (fd, data) => {
    const buffer = Buffer.from(data);
    return fs.writeSync(fd, buffer, 0, Math.min(buffer.length, 4093));
  },
  write: async (fd, data) => {
    const buffer = Buffer.from(data);
    return fs.writeSync(fd, buffer, 0, Math.min(buffer.length, 4093));
  },
  read: async (fd, data, options) => fs.readSync(fd, new Uint8Array(data), 0, options?.length ?? data.byteLength, null),
  fsyncSync: fd => fs.fsyncSync(fd),
  renameSync: (a, b) => {
    if (rejectManifestRename && b.endsWith('/session.json')) throw new Error('injected rename failure');
    fs.renameSync(a, b);
  },
  readTextSync: p => fs.readFileSync(p, 'utf8'),
  listFileSync: p => fs.readdirSync(p),
  unlinkSync: p => fs.unlinkSync(p),
  statSync: p => {
    const st = typeof p === 'number' ? fs.fstatSync(p) : fs.statSync(p);
    if (p === pretendLargePath) return { size: 0xFFFFFFFF, isFile: () => true };
    return st;
  },
  copyFile: async (source, dest) => {
    if (typeof dest === 'number') fs.writeFileSync(dest, fs.readFileSync(source));
    else fs.copyFileSync(source, dest);
  },
};
const core = {
  fileIo: sdkFs,
  hash: { hash: async (p, algorithm) => crypto.createHash(algorithm).update(fs.readFileSync(p)).digest('hex') },
  picker: {
    DocumentSaveOptions: class {},
    DocumentViewPicker: class { async save() { return selectedUri ? [selectedUri] : []; } },
  },
};
const modules = new Map();
function load(file) {
  if (modules.has(file)) return modules.get(file);
  const compiled = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2021, module: ts.ModuleKind.CommonJS },
    fileName: path.basename(file).replace(/\.ets$/, '.ts'),
  });
  const module = { exports: {} };
  modules.set(file, module.exports);
  const localRequire = name => {
    if (name === '@kit.CoreFileKit') return core;
    if (name === '@kit.ArkTS') return { util: { TextEncoder: class { encodeInto(s) { return new TextEncoder().encode(s); } } } };
    if (name === '@kit.AbilityKit') return {};
    if (name.startsWith('.')) return load(path.resolve(path.dirname(file), name + '.ets'));
    throw new Error(`Unexpected SDK import: ${name}`);
  };
  new Function('require', 'module', 'exports', compiled.outputText)(localRequire, module, module.exports);
  return module.exports;
}
const sourceDir = path.resolve(__dirname, '../entry/src/main/ets');
const { SessionStore } = load(path.join(sourceDir, 'services/SessionStore.ets'));
const { CapturedPhoto, CameraPhoto } = load(path.join(sourceDir, 'model/Models.ets'));

async function fixture() {
  const folder = path.join(root, String(++fixtureNumber));
  const context = { filesDir: path.join(folder, 'files'), cacheDir: path.join(folder, 'cache') };
  fs.mkdirSync(context.filesDir, { recursive: true });
  fs.mkdirSync(context.cacheDir, { recursive: true });
  const store = new SessionStore(context);
  await store.initialize();
  selectedUri = path.join(folder, 'export.zip');
  fs.writeFileSync(selectedUri, '');
  return { store, context, folder };
}
function photo(lensId = '0') {
  const result = new CapturedPhoto();
  result.lensId = lensId;
  result.lensLabel = '测试镜头';
  result.capturedAt = Date.now();
  const bytes = crypto.randomBytes(80017);
  result.jpeg = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
  return result;
}
function zipContents(zip) {
  const result = spawnSync('python3', ['-c',
    'import zipfile,json,sys,hashlib; z=zipfile.ZipFile(sys.argv[1]); assert z.testzip() is None; print(json.dumps({"names":z.namelist(),"manifest":json.loads(z.read("manifest.json")),"hashes":{n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist()}}))', zip], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  return JSON.parse(result.stdout);
}

test('streaming ZIP survives short writes and preserves photo bytes, root layout and metadata', async () => {
  const { store } = await fixture();
  const shot = store.createShot('PHONE_ONLY', ['front/0']);
  const captured = photo('front/0');
  await store.addPhonePhoto(shot, captured);
  assert.equal(store.isComplete(shot), true);
  const uri = await store.exportZip(false);
  const result = zipContents(uri);
  assert.deepEqual(result.names, ['manifest.json', `phone/${shot.id}/front%2F0.jpg`]);
  const expected = crypto.createHash('sha256').update(Buffer.from(captured.jpeg)).digest('hex');
  assert.equal(result.hashes[result.names[1]], expected);
  assert.equal(result.manifest.shots[0].phonePhotos[0].lensLabel, '测试镜头');
  assert.equal(result.manifest.shots[0].status, 'complete');
});

test('restart retains partial dual-lens photos and requires explicit incomplete export', async () => {
  const { store, context } = await fixture();
  const shot = store.createShot('PHONE_ONLY', ['0', '1']);
  await store.addPhonePhoto(shot, photo('0'));
  const restored = new SessionStore(context);
  await restored.initialize();
  const saved = restored.getCurrent().shots[0];
  assert.equal(saved.status, 'incomplete');
  assert.equal(saved.phonePhotos.length, 1);
  assert.equal(restored.isComplete(saved), false);
  await assert.rejects(restored.exportZip(false), /不完整/);
  const result = zipContents(await restored.exportZip(true));
  assert.deepEqual(result.manifest.shots[0].expectedLensIds, ['0', '1']);
  assert.equal(result.manifest.shots[0].status, 'incomplete');
});

test('linked shots need imported camera originals; size mismatch cannot be committed', async () => {
  const { store } = await fixture();
  const shot = store.createShot('PHONE_TO_CAMERA', ['0'], 'camera-sha256');
  await store.addPhonePhoto(shot, photo());
  const camera = new CameraPhoto();
  camera.handle = 123;
  camera.filename = '../../NEF原片.NEF';
  camera.size = 3;
  shot.cameraPhotos.push(camera);
  store.save();
  const dest = store.getCameraPath(shot, camera);
  assert.ok(dest.includes(`/camera/${shot.id}/7b_`));
  assert.equal(path.basename(dest).includes('/'), false);
  fs.writeFileSync(dest, Buffer.from([1, 2]));
  await assert.rejects(store.commitCameraPhoto(shot, camera), /大小不匹配/);
  assert.equal(camera.relativePath, '');
  assert.equal(store.isComplete(shot), false);
  fs.writeFileSync(dest, Buffer.from([1, 2, 3]));
  await store.commitCameraPhoto(shot, camera);
  assert.equal(store.isComplete(shot), true);
  const result = zipContents(await store.exportZip(false));
  assert.ok(result.names.some(n => n.startsWith(`camera/${shot.id}/`)));
});

test('tampered bytes are rejected even when incomplete export is allowed', async () => {
  const { store, context } = await fixture();
  const shot = store.createShot('PHONE_ONLY', ['0']);
  await store.addPhonePhoto(shot, photo());
  const p = path.join(context.filesDir, 'pairshot', store.getCurrent().id, shot.phonePhotos[0].relativePath);
  const data = fs.readFileSync(p);
  data[1] ^= 0xFF;
  fs.writeFileSync(p, data);
  await assert.rejects(store.exportZip(true), /摘要校验失败/);
  assert.equal(fs.readFileSync(selectedUri).length, 0);
});

test('previous camera imports verify against original digest and reject damage or foreign records', async () => {
  const { store } = await fixture();
  const shot = store.createShot('PHONE_TO_CAMERA', ['0'], 'camera-sha256');
  const camera = new CameraPhoto();
  camera.handle = 987;
  camera.filename = 'DSC_0001.NEF';
  camera.size = 3;
  shot.cameraPhotos.push(camera);
  const dest = store.getCameraPath(shot, camera);
  fs.writeFileSync(dest, Buffer.from([1, 2, 3]));
  await store.commitCameraPhoto(shot, camera);
  const originalMetadata = JSON.stringify(camera);
  await store.verifyCameraPhoto(shot, camera);
  assert.equal(JSON.stringify(camera), originalMetadata);
  fs.writeFileSync(dest, Buffer.from([1, 2, 4]));
  await assert.rejects(store.verifyCameraPhoto(shot, camera), /摘要校验失败/);
  assert.equal(JSON.stringify(camera), originalMetadata, 'verification must not trust damaged bytes by replacing the digest');
  fs.unlinkSync(dest);
  await assert.rejects(store.verifyCameraPhoto(shot, camera), /文件缺失/);
  const wrongRecord = new CameraPhoto();
  Object.assign(wrongRecord, camera);
  await assert.rejects(store.verifyCameraPhoto(shot, wrongRecord), /不属于/);
  const originalPath = camera.relativePath;
  camera.relativePath = '../../outside.NEF';
  await assert.rejects(store.verifyCameraPhoto(shot, camera), /路径无效/);
  camera.relativePath = originalPath;
  // A fresh download followed by commit repairs the file and keeps the trusted original digest.
  fs.writeFileSync(dest, Buffer.from([1, 2, 3]));
  await store.commitCameraPhoto(shot, camera);
  await store.verifyCameraPhoto(shot, camera);
  store.newSession();
  await assert.rejects(store.verifyCameraPhoto(shot, camera), /不属于当前批次/);
});

test('missing persisted photos are identified in incomplete manifest and excluded from ZIP', async () => {
  const { store, context } = await fixture();
  const shot = store.createShot('PHONE_ONLY', ['0']);
  await store.addPhonePhoto(shot, photo());
  const p = path.join(context.filesDir, 'pairshot', store.getCurrent().id, shot.phonePhotos[0].relativePath);
  fs.unlinkSync(p);
  await assert.rejects(store.exportZip(false), /文件缺失/);
  const result = zipContents(await store.exportZip(true));
  assert.deepEqual(result.names, ['manifest.json']);
  assert.equal(result.manifest.shots[0].status, 'incomplete');
  assert.match(result.manifest.shots[0].error, /文件缺失/);
});

test('traversal in persisted paths and session identifiers is rejected', async () => {
  const { store, context } = await fixture();
  const shot = store.createShot('PHONE_ONLY', ['0']);
  await store.addPhonePhoto(shot, photo());
  const id = store.getCurrent().id;
  const manifest = path.join(context.filesDir, 'pairshot', id, 'session.json');
  const corrupted = JSON.parse(fs.readFileSync(manifest, 'utf8'));
  corrupted.shots[0].phonePhotos[0].relativePath = '../../outside.jpg';
  fs.writeFileSync(manifest, JSON.stringify(corrupted));
  assert.throws(() => store.loadSession(id), /路径无效/);
  assert.throws(() => store.loadSession('../outside'), /标识无效/);
});

test('interrupted manifest replacement keeps prior persisted session readable', async () => {
  const { store, context } = await fixture();
  const id = store.getCurrent().id;
  rejectManifestRename = true;
  try {
    assert.throws(() => store.createShot('PHONE_ONLY', ['0']), /injected rename failure/);
  } finally {
    rejectManifestRename = false;
  }
  const restored = new SessionStore(context);
  await restored.initialize();
  assert.equal(restored.getCurrent().id, id);
  assert.equal(restored.getCurrent().shots.length, 0);
});

test('4 GiB classic ZIP overflow is rejected before writing a misleading archive', async () => {
  const { store, context } = await fixture();
  const shot = store.createShot('PHONE_ONLY', ['0']);
  await store.addPhonePhoto(shot, photo());
  const p = path.join(context.filesDir, 'pairshot', store.getCurrent().id, shot.phonePhotos[0].relativePath);
  shot.phonePhotos[0].bytes = 0xFFFFFFFF;
  pretendLargePath = p;
  try {
    await assert.rejects(store.exportZip(false), /4 GiB/);
    assert.equal(fs.readFileSync(selectedUri).length, 0);
  } finally {
    pretendLargePath = '';
  }
});

test('picker cancellation leaves source photos intact and permits another operation', async () => {
  const { store, context } = await fixture();
  const shot = store.createShot('PHONE_ONLY', ['0']);
  await store.addPhonePhoto(shot, photo());
  selectedUri = '';
  await assert.rejects(store.exportZip(false), /取消导出/);
  const p = path.join(context.filesDir, 'pairshot', store.getCurrent().id, shot.phonePhotos[0].relativePath);
  assert.equal(fs.existsSync(p), true);
  assert.equal(fs.readdirSync(context.cacheDir).length, 0);
  assert.doesNotThrow(() => store.newSession());
});

after(() => fs.rmSync(root, { recursive: true, force: true }));
