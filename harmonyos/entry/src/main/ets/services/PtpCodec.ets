// PTP wire-format facts, implemented independently. No vendor library is bundled.
export class PtpHeader {
  size: number = 12;
  type: number = 0;
  code: number = 0;
  transaction: number = 0;
}

export class PtpContainer extends PtpHeader {
  payload: Uint8Array = new Uint8Array(0);
}

export class PtpDeviceData {
  manufacturer: string = '';
  model: string = '';
  version: string = '';
  serial: string = '';
  vendor: number = 0;
  operations: number[] = [];
  events: number[] = [];
}

export class PtpObjectData {
  handle: number = 0;
  storage: number = 0;
  format: number = 0;
  size: number = 0;
  parent: number = 0;
  filename: string = '';
  captureDate: string = '';

  isPhoto(): boolean {
    const dot = this.filename.lastIndexOf('.');
    const suffix = dot >= 0 ? this.filename.substring(dot + 1).toLowerCase() : '';
    return this.format !== 0x3001 && ['jpg', 'jpeg', 'nef', 'nrw', 'dng', 'tif', 'tiff',
      'heic', 'hif', 'cr2', 'cr3', 'arw', 'raf', 'orf', 'rw2', 'pef'].includes(suffix);
  }
}

export class DatasetReader {
  private view: DataView;
  private position: number = 0;

  constructor(bytes: Uint8Array) {
    this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  }

  private need(count: number): void {
    if (!Number.isInteger(count) || count < 0 || count > this.view.byteLength - this.position) {
      throw new Error('PTP 信息数据不完整或长度无效');
    }
  }

  u16(): number {
    this.need(2);
    const result = this.view.getUint16(this.position, true);
    this.position += 2;
    return result;
  }

  u32(): number {
    this.need(4);
    const result = this.view.getUint32(this.position, true);
    this.position += 4;
    return result;
  }

  skip(count: number): void {
    this.need(count);
    this.position += count;
  }

  u16Array(): number[] {
    const count = this.u32();
    this.need(count * 2);
    const result: number[] = [];
    for (let i = 0; i < count; i++) {
      result.push(this.u16());
    }
    return result;
  }

  u32Array(): number[] {
    const count = this.u32();
    this.need(count * 4);
    const result: number[] = [];
    for (let i = 0; i < count; i++) {
      result.push(this.u32());
    }
    return result;
  }

  string(): string {
    this.need(1);
    const count = this.view.getUint8(this.position++);
    if (count === 0) {
      return '';
    }
    this.need(count * 2);
    let value = '';
    for (let i = 0; i < count - 1; i++) {
      value += String.fromCharCode(this.u16());
    }
    if (this.u16() !== 0) {
      throw new Error('PTP 字符串没有结束标记');
    }
    return value;
  }
}

export class PtpResponseError extends Error {
  responseCode: number;
  operationCode: number;
  transactionId: number;

  constructor(response: number, operation: number, transaction: number) {
    let message = '相机返回错误';
    switch (response) {
      case 0x2003: message = '相机会话已失效，请重新连接'; break;
      case 0x2004: message = '相机事务序号异常，请重新连接'; break;
      case 0x2005: message = '相机不支持此操作'; break;
      case 0x200f: message = '相机拒绝访问，请检查 USB 连接模式'; break;
      case 0x2019: message = '相机忙，请稍后再试'; break;
      case 0x201d: message = '相机参数不支持，请检查拍摄模式'; break;
      case 0x201e: message = '相机存在尚未结束的会话'; break;
      case 0xa002: message = '相机未合焦，请先在相机端完成对焦'; break;
      case 0xa004: message = '相机当前状态无法拍摄，请查看相机屏幕'; break;
      case 0xa008: message = '当前为 B 门模式，不支持长按快门'; break;
      case 0xa021: message = '相机存储错误，请检查存储卡'; break;
      case 0xa022: message = '相机存储卡未格式化，请在相机端检查'; break;
    }
    super(`${message}（返回 0x${response.toString(16)}，操作 0x${operation.toString(16)}，事务 ${transaction}）`);
    this.name = 'PtpResponseError';
    this.responseCode = response;
    this.operationCode = operation;
    this.transactionId = transaction;
  }
}

export class PtpCodec {
  static readonly HEADER_SIZE: number = 12;
  static readonly MAX_CONTROL: number = 16 * 1024 * 1024;
  static readonly COMMAND: number = 1;
  static readonly DATA: number = 2;
  static readonly RESPONSE: number = 3;
  static readonly OK: number = 0x2001;
  static readonly GET_DEVICE_INFO: number = 0x1001;
  static readonly OPEN_SESSION: number = 0x1002;
  static readonly CLOSE_SESSION: number = 0x1003;
  static readonly GET_OBJECT_HANDLES: number = 0x1007;
  static readonly GET_OBJECT_INFO: number = 0x1008;
  static readonly GET_OBJECT: number = 0x1009;
  static readonly INITIATE_CAPTURE: number = 0x100e;
  static readonly NIKON_MODE: number = 0x90c2;
  static readonly NIKON_EVENTS: number = 0x90c7;
  static readonly NIKON_READY: number = 0x90c8;
  static readonly NIKON_CAPTURE: number = 0x9207;

  static command(code: number, transaction: number, parameters: number[]): Uint8Array {
    if (!Number.isInteger(code) || code < 0 || code > 0xffff || !Number.isInteger(transaction) ||
      transaction < 0 || transaction > 0xffffffff || parameters.length > 5) {
      throw new Error('PTP 命令参数无效');
    }
    const result = new Uint8Array(12 + 4 * parameters.length);
    const view = new DataView(result.buffer);
    view.setUint32(0, result.length, true);
    view.setUint16(4, PtpCodec.COMMAND, true);
    view.setUint16(6, code, true);
    view.setUint32(8, transaction, true);
    for (let i = 0; i < parameters.length; i++) {
      const value = parameters[i];
      if (!Number.isInteger(value) || value < 0 || value > 0xffffffff) {
        throw new Error('PTP 命令参数超出范围');
      }
      view.setUint32(12 + 4 * i, value, true);
    }
    return result;
  }

  static header(bytes: Uint8Array): PtpHeader {
    if (bytes.length < 12) {
      throw new Error('PTP 数据头不完整');
    }
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const result = new PtpHeader();
    result.size = view.getUint32(0, true);
    result.type = view.getUint16(4, true);
    result.code = view.getUint16(6, true);
    result.transaction = view.getUint32(8, true);
    if (result.size < 12 || result.type < 1 || result.type > 4) {
      throw new Error('PTP 数据头长度或类型无效');
    }
    return result;
  }

  static decode(bytes: Uint8Array): PtpContainer {
    const header = PtpCodec.header(bytes);
    if (header.size !== bytes.length || header.size > PtpCodec.MAX_CONTROL) {
      throw new Error('PTP 控制数据长度不匹配或过大');
    }
    const result = new PtpContainer();
    result.size = header.size;
    result.type = header.type;
    result.code = header.code;
    result.transaction = header.transaction;
    result.payload = bytes.slice(12);
    return result;
  }

  static deviceInfo(bytes: Uint8Array): PtpDeviceData {
    const reader = new DatasetReader(bytes);
    reader.u16();
    const info = new PtpDeviceData();
    info.vendor = reader.u32();
    reader.u16();
    reader.string();
    reader.u16();
    info.operations = reader.u16Array();
    info.events = reader.u16Array();
    reader.u16Array();
    reader.u16Array();
    reader.u16Array();
    info.manufacturer = reader.string();
    info.model = reader.string();
    info.version = reader.string();
    info.serial = reader.string();
    return info;
  }

  static captureMode(info: PtpDeviceData): string {
    let normalized = info.model.toUpperCase().replace(/[^A-Z0-9]/g, '');
    if (normalized.startsWith('NIKON')) {
      normalized = normalized.substring(5);
    }
    if (info.vendor === 0x0000000a && normalized === 'Z6' && info.operations.includes(PtpCodec.NIKON_CAPTURE)) {
      return 'NIKON_Z6';
    }
    return info.operations.includes(PtpCodec.INITIATE_CAPTURE) ? 'STANDARD' : 'UNSUPPORTED';
  }

  static objectInfo(handle: number, bytes: Uint8Array): PtpObjectData {
    const reader = new DatasetReader(bytes);
    const info = new PtpObjectData();
    info.handle = handle;
    info.storage = reader.u32();
    info.format = reader.u16();
    reader.u16();
    info.size = reader.u32();
    reader.skip(26);
    info.parent = reader.u32();
    reader.skip(10);
    info.filename = reader.string();
    info.captureDate = reader.string();
    reader.string();
    reader.string();
    return info;
  }

  static addedHandles(bytes: Uint8Array): number[] {
    const reader = new DatasetReader(bytes);
    const count = reader.u16();
    if (bytes.length !== 2 + count * 6) {
      throw new Error('尼康事件数据长度无效');
    }
    const result: number[] = [];
    for (let i = 0; i < count; i++) {
      const code = reader.u16();
      const handle = reader.u32();
      if (code === 0x4002 && handle !== 0) {
        result.push(handle);
      }
    }
    return result;
  }
}

/** Reads split/coalesced USB packets and streams photo data with bounded memory. */
export class PtpInput {
  private chunk: Uint8Array = new Uint8Array(0);
  private position: number = 0;
  private readChunk: () => Promise<Uint8Array>;

  constructor(readChunk: () => Promise<Uint8Array>) {
    this.readChunk = readChunk;
  }

  private async fill(): Promise<void> {
    if (this.position < this.chunk.length) {
      return;
    }
    const deadline = Date.now() + 5000;
    for (let empty = 0; empty < 8; empty++) {
      this.chunk = await this.readChunk();
      this.position = 0;
      if (this.chunk.length > 0) {
        return;
      }
      if (Date.now() >= deadline) {
        break;
      }
    }
    throw new Error('USB 数据传输停滞，请重新连接');
  }

  private async bytes(count: number): Promise<Uint8Array> {
    const result = new Uint8Array(count);
    let offset = 0;
    while (offset < count) {
      await this.fill();
      const length = Math.min(count - offset, this.chunk.length - this.position);
      result.set(this.chunk.subarray(this.position, this.position + length), offset);
      offset += length;
      this.position += length;
    }
    return result;
  }

  async receive(): Promise<PtpContainer> {
    const header = PtpCodec.header(await this.bytes(12));
    if (header.size > PtpCodec.MAX_CONTROL) {
      throw new Error('PTP 控制数据过大');
    }
    const result = new PtpContainer();
    result.size = header.size;
    result.type = header.type;
    result.code = header.code;
    result.transaction = header.transaction;
    result.payload = await this.bytes(header.size - 12);
    return result;
  }

  async receiveData(code: number, transaction: number, expected: number,
    writeChunk: (bytes: Uint8Array) => void): Promise<number> {
    const header = PtpCodec.header(await this.bytes(12));
    if (header.transaction !== transaction) {
      throw new Error('PTP 下载事务不匹配');
    }
    if (header.type === PtpCodec.RESPONSE) {
      if (header.size > 32) {
        throw new Error('PTP 响应长度无效');
      }
      await this.bytes(header.size - 12);
      if (header.code !== PtpCodec.OK) {
        throw new PtpResponseError(header.code, code, transaction);
      }
      throw new Error('相机未返回照片数据');
    }
    if (header.type !== PtpCodec.DATA || header.code !== code || header.size - 12 !== expected) {
      throw new Error('PTP 下载数据类型或照片大小不匹配');
    }
    let remaining = expected;
    while (remaining > 0) {
      await this.fill();
      const length = Math.min(remaining, this.chunk.length - this.position);
      writeChunk(this.chunk.subarray(this.position, this.position + length));
      this.position += length;
      remaining -= length;
    }
    return expected;
  }
}
