// Runs the actual platform-independent ArkTS protocol codec with Node's test runner.
// Usage: node --test harmonyos/tests/ptp.test.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const { test } = require('node:test');

function loadCodec() {
  const toolsHome = process.env.DEVECO_HOME || '/workspace/.harmony-environment/command-line-tools';
  const compiler = require(path.join(toolsHome, 'codelinter/node_modules/typescript'));
  const sourcePath = path.resolve(__dirname, '../entry/src/main/ets/services/PtpCodec.ets');
  const source = fs.readFileSync(sourcePath, 'utf8');
  const compiled = compiler.transpileModule(source, {
    fileName: 'PtpCodec.ts',
    compilerOptions: { module: compiler.ModuleKind.CommonJS, target: compiler.ScriptTarget.ES2020 },
    reportDiagnostics: true,
  });
  const errors = (compiled.diagnostics || []).filter(d => d.category === compiler.DiagnosticCategory.Error);
  assert.deepEqual(errors, [], 'Protocol source must transpile without syntax errors');
  const loaded = new Module(sourcePath, module);
  loaded.filename = sourcePath;
  loaded.paths = Module._nodeModulePaths(path.dirname(sourcePath));
  loaded._compile(compiled.outputText, sourcePath);
  return loaded.exports;
}

const { PtpCodec, DatasetReader, PtpInput, PtpResponseError } = loadCodec();

function u16(value) {
  const bytes = Buffer.alloc(2);
  bytes.writeUInt16LE(value);
  return bytes;
}

function u32(value) {
  const bytes = Buffer.alloc(4);
  bytes.writeUInt32LE(value);
  return bytes;
}

function string(value) {
  const encoded = Buffer.from(value, 'utf16le');
  return Buffer.concat([Buffer.from([encoded.length / 2 + 1]), encoded, Buffer.alloc(2)]);
}

function array16(values) {
  return Buffer.concat([u32(values.length), ...values.map(u16)]);
}

function deviceInfo({ vendor = 10, manufacturer = 'Nikon Corporation', model = 'NIKON Z 6',
  operations = [0x1001, 0x1002, 0x1007, 0x1008, 0x1009, 0x9207], events = [0x4002],
  serial = 'TEST-SERIAL' } = {}) {
  return Buffer.concat([
    u16(100), u32(vendor), u16(100), string('Nikon'), u16(0),
    array16(operations), array16(events), array16([]), array16([]), array16([]),
    string(manufacturer), string(model), string('1.00'), string(serial),
  ]);
}

function objectInfo({ filename = 'DSC_0001.NEF', size = 42_000_000, storage = 0x10001,
  format = 0xb101, parent = 14, date = '20261113T221500' } = {}) {
  return Buffer.concat([
    u32(storage), u16(format), u16(0), u32(size), Buffer.alloc(26), u32(parent), Buffer.alloc(10),
    string(filename), string(date), string(date), string(''),
  ]);
}

function container(type = 3, code = 0x2001, transaction = 7, payload = Buffer.alloc(0)) {
  return Buffer.concat([u32(12 + payload.length), u16(type), u16(code), u32(transaction), payload]);
}

test('command encodes little-endian header and all five unsigned parameters', () => {
  const actual = Buffer.from(PtpCodec.command(0x9207, 0xffffffff, [0xffffffff, 0, 1, 0x12345678, 9]));
  assert.equal(actual.toString('hex'),
    '2000000001000792ffffffffffffffff00000000010000007856341209000000');
  assert.equal(PtpCodec.command(0x1001, 0, []).length, 12);
});

test('commands reject too many parameters and invalid unsigned values', () => {
  assert.throws(() => PtpCodec.command(0x1001, 1, [0, 0, 0, 0, 0, 0]));
  for (const invalid of [-1, 0x100000000, 1.25, NaN, Infinity]) {
    assert.throws(() => PtpCodec.command(0x1001, invalid, []), `transaction ${invalid}`);
    assert.throws(() => PtpCodec.command(0x1001, 1, [invalid]), `parameter ${invalid}`);
  }
  for (const invalid of [-1, 0x10000, 1.25, NaN, Infinity]) {
    assert.throws(() => PtpCodec.command(invalid, 1, []), `operation ${invalid}`);
  }
});

test('header preserves unsigned 32-bit length and transaction', () => {
  const bytes = Buffer.concat([u32(0xffffffff), u16(2), u16(0x1009), u32(0xfffffffe)]);
  const header = PtpCodec.header(bytes);
  assert.equal(header.size, 0xffffffff);
  assert.equal(header.type, 2);
  assert.equal(header.code, 0x1009);
  assert.equal(header.transaction, 0xfffffffe);
});

test('header rejects short, below-minimum, and unknown-type containers', () => {
  for (let length = 0; length < 12; length++) {
    assert.throws(() => PtpCodec.header(new Uint8Array(length)));
  }
  const tooShort = container();
  tooShort.writeUInt32LE(11, 0);
  assert.throws(() => PtpCodec.header(tooShort));
  assert.throws(() => PtpCodec.header(container(0)));
  assert.throws(() => PtpCodec.header(container(5)));
});

test('decode validates exact payload length and accepts a typed-array view', () => {
  const encoded = container(2, 0x1001, 9, Buffer.from([4, 5, 6]));
  const padded = Buffer.concat([Buffer.from([0xaa, 0xbb]), encoded, Buffer.from([0xcc])]);
  const actual = PtpCodec.decode(padded.subarray(2, padded.length - 1));
  assert.equal(actual.type, 2);
  assert.equal(actual.code, 0x1001);
  assert.equal(actual.transaction, 9);
  assert.deepEqual(Array.from(actual.payload), [4, 5, 6]);
  assert.throws(() => PtpCodec.decode(encoded.subarray(0, encoded.length - 1)));
  assert.throws(() => PtpCodec.decode(Buffer.concat([encoded, Buffer.from([0])])));
});

test('ordinary container decoding rejects oversized data while streaming headers remain valid', () => {
  const bytes = Buffer.alloc(16 * 1024 * 1024 + 1);
  bytes.writeUInt32LE(bytes.length, 0);
  bytes.writeUInt16LE(2, 4);
  bytes.writeUInt16LE(0x1009, 6);
  bytes.writeUInt32LE(1, 8);
  assert.equal(PtpCodec.header(bytes).size, bytes.length);
  assert.throws(() => PtpCodec.decode(bytes));
});

test('dataset reader rejects truncated integers, arrays, and negative skip', () => {
  assert.throws(() => new DatasetReader(new Uint8Array(1)).u16());
  assert.throws(() => new DatasetReader(new Uint8Array(3)).u32());
  assert.throws(() => new DatasetReader(u32(0xffffffff)).u16Array());
  assert.throws(() => new DatasetReader(u32(2)).u32Array());
  assert.throws(() => new DatasetReader(new Uint8Array(2)).skip(3));
  assert.throws(() => new DatasetReader(new Uint8Array(2)).skip(-1));
  assert.deepEqual(new DatasetReader(Buffer.concat([u32(2), u32(0xffffffff), u32(8)])).u32Array(),
    [0xffffffff, 8]);
});

test('PTP strings handle zero count, UTF-16 text, and reject a missing terminator', () => {
  assert.equal(new DatasetReader(Buffer.from([0])).string(), '');
  assert.equal(new DatasetReader(string('')).string(), '');
  assert.equal(new DatasetReader(string('照片 📷')).string(), '照片 📷');
  assert.throws(() => new DatasetReader(Buffer.from([2, 65, 0])).string());
  assert.throws(() => new DatasetReader(Buffer.from([2, 65, 0, 66, 0])).string());
});

test('device dataset preserves operation support, vendor and identity input', () => {
  const data = PtpCodec.deviceInfo(deviceInfo());
  assert.equal(data.manufacturer, 'Nikon Corporation');
  assert.equal(data.model, 'NIKON Z 6');
  assert.equal(data.version, '1.00');
  assert.equal(data.serial, 'TEST-SERIAL');
  assert.equal(data.vendor, 10);
  assert.equal(data.operations.includes(0x9207), true);
  assert.deepEqual(data.events, [0x4002]);
  const encoded = deviceInfo();
  assert.throws(() => PtpCodec.deviceInfo(encoded.subarray(0, encoded.length - 1)));
});

test('Nikon capture selection requires exact first-generation Z6, Nikon vendor, and opcode', () => {
  assert.equal(PtpCodec.captureMode(PtpCodec.deviceInfo(deviceInfo())), 'NIKON_Z6');
  assert.equal(PtpCodec.captureMode(PtpCodec.deviceInfo(deviceInfo({ model: 'Z6' }))), 'NIKON_Z6');
  for (const model of ['NIKON Z 6II', 'NIKON Z 6III', 'Z8', 'D850']) {
    assert.equal(PtpCodec.captureMode(PtpCodec.deviceInfo(deviceInfo({ model }))), 'UNSUPPORTED');
  }
  assert.equal(PtpCodec.captureMode(PtpCodec.deviceInfo(deviceInfo({ vendor: 11 }))), 'UNSUPPORTED');
  assert.equal(PtpCodec.captureMode(PtpCodec.deviceInfo(deviceInfo({ operations: [0x100e] }))), 'STANDARD');
  assert.equal(PtpCodec.captureMode(PtpCodec.deviceInfo(deviceInfo({ operations: [] }))), 'UNSUPPORTED');
  assert.equal(PtpCodec.captureMode(PtpCodec.deviceInfo(deviceInfo({ model: 'Z6 II', operations: [0x100e, 0x9207] }))), 'STANDARD');
});

test('object metadata retains large RAW file size, grouping fields, and capture date', () => {
  const data = PtpCodec.objectInfo(1234, objectInfo());
  assert.equal(data.handle, 1234);
  assert.equal(data.storage, 0x10001);
  assert.equal(data.parent, 14);
  assert.equal(data.filename, 'DSC_0001.NEF');
  assert.equal(data.size, 42_000_000);
  assert.equal(data.captureDate, '20261113T221500');
  assert.equal(data.isPhoto(), true);
  assert.equal(PtpCodec.objectInfo(7, objectInfo({ size: 0xffffffff })).size, 0xffffffff);
});

test('only photo file extensions qualify and directory associations never qualify', () => {
  for (const filename of ['frame.jpg', 'FRAME.JPEG', 'frame.NEF', 'frame.dng', 'frame.heic', 'frame.CR3', 'frame.arw']) {
    assert.equal(PtpCodec.objectInfo(1, objectInfo({ filename })).isPhoto(), true, filename);
  }
  for (const filename of ['clip.mov', 'metadata.xmp', 'no-extension']) {
    assert.equal(PtpCodec.objectInfo(1, objectInfo({ filename })).isPhoto(), false, filename);
  }
  assert.equal(PtpCodec.objectInfo(1, objectInfo({ format: 0x3001, filename: 'folder.jpg' })).isPhoto(), false);
  const encoded = objectInfo();
  assert.throws(() => PtpCodec.objectInfo(1, encoded.subarray(0, encoded.length - 1)));
});

test('Nikon event decoder returns only ObjectAdded handles and validates declared count', () => {
  const encoded = Buffer.concat([u16(3), u16(0x4002), u32(42), u16(0x400d), u32(99), u16(0x4002), u32(0xffffffff)]);
  assert.deepEqual(PtpCodec.addedHandles(encoded), [42, 0xffffffff]);
  assert.deepEqual(PtpCodec.addedHandles(u16(0)), []);
  assert.throws(() => PtpCodec.addedHandles(new Uint8Array(1)));
  assert.throws(() => PtpCodec.addedHandles(encoded.subarray(0, encoded.length - 1)));
  assert.throws(() => PtpCodec.addedHandles(Buffer.concat([encoded, Buffer.from([0])])));
  assert.throws(() => PtpCodec.addedHandles(u16(65535)));
  assert.deepEqual(PtpCodec.addedHandles(Buffer.concat([u16(1), u16(0x4002), u32(0)])), []);
});

function readerFor(chunks) {
  let position = 0;
  return new PtpInput(async () => {
    assert.ok(position < chunks.length, 'Unexpected USB read after fixture exhaustion');
    return chunks[position++];
  });
}

test('USB reader reassembles headers split at every byte boundary', async () => {
  const bytes = container(2, 0x1001, 27, Buffer.from([10, 20, 30, 40]));
  for (let boundary = 1; boundary < bytes.length; boundary++) {
    const reader = readerFor([bytes.subarray(0, boundary), bytes.subarray(boundary)]);
    const actual = await reader.receive();
    assert.equal(actual.transaction, 27);
    assert.equal(actual.code, 0x1001);
    assert.deepEqual(Array.from(actual.payload), [10, 20, 30, 40]);
  }
  const oneByteChunks = Array.from(bytes, value => Buffer.from([value]));
  assert.deepEqual(Array.from((await readerFor(oneByteChunks).receive()).payload), [10, 20, 30, 40]);
});

test('USB reader retains coalesced response bytes after streaming photo data', async () => {
  const data = container(2, 0x1009, 77, Buffer.from([1, 2, 3, 4, 5]));
  const response = container(3, 0x2001, 77, u32(0));
  const combined = Buffer.concat([data, response]);
  for (const chunks of [[combined], [combined.subarray(0, 9), combined.subarray(9, 20), combined.subarray(20)]]) {
    const reader = readerFor(chunks);
    const written = [];
    assert.equal(await reader.receiveData(0x1009, 77, 5, bytes => written.push(...bytes)), 5);
    assert.deepEqual(written, [1, 2, 3, 4, 5]);
    const final = await reader.receive();
    assert.equal(final.type, 3);
    assert.equal(final.code, 0x2001);
    assert.equal(final.transaction, 77);
    assert.deepEqual(Array.from(final.payload), [0, 0, 0, 0]);
  }
});

test('photo streaming exceeds the control-data limit using bounded 16 KiB chunks', async () => {
  const expected = 17 * 1024 * 1024 + 31;
  const header = Buffer.concat([u32(expected + 12), u16(2), u16(0x1009), u32(91)]);
  const finalResponse = container(3, 0x2001, 91);
  let sentHeader = false;
  let generated = 0;
  let sentResponse = false;
  let largestWritten = 0;
  let written = 0;
  const reader = new PtpInput(async () => {
    if (!sentHeader) {
      sentHeader = true;
      return header;
    }
    if (generated < expected) {
      const size = Math.min(16 * 1024, expected - generated);
      generated += size;
      return Buffer.alloc(size, 0xa5);
    }
    assert.equal(sentResponse, false, 'No reads should follow the response');
    sentResponse = true;
    return finalResponse;
  });
  const total = await reader.receiveData(0x1009, 91, expected, bytes => {
    largestWritten = Math.max(largestWritten, bytes.length);
    written += bytes.length;
    assert.equal(bytes[0], 0xa5);
    assert.equal(bytes[bytes.length - 1], 0xa5);
  });
  assert.equal(total, expected);
  assert.equal(written, expected);
  assert.equal(generated, expected);
  assert.ok(largestWritten <= 16 * 1024);
  assert.equal((await reader.receive()).code, 0x2001);
});

test('photo streaming rejects wrong transaction, opcode, type, or advertised size before writing', async () => {
  const cases = [
    container(2, 0x1009, 8, Buffer.from([1, 2])),
    container(2, 0x1008, 7, Buffer.from([1, 2])),
    container(1, 0x1009, 7, Buffer.from([1, 2])),
    container(2, 0x1009, 7, Buffer.from([1])),
  ];
  for (const bytes of cases) {
    let writeCalls = 0;
    await assert.rejects(readerFor([bytes]).receiveData(0x1009, 7, 2, () => { writeCalls++; }));
    assert.equal(writeCalls, 0);
  }
});

test('PTP error responses retain code, operation, and transaction; success without data is rejected', async () => {
  const reader = readerFor([container(3, 0x2019, 15, u32(1))]);
  await assert.rejects(reader.receiveData(0x1009, 15, 10, () => assert.fail('Unexpected photo write')), error => {
    assert.ok(error instanceof PtpResponseError);
    assert.equal(error.responseCode, 0x2019);
    assert.equal(error.operationCode, 0x1009);
    assert.equal(error.transactionId, 15);
    return true;
  });
  await assert.rejects(readerFor([container(3, 0x2001, 15)]).receiveData(0x1009, 15, 10, () => {}));
  await assert.rejects(readerFor([container(3, 0x2019, 15, Buffer.alloc(24))])
    .receiveData(0x1009, 15, 10, () => {}));
});

test('reader rejects stalled transfers and propagates USB and output-write failures', async () => {
  let calls = 0;
  const stalled = new PtpInput(async () => { calls++; return new Uint8Array(0); });
  await assert.rejects(stalled.receive());
  assert.equal(calls, 8);
  const detached = new Error('Fixture: USB detached');
  await assert.rejects(new PtpInput(async () => { throw detached; }).receive(), error => error === detached);
  const fullDisk = new Error('Fixture: disk full');
  await assert.rejects(readerFor([container(2, 0x1009, 1, Buffer.from([1, 2]))])
    .receiveData(0x1009, 1, 2, () => { throw fullDisk; }), error => error === fullDisk);
});

test('ordinary USB receive rejects an oversized header before attempting its payload', async () => {
  const header = Buffer.concat([u32(16 * 1024 * 1024 + 1), u16(2), u16(0x1007), u32(5)]);
  let reads = 0;
  const reader = new PtpInput(async () => { reads++; return header; });
  await assert.rejects(reader.receive());
  assert.equal(reads, 1);
});
