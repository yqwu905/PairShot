import { common } from '@kit.AbilityKit';
import { fileIo as fs, hash, picker } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { CameraPhoto, CapturedPhoto, PhonePhoto, SessionRecord, SessionSummary, ShotRecord, MAX_PHONE_LENSES } from '../model/Models';

const MAX_ZIP_SIZE: number = 0xFFFFFFFF;
const CHUNK_SIZE: number = 65536;

function requireValue(condition: boolean, message: string): void {
  if (!condition) {
    throw new Error(message);
  }
}

function isId(value: string): boolean {
  return typeof value === 'string' && /^[a-zA-Z0-9_-]{1,100}$/.test(value);
}

function filename(value: string): string {
  const safe = value.replace(/[^a-zA-Z0-9._-]/g, '_').substring(0,120);
  return safe.length > 0 && safe !== '.' && safe !== '..' ? safe : 'photo.bin';
}

function phoneRelative(shot: ShotRecord, lensId: string): string {
  requireValue(isId(shot.id), '拍摄记录标识无效');
  requireValue(typeof lensId === 'string' && lensId.length > 0 && lensId.length <= 128, '镜头标识无效');
  return `phone/${shot.id}/${encodeURIComponent(lensId)}.jpg`;
}

function cameraRelative(shot: ShotRecord, photo: CameraPhoto): string {
  requireValue(isId(shot.id), '拍摄记录标识无效');
  requireValue(Number.isInteger(photo.handle) && photo.handle > 0 && photo.handle <= 0xFFFFFFFF, '相机文件句柄无效');
  requireValue(typeof photo.filename === 'string' && photo.filename.length > 0, '相机文件名无效');
  return `camera/${shot.id}/${photo.handle.toString(16)}_${filename(photo.filename)}`;
}

function makeDirectory(path: string): void {
  if (!fs.accessSync(path)) {
    fs.mkdirSync(path, true);
  }
}

function writeWhole(fd: number, data: ArrayBuffer): void {
  let offset = 0;
  while (offset < data.byteLength) {
    const written = fs.writeSync(fd, data.slice(offset));
    requireValue(written > 0, '文件写入未取得进展');
    offset += written;
  }
}

function writeAtomic(path: string, data: ArrayBuffer): void {
  const temp = `${path}.part`;
  const file = fs.openSync(temp, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
  try {
    writeWhole(file.fd, data);
    fs.fsyncSync(file.fd);
  } finally {
    fs.closeSync(file);
  }
  fs.renameSync(temp, path);
}

function textBytes(value: string): ArrayBuffer {
  return new util.TextEncoder().encodeInto(value).buffer as ArrayBuffer;
}

function deleteIfPresent(path: string): void {
  try {
    if (fs.accessSync(path)) {
      fs.unlinkSync(path);
    }
  } catch (_) {
    // Export scratch cleanup must never hide the original failure or touch source photos.
  }
}

class ZipSource {
  name: string = '';
  path: string = '';
  size: number = 0;
}

class ZipEntry {
  name: Uint8Array = new Uint8Array(0);
  crc: number = 0;
  size: number = 0;
  offset: number = 0;
}

/** Streaming ZIP STORE with UTF-8 names and data descriptors; JPEG/RAW bytes remain unchanged. */
class ZipWriter {
  private fd: number;
  private position: number = 0;
  private entries: ZipEntry[] = [];
  private crcTable: number[] = [];

  constructor(fd: number) {
    this.fd = fd;
    for (let n = 0; n < 256; n++) {
      let crc = n;
      for (let bit = 0; bit < 8; bit++) {
        crc = (crc & 1) !== 0 ? 0xEDB88320 ^ (crc >>> 1) : crc >>> 1;
      }
      this.crcTable.push(crc >>> 0);
    }
  }

  private async write(data: ArrayBuffer): Promise<void> {
    requireValue(this.position + data.byteLength <= MAX_ZIP_SIZE, '归档超过 4 GiB，请分批导出');
    let offset = 0;
    while (offset < data.byteLength) {
      const count = await fs.write(this.fd, data.slice(offset));
      requireValue(count > 0, 'ZIP 写入失败');
      offset += count;
      this.position += count;
    }
  }

  async add(source: ZipSource): Promise<void> {
    requireValue(this.entries.length < 65535, '归档文件数量超过 ZIP 上限');
    const entry = new ZipEntry();
    entry.name = new util.TextEncoder().encodeInto(source.name);
    entry.size = source.size;
    entry.offset = this.position;
    requireValue(entry.name.byteLength <= 65535, '归档文件名过长');
    const header = new ArrayBuffer(30 + entry.name.byteLength);
    const h = new DataView(header);
    h.setUint32(0, 0x04034B50, true);
    h.setUint16(4, 20, true);
    h.setUint16(6, 0x0808, true); // UTF-8 plus post-data descriptor.
    h.setUint16(12, 33, true); // 1980-01-01; actual capture time is in manifest.json.
    h.setUint16(26, entry.name.byteLength, true);
    new Uint8Array(header, 30).set(entry.name);
    await this.write(header);
    const file = fs.openSync(source.path, fs.OpenMode.READ_ONLY);
    let count = 0;
    let crc = 0xFFFFFFFF;
    const chunk = new ArrayBuffer(CHUNK_SIZE);
    try {
      while (count < source.size) {
        const read = await fs.read(file.fd, chunk, { length: Math.min(CHUNK_SIZE, source.size - count) });
        requireValue(read > 0, `读取文件中断：${source.name}`);
        const bytes = new Uint8Array(chunk, 0, read);
        for (let i = 0; i < read; i++) {
          crc = this.crcTable[(crc ^ bytes[i]) & 0xFF] ^ (crc >>> 8);
        }
        await this.write(chunk.slice(0, read));
        count += read;
      }
      requireValue(fs.statSync(file.fd).size === source.size, `导出期间文件大小变化：${source.name}`);
    } finally {
      fs.closeSync(file);
    }
    entry.crc = (crc ^ 0xFFFFFFFF) >>> 0;
    const descriptor = new ArrayBuffer(16);
    const d = new DataView(descriptor);
    d.setUint32(0, 0x08074B50, true);
    d.setUint32(4, entry.crc, true);
    d.setUint32(8, entry.size, true);
    d.setUint32(12, entry.size, true);
    await this.write(descriptor);
    this.entries.push(entry);
  }

  async finish(): Promise<void> {
    const start = this.position;
    for (const entry of this.entries) {
      const header = new ArrayBuffer(46 + entry.name.byteLength);
      const h = new DataView(header);
      h.setUint32(0, 0x02014B50, true);
      h.setUint16(4, 20, true);
      h.setUint16(6, 20, true);
      h.setUint16(8, 0x0808, true);
      h.setUint16(14, 33, true);
      h.setUint32(16, entry.crc, true);
      h.setUint32(20, entry.size, true);
      h.setUint32(24, entry.size, true);
      h.setUint16(28, entry.name.byteLength, true);
      h.setUint32(42, entry.offset, true);
      new Uint8Array(header, 46).set(entry.name);
      await this.write(header);
    }
    const end = new ArrayBuffer(22);
    const d = new DataView(end);
    d.setUint32(0, 0x06054B50, true);
    d.setUint16(8, this.entries.length, true);
    d.setUint16(10, this.entries.length, true);
    d.setUint32(12, this.position - start, true);
    d.setUint32(16, start, true);
    await this.write(end);
  }
}

export class SessionStore {
  private context: common.UIAbilityContext;
  private root: string;
  private current: SessionRecord = new SessionRecord();
  private sequence: number = 0;
  private exporting: boolean = false;

  constructor(context: common.UIAbilityContext) {
    this.context = context;
    this.root = `${context.filesDir}/pairshot`;
  }

  async initialize(): Promise<void> {
    makeDirectory(this.root);
    const pointer = `${this.root}/current.txt`;
    if (fs.accessSync(pointer)) {
      const id = fs.readTextSync(pointer).trim();
      this.loadSession(id);
    } else {
      const sessions = this.listSessions();
      if (sessions.length > 0) {
        this.loadSession(sessions[0].id);
      } else {
        this.newSession();
      }
    }
  }

  getCurrent(): SessionRecord {
    return this.current;
  }

  private nextId(): string {
    this.sequence++;
    return `${Date.now().toString(36)}_${this.sequence.toString(36)}_${Math.floor(Math.random() * 0x100000000).toString(36)}`;
  }

  private sessionPath(id: string): string {
    requireValue(isId(id), '批次标识无效');
    return `${this.root}/${id}`;
  }

  private assertMutable(): void {
    requireValue(!this.exporting, '正在导出，请等待完成后再拍摄或切换记录');
  }

  private assertShot(shot: ShotRecord): void {
    this.assertMutable();
    requireValue(this.current.shots.indexOf(shot) >= 0, '拍摄记录不属于当前批次');
  }

  private validate(session: SessionRecord, id: string): void {
    requireValue(session !== null && session.schemaVersion === 1 && session.platform === 'HarmonyOS' && session.id === id, '批次清单无效或版本不兼容');
    requireValue(Array.isArray(session.shots) && Number.isFinite(session.createdAt), '批次清单数据不完整');
    const shotIds: string[] = [];
    for (const shot of session.shots) {
      requireValue(shot !== null && isId(shot.id) && shotIds.indexOf(shot.id) < 0, '拍摄记录标识无效或重复');
      shotIds.push(shot.id);
      requireValue(shot.mode === 'PHONE_ONLY' || shot.mode === 'PHONE_TO_CAMERA' || shot.mode === 'CAMERA_TO_PHONE', '拍摄模式无效');
      requireValue(Array.isArray(shot.expectedLensIds) && Array.isArray(shot.phonePhotos) && Array.isArray(shot.cameraPhotos), '拍摄记录列表不完整');
      const lensIds: string[] = [];
      for (const lensId of shot.expectedLensIds) {
        phoneRelative(shot, lensId);
        requireValue(lensIds.indexOf(lensId) < 0, '重复的预期镜头');
        lensIds.push(lensId);
      }
      const photoLensIds: string[] = [];
      for (const photo of shot.phonePhotos) {
        requireValue(photo !== null && lensIds.indexOf(photo.lensId) >= 0 && photoLensIds.indexOf(photo.lensId) < 0, '手机照片镜头无效或重复');
        photoLensIds.push(photo.lensId);
        requireValue(photo.relativePath === phoneRelative(shot, photo.lensId), '手机照片路径无效');
        requireValue(Number.isInteger(photo.bytes) && photo.bytes > 0 && typeof photo.sha256 === 'string' && /^[a-f0-9]{64}$/i.test(photo.sha256), '手机照片校验信息无效');
      }
      const handles: number[] = [];
      for (const photo of shot.cameraPhotos) {
        requireValue(photo !== null, '相机照片记录无效');
        const expected = cameraRelative(shot, photo);
        requireValue(handles.indexOf(photo.handle) < 0, '相机文件句柄重复');
        handles.push(photo.handle);
        requireValue(Number.isInteger(photo.size) && photo.size > 0 && photo.size <= MAX_ZIP_SIZE, '相机照片大小无效');
        requireValue(photo.relativePath === '' || photo.relativePath === expected, '相机照片路径无效');
        if (photo.relativePath.length > 0) {
          requireValue(typeof photo.sha256 === 'string' && /^[a-f0-9]{64}$/i.test(photo.sha256), '相机照片校验信息无效');
        }
      }
    }
  }

  listSessions(): SessionSummary[] {
    makeDirectory(this.root);
    const result: SessionSummary[] = [];
    for (const id of fs.listFileSync(this.root)) {
      if (!isId(id)) {
        continue;
      }
      const path = `${this.sessionPath(id)}/session.json`;
      if (!fs.accessSync(path)) {
        continue;
      }
      try {
        const session = JSON.parse(fs.readTextSync(path)) as SessionRecord;
        this.validate(session, id);
        const summary = new SessionSummary();
        summary.id = id;
        summary.createdAt = session.createdAt;
        summary.shotCount = session.shots.length;
        result.push(summary);
      } catch (_) {
        // Preserve damaged files for recovery instead of silently replacing them.
      }
    }
    return result.sort((a: SessionSummary, b: SessionSummary) => b.createdAt - a.createdAt);
  }

  newSession(): SessionRecord {
    this.assertMutable();
    const session = new SessionRecord();
    session.id = this.nextId();
    session.createdAt = Date.now();
    makeDirectory(this.sessionPath(session.id));
    this.current = session;
    this.save();
    writeAtomic(`${this.root}/current.txt`, textBytes(session.id));
    return session;
  }

  loadSession(id: string): SessionRecord {
    this.assertMutable();
    const session = JSON.parse(fs.readTextSync(`${this.sessionPath(id)}/session.json`)) as SessionRecord;
    this.validate(session, id);
    for (const shot of session.shots) {
      if (shot.status === 'pending') {
        shot.status = 'incomplete';
        shot.error = '上次拍摄未完成，已保留成功保存的照片';
      }
    }
    this.current = session;
    this.save();
    writeAtomic(`${this.root}/current.txt`, textBytes(id));
    return session;
  }

  createShot(mode: string, lensIds: string[], cameraIdentity: string = ''): ShotRecord {
    this.assertMutable();
    requireValue(mode === 'PHONE_ONLY' || mode === 'PHONE_TO_CAMERA' || mode === 'CAMERA_TO_PHONE', '拍摄模式无效');
    requireValue(lensIds.length > 0 && lensIds.length <= MAX_PHONE_LENSES, `请选择 1 至 ${MAX_PHONE_LENSES} 枚镜头`);
    const shot = new ShotRecord();
    shot.id = this.nextId();
    shot.createdAt = Date.now();
    shot.mode = mode;
    shot.expectedLensIds = lensIds.slice();
    shot.cameraIdentity = cameraIdentity;
    const checkedIds: string[] = [];
    for (const lensId of lensIds) {
      phoneRelative(shot, lensId);
      requireValue(checkedIds.indexOf(lensId) < 0, '同一镜头不能重复选择');
      checkedIds.push(lensId);
    }
    this.current.shots.push(shot);
    try {
      this.save();
    } catch (error) {
      this.current.shots.pop();
      const failure = error as Error;
      throw failure;
    }
    return shot;
  }

  save(): void {
    this.assertMutable();
    this.validate(this.current, this.current.id);
    writeAtomic(`${this.sessionPath(this.current.id)}/session.json`, textBytes(JSON.stringify(this.current, null, 2)));
  }

  async addPhonePhoto(shot: ShotRecord, photo: CapturedPhoto): Promise<void> {
    this.assertShot(shot);
    requireValue(shot.expectedLensIds.indexOf(photo.lensId) >= 0, '照片不是本次选择的镜头');
    requireValue(!shot.phonePhotos.some((item: PhonePhoto) => item.lensId === photo.lensId), '同一镜头照片已保存，请新建拍摄记录');
    requireValue(photo.jpeg.byteLength > 0, '照片数据为空');
    const record = new PhonePhoto();
    record.lensId = photo.lensId;
    record.lensLabel = photo.lensLabel;
    record.zoom = photo.zoom;
    record.capturedAt = photo.capturedAt;
    record.captureMode = photo.captureMode || 'SINGLE';
    record.shutterTimestampMs = Number.isFinite(photo.shutterTimestampMs) ? photo.shutterTimestampMs : 0;
    record.relativePath = phoneRelative(shot, photo.lensId);
    record.bytes = photo.jpeg.byteLength;
    const path = `${this.sessionPath(this.current.id)}/${record.relativePath}`;
    makeDirectory(`${this.sessionPath(this.current.id)}/phone/${shot.id}`);
    writeAtomic(path, photo.jpeg);
    record.sha256 = (await hash.hash(path, 'sha256')).toLowerCase();
    this.assertShot(shot);
    shot.phonePhotos.push(record);
    this.save();
  }

  getCameraPath(shot: ShotRecord, photo: CameraPhoto): string {
    this.assertShot(shot);
    const path = cameraRelative(shot, photo);
    makeDirectory(`${this.sessionPath(this.current.id)}/camera/${shot.id}`);
    return `${this.sessionPath(this.current.id)}/${path}`;
  }

  async commitCameraPhoto(shot: ShotRecord, photo: CameraPhoto): Promise<void> {
    this.assertShot(shot);
    const path = this.getCameraPath(shot, photo);
    requireValue(fs.statSync(path).isFile() && fs.statSync(path).size === photo.size, '导入的相机照片大小不匹配');
    const digest = (await hash.hash(path, 'sha256')).toLowerCase();
    this.assertShot(shot);
    const old = shot.cameraPhotos.find((item: CameraPhoto) => item.handle === photo.handle);
    requireValue(old === undefined || old === photo, '相机文件句柄已属于另一条照片记录');
    photo.relativePath = cameraRelative(shot, photo);
    photo.sha256 = digest;
    photo.importError = '';
    if (old === undefined) {
      shot.cameraPhotos.push(photo);
    }
    this.save();
  }

  async verifyCameraPhoto(shot: ShotRecord, photo: CameraPhoto): Promise<void> {
    this.assertShot(shot);
    requireValue(shot.cameraPhotos.indexOf(photo) >= 0, '相机照片不属于此拍摄记录');
    const relativePath = cameraRelative(shot, photo);
    requireValue(photo.relativePath === relativePath, '相机照片路径无效或尚未导入');
    requireValue(Number.isInteger(photo.size) && photo.size > 0 && photo.size <= MAX_ZIP_SIZE,
      '相机照片大小无效');
    requireValue(typeof photo.sha256 === 'string' && /^[a-f0-9]{64}$/i.test(photo.sha256),
      '相机照片校验信息无效');
    await this.verify(`${this.sessionPath(this.current.id)}/${relativePath}`, photo.size, photo.sha256);
    this.assertShot(shot);
  }

  isComplete(shot: ShotRecord): boolean {
    const phonesComplete = shot.expectedLensIds.length > 0 && shot.expectedLensIds.every((id: string) =>
      shot.phonePhotos.some((photo: PhonePhoto) => photo.lensId === id && photo.bytes > 0 && photo.sha256.length === 64));
    if (!phonesComplete) {
      return false;
    }
    return shot.mode === 'PHONE_ONLY' || (shot.cameraPhotos.length > 0 && shot.cameraPhotos.every((photo: CameraPhoto) =>
      photo.relativePath.length > 0 && photo.sha256.length === 64 && photo.importError.length === 0));
  }

  private async verify(path: string, size: number, digest: string): Promise<void> {
    requireValue(fs.accessSync(path), '照片文件缺失，无法完整导出');
    const stat = fs.statSync(path);
    requireValue(stat.isFile() && stat.size === size, '照片大小校验失败，已停止导出');
    requireValue((await hash.hash(path, 'sha256')).toLowerCase() === digest.toLowerCase(), '照片摘要校验失败，已停止导出');
  }

  async exportZip(allowIncomplete: boolean): Promise<string> {
    this.assertMutable();
    this.validate(this.current, this.current.id);
    requireValue(this.current.shots.length > 0, '当前批次还没有拍摄记录');
    const snapshot = JSON.parse(JSON.stringify(this.current)) as SessionRecord;
    requireValue(allowIncomplete || snapshot.shots.every((shot: ShotRecord) => this.isComplete(shot)), '存在不完整记录；请先导入相机照片，或明确允许导出不完整记录');
    const directory = this.sessionPath(snapshot.id);
    const prefix = `${this.context.cacheDir}/pairshot-export-${this.nextId()}`;
    const manifestPath = `${prefix}.json`;
    const zipPath = `${prefix}.zip`;
    this.exporting = true;
    try {
      const sources: ZipSource[] = [];
      for (const shot of snapshot.shots) {
        shot.status = this.isComplete(shot) ? 'complete' : 'incomplete';
        for (const photo of shot.phonePhotos) {
          const source = new ZipSource();
          source.name = photo.relativePath;
          source.path = `${directory}/${source.name}`;
          source.size = photo.bytes;
          // Missing persisted files can be listed as missing only with explicit consent.
          if (!fs.accessSync(source.path) && allowIncomplete) {
            shot.status = 'incomplete';
            shot.error = '手机照片文件缺失：' + photo.relativePath;
            continue;
          }
          await this.verify(source.path, source.size, photo.sha256);
          sources.push(source);
        }
        for (const photo of shot.cameraPhotos) {
          if (photo.relativePath.length === 0) {
            continue;
          }
          const source = new ZipSource();
          source.name = photo.relativePath;
          source.path = `${directory}/${source.name}`;
          source.size = photo.size;
          if (!fs.accessSync(source.path) && allowIncomplete) {
            shot.status = 'incomplete';
            photo.importError = '已导入的相机照片文件缺失';
            continue;
          }
          await this.verify(source.path, source.size, photo.sha256);
          sources.push(source);
        }
      }
      writeAtomic(manifestPath, textBytes(JSON.stringify(snapshot, null, 2)));
      const manifest = new ZipSource();
      manifest.name = 'manifest.json';
      manifest.path = manifestPath;
      manifest.size = fs.statSync(manifestPath).size;
      sources.unshift(manifest);
      let estimated = 22;
      for (const source of sources) {
        estimated += source.size + 92 + textBytes(source.name).byteLength * 2;
      }
      requireValue(estimated <= MAX_ZIP_SIZE && sources.length <= 65535, '本批次超过 ZIP 4 GiB 或文件数上限，请使用较小批次');
      const file = fs.openSync(zipPath, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
      try {
        const zip = new ZipWriter(file.fd);
        for (const source of sources) {
          await zip.add(source);
        }
        await zip.finish();
        fs.fsyncSync(file.fd);
      } finally {
        fs.closeSync(file);
      }
      const documentPicker = new picker.DocumentViewPicker(this.context);
      const options = new picker.DocumentSaveOptions();
      options.newFileNames = [`PairShot-${snapshot.id}.zip`];
      options.fileSuffixChoices = ['ZIP archive|.zip'];
      const uris = await documentPicker.save(options);
      requireValue(uris.length === 1 && uris[0].length > 0, '已取消导出');
      const target = fs.openSync(uris[0], fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
      try {
        await fs.copyFile(zipPath, target.fd);
        fs.fsyncSync(target.fd);
      } finally {
        fs.closeSync(target);
      }
      return uris[0];
    } finally {
      this.exporting = false;
      deleteIfPresent(zipPath);
      deleteIfPresent(manifestPath);
      deleteIfPresent(`${manifestPath}.part`);
    }
  }
}
