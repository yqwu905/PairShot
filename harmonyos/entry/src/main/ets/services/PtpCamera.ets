import { usbManager } from '@kit.BasicServicesKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';
import { CameraPhoto } from '../model/Models';
import { DatasetReader, PtpCodec, PtpDeviceData, PtpInput, PtpObjectData, PtpResponseError } from './PtpCodec';

export class UsbCameraInfo {
  name: string = '';
  label: string = '';
}

class UsbSelection {
  configuration: usbManager.USBConfiguration;
  iface: usbManager.USBInterface;
  input: usbManager.USBEndpoint;
  output: usbManager.USBEndpoint;

  constructor(configuration: usbManager.USBConfiguration, iface: usbManager.USBInterface,
    input: usbManager.USBEndpoint, output: usbManager.USBEndpoint) {
    this.configuration = configuration;
    this.iface = iface;
    this.input = input;
    this.output = output;
  }
}

/** USB/PTP transactions are serialized. No shutter operation is automatically retried. */
export class PtpCamera {
  private queue: Promise<void> = Promise.resolve();
  private pipe: usbManager.USBDevicePipe | undefined = undefined;
  private selection: UsbSelection | undefined = undefined;
  private reader: PtpInput | undefined = undefined;
  private deviceInfo: PtpDeviceData | undefined = undefined;
  private transaction: number = 1;
  private opened: boolean = false;
  private identity: string = '';
  private reliableIdentity: boolean = false;
  private nikonControlMode: boolean = false;
  private nikonModeAttempted: boolean = false;
  private nikonEventsDisabled: boolean = false;
  private lifecycleGeneration: number = 0;
  private activeGeneration: number = -1;

  private serial<T>(operation: (generation: number) => Promise<T>): Promise<T> {
    const generation = this.lifecycleGeneration;
    const result = this.queue.then(async (): Promise<T> => {
      this.checkGeneration(generation);
      const value = await operation(generation);
      this.checkGeneration(generation);
      return value;
    });
    this.queue = result.then(() => {}, () => {});
    return result;
  }

  private checkGeneration(generation: number): void {
    if (generation !== this.lifecycleGeneration) {
      throw new Error('USB 操作已取消，请重新连接相机');
    }
  }

  private findInterface(device: usbManager.USBDevice): UsbSelection | undefined {
    for (let c = 0; c < device.configs.length; c++) {
      const config = device.configs[c];
      for (let i = 0; i < config.interfaces.length; i++) {
        const iface = config.interfaces[i];
        if (iface.clazz !== 6 || iface.subClass !== 1 || iface.protocol !== 1) {
          continue;
        }
        let input: usbManager.USBEndpoint | undefined = undefined;
        let output: usbManager.USBEndpoint | undefined = undefined;
        for (let e = 0; e < iface.endpoints.length; e++) {
          const endpoint = iface.endpoints[e];
          if (endpoint.type !== 2) {
            continue;
          }
          if ((endpoint.address & 0x80) !== 0) {
            input = endpoint;
          } else {
            output = endpoint;
          }
        }
        if (input !== undefined && output !== undefined) {
          return new UsbSelection(config, iface, input, output);
        }
      }
    }
    return undefined;
  }

  listDevices(): UsbCameraInfo[] {
    const result: UsbCameraInfo[] = [];
    const devices = usbManager.getDevices();
    for (let i = 0; i < devices.length; i++) {
      const device = devices[i];
      if (this.findInterface(device) !== undefined) {
        const item = new UsbCameraInfo();
        item.name = device.name;
        item.label = `${device.manufacturerName || 'USB'} ${device.productName || 'PTP 相机'}`;
        result.push(item);
      }
    }
    return result;
  }

  connect(deviceName: string): Promise<string> {
    return this.serial(async (generation: number): Promise<string> => {
      try {
        await this.disconnectUnlocked();
      } catch (_) {
        // The prior pipe has already been released in finally.
      }
      this.checkGeneration(generation);
      const devices = usbManager.getDevices();
      let device: usbManager.USBDevice | undefined = undefined;
      for (let i = 0; i < devices.length; i++) {
        if (devices[i].name === deviceName) {
          device = devices[i];
          break;
        }
      }
      if (device === undefined) {
        throw new Error('USB 相机已断开，请检查 OTG 数据线');
      }
      const selected = this.findInterface(device);
      if (selected === undefined) {
        throw new Error('设备未提供 PTP 照片传输接口，请检查 USB 模式');
      }
      if (!usbManager.hasRight(device.name) && !await usbManager.requestRight(device.name)) {
        throw new Error('未获得 USB 访问授权');
      }
      this.checkGeneration(generation);
      try {
        this.pipe = usbManager.connectDevice(device);
        this.activeGeneration = generation;
        this.selection = selected;
        if (device.configs.length > 0 && device.configs[0].id !== selected.configuration.id &&
          usbManager.setConfiguration(this.pipe, selected.configuration) !== 0) {
          throw new Error('无法切换到相机的 PTP 配置');
        }
        if (usbManager.claimInterface(this.pipe, selected.iface, true) !== 0) {
          throw new Error('无法占用相机接口，请关闭其他联机应用');
        }
        this.reader = new PtpInput(() => this.readChunk());
        this.transaction = 1;
        const infoBytes = await this.transact(PtpCodec.GET_DEVICE_INFO, 0, []);
        const info = PtpCodec.deviceInfo(infoBytes);
        const md = cryptoFramework.createMd('SHA256');
        const identityBytes = new util.TextEncoder().encodeInto(`${info.vendor}|${info.manufacturer}|${info.model}|${info.serial}`);
        await md.update({ data: identityBytes });
        const digest = await md.digest();
        this.checkGeneration(generation);
        this.identity = Array.from(digest.data).map((byte: number) => byte.toString(16).padStart(2, '0')).join('');
        this.reliableIdentity = info.serial.trim().length > 0;
        // Never retain or log a camera serial number after hashing it.
        info.serial = '';
        this.deviceInfo = info;
        try {
          await this.transact(PtpCodec.OPEN_SESSION, 0, [1]);
        } catch (error) {
          const failure = error as Error;
          if (!(failure instanceof PtpResponseError) || failure.responseCode !== 0x201e) {
            throw failure;
          }
          // Recover one stale session before any shutter can be sent.
          try {
            await this.transact(PtpCodec.CLOSE_SESSION, this.nextTransaction(), []);
          } catch (closeError) {
            const closeFailure = closeError as Error;
            if (!(closeFailure instanceof PtpResponseError) || closeFailure.responseCode !== 0x2003) {
              throw closeFailure;
            }
          }
          this.transaction = 1;
          await this.transact(PtpCodec.OPEN_SESSION, 0, [1]);
        }
        this.checkGeneration(generation);
        this.opened = true;
        return `${info.manufacturer} ${info.model}`.trim() || 'PTP 相机';
      } catch (error) {
        this.release(generation);
        const failure = error as Error;
        throw failure;
      }
    });
  }

  getIdentity(): string {
    return this.identity;
  }

  hasReliableIdentity(): boolean {
    return this.reliableIdentity;
  }

  isConnected(): boolean {
    return this.opened && this.pipe !== undefined;
  }

  supportsCapture(): boolean {
    return this.isConnected() && this.deviceInfo !== undefined && PtpCodec.captureMode(this.deviceInfo) !== 'UNSUPPORTED';
  }

  supportsNikonEvents(): boolean {
    return this.isConnected() && !this.nikonEventsDisabled && this.deviceInfo !== undefined &&
      this.deviceInfo.vendor === 0x0a && this.deviceInfo.operations.includes(PtpCodec.NIKON_EVENTS);
  }

  private requireOpen(): PtpDeviceData {
    if (!this.opened || this.deviceInfo === undefined || this.reader === undefined || this.pipe === undefined) {
      throw new Error('相机尚未连接，或连接已失效');
    }
    return this.deviceInfo;
  }

  private nextTransaction(): number {
    if (this.transaction > 0xffffffff) {
      throw new Error('PTP 事务号已用尽，请重新连接相机');
    }
    return this.transaction++;
  }

  capture(): Promise<void> {
    return this.serial(async (): Promise<void> => {
      const info = this.requireOpen();
      const mode = PtpCodec.captureMode(info);
      if (mode === 'NIKON_Z6') {
        if (!this.nikonModeAttempted && info.operations.includes(PtpCodec.NIKON_MODE)) {
          try {
            await this.transact(PtpCodec.NIKON_MODE, this.nextTransaction(), [1]);
            this.nikonControlMode = true;
          } catch (error) {
            const failure = error as Error;
            if (!(failure instanceof PtpResponseError) || failure.responseCode !== 0xa003) {
              throw failure;
            }
          }
          this.nikonModeAttempted = true;
        }
        if (info.operations.includes(PtpCodec.NIKON_READY)) {
          await this.transact(PtpCodec.NIKON_READY, this.nextTransaction(), []);
        }
        await this.transact(PtpCodec.NIKON_CAPTURE, this.nextTransaction(), [0xffffffff, 0]);
      } else if (mode === 'STANDARD') {
        await this.transact(PtpCodec.INITIATE_CAPTURE, this.nextTransaction(), [0, 0]);
      } else {
        throw new Error('该型号没有声明受支持的远程快门指令，可尝试相机拍后跟拍');
      }
    });
  }

  useLocalShutter(): Promise<void> {
    return this.serial(async (): Promise<void> => {
      this.requireOpen();
      await this.restoreLocalShutter();
    });
  }

  private async restoreLocalShutter(): Promise<void> {
    if (this.nikonControlMode) {
      await this.transact(PtpCodec.NIKON_MODE, this.nextTransaction(), [0]);
      this.nikonControlMode = false;
      this.nikonModeAttempted = false;
    }
  }

  listPhotoHandles(): Promise<number[]> {
    return this.serial(async (): Promise<number[]> => {
      const info = this.requireOpen();
      if (!info.operations.includes(PtpCodec.GET_OBJECT_HANDLES)) {
        throw new Error('相机未声明照片列表读取能力');
      }
      const bytes = await this.transact(PtpCodec.GET_OBJECT_HANDLES, this.nextTransaction(), [0xffffffff, 0, 0]);
      return new DatasetReader(bytes).u32Array();
    });
  }

  private async objectInfo(handle: number): Promise<PtpObjectData> {
    const info = this.requireOpen();
    if (!info.operations.includes(PtpCodec.GET_OBJECT_INFO)) {
      throw new Error('相机未声明照片信息读取能力');
    }
    const bytes = await this.transact(PtpCodec.GET_OBJECT_INFO, this.nextTransaction(), [handle]);
    return PtpCodec.objectInfo(handle, bytes);
  }

  getPhotoInfo(handle: number): Promise<CameraPhoto> {
    return this.serial(async (): Promise<CameraPhoto> => {
      const info = await this.objectInfo(handle);
      if (!info.isPhoto()) {
        throw new Error('该对象不是可导入的照片');
      }
      const photo = new CameraPhoto();
      photo.handle = info.handle;
      photo.filename = info.filename;
      photo.size = info.size;
      photo.storage = info.storage;
      photo.parent = info.parent;
      photo.captureDate = info.captureDate;
      return photo;
    });
  }

  pollAddedHandles(): Promise<number[]> {
    return this.serial(async (): Promise<number[]> => {
      this.requireOpen();
      if (!this.supportsNikonEvents()) {
        return [];
      }
      try {
        const bytes = await this.transact(PtpCodec.NIKON_EVENTS, this.nextTransaction(), []);
        return PtpCodec.addedHandles(bytes);
      } catch (error) {
        const failure = error as Error;
        if (failure instanceof PtpResponseError && failure.responseCode === 0x2005) {
          this.nikonEventsDisabled = true;
          return [];
        }
        throw failure;
      }
    });
  }

  downloadPhoto(photo: CameraPhoto, path: string): Promise<void> {
    return this.serial(async (generation: number): Promise<void> => {
      const device = this.requireOpen();
      if (!device.operations.includes(PtpCodec.GET_OBJECT)) {
        throw new Error('相机未声明原片下载能力');
      }
      const current = await this.objectInfo(photo.handle);
      if (!current.isPhoto() || current.filename !== photo.filename || current.size !== photo.size ||
        current.storage !== photo.storage || current.parent !== photo.parent || current.captureDate !== photo.captureDate) {
        throw new Error('相机照片信息已变化，拒绝使用旧句柄导入');
      }
      if (photo.size <= 0 || photo.size > 0xffffffff - 12) {
        throw new Error('照片大小无效，或超出标准 PTP 单文件传输范围');
      }
      const partial = `${path}.part`;
      let file: fs.File | undefined = undefined;
      try {
        file = fs.openSync(partial, fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
        const transaction = this.nextTransaction();
        await this.send(PtpCodec.command(PtpCodec.GET_OBJECT, transaction, [photo.handle]));
        const reader = this.reader;
        if (reader === undefined) {
          throw new Error('USB 连接已关闭');
        }
        const fd = file.fd;
        await reader.receiveData(PtpCodec.GET_OBJECT, transaction, photo.size, (bytes: Uint8Array): void => {
          let offset = 0;
          while (offset < bytes.length) {
            const written = fs.writeSync(fd, bytes.slice(offset).buffer);
            if (written <= 0 || written > bytes.length - offset) {
              throw new Error('照片写入失败，请检查剩余存储空间');
            }
            offset += written;
          }
        });
        const response = await reader.receive();
        this.checkGeneration(generation);
        if (response.type !== PtpCodec.RESPONSE || response.transaction !== transaction || response.size > 32) {
          throw new Error('PTP 下载完成响应无效');
        }
        if (response.code !== PtpCodec.OK) {
          throw new PtpResponseError(response.code, PtpCodec.GET_OBJECT, transaction);
        }
        fs.fsyncSync(file.fd);
        const size = fs.statSync(file.fd).size;
        fs.closeSync(file);
        file = undefined;
        if (size !== photo.size) {
          throw new Error('已保存照片的大小校验失败');
        }
        fs.renameSync(partial, path);
      } catch (error) {
        // A failed stream may leave unread bytes. Never reuse it for another operation.
        this.release(generation);
        const failure = error as Error;
        throw failure;
      } finally {
        if (file !== undefined) {
          try { fs.closeSync(file); } catch (_) {}
        }
        try {
          if (fs.accessSync(partial)) {
            fs.unlinkSync(partial);
          }
        } catch (_) {}
      }
    });
  }

  disconnect(): Promise<void> {
    return this.serial(() => this.disconnectUnlocked());
  }

  /** Immediate lifecycle cancellation. A later connect must request a fresh generation. */
  abort(): void {
    this.lifecycleGeneration++;
    this.release();
  }

  private async disconnectUnlocked(): Promise<void> {
    const generation = this.activeGeneration;
    try {
      if (this.opened) {
        try {
          await this.restoreLocalShutter();
        } finally {
          if (this.pipe !== undefined) {
            await this.transact(PtpCodec.CLOSE_SESSION, this.nextTransaction(), []);
          }
        }
      }
    } finally {
      this.release(generation);
    }
  }

  private release(expectedGeneration?: number): void {
    if (expectedGeneration !== undefined && this.activeGeneration !== expectedGeneration) {
      return;
    }
    const pipe = this.pipe;
    const selected = this.selection;
    this.opened = false;
    this.pipe = undefined;
    this.selection = undefined;
    this.reader = undefined;
    this.deviceInfo = undefined;
    this.identity = '';
    this.reliableIdentity = false;
    this.nikonControlMode = false;
    this.nikonModeAttempted = false;
    this.nikonEventsDisabled = false;
    this.activeGeneration = -1;
    if (pipe !== undefined) {
      if (selected !== undefined) {
        try { usbManager.releaseInterface(pipe, selected.iface); } catch (_) {}
      }
      try { usbManager.closePipe(pipe); } catch (_) {}
    }
  }

  private async send(bytes: Uint8Array): Promise<void> {
    const generation = this.activeGeneration;
    const pipe = this.pipe;
    const selected = this.selection;
    if (pipe === undefined || selected === undefined) {
      throw new Error('USB 连接已关闭');
    }
    try {
      const count = await usbManager.bulkTransfer(pipe, selected.output, bytes, 5000);
      this.checkGeneration(generation);
      if (count !== bytes.length) {
        throw new Error('USB 指令发送不完整，请重新连接相机');
      }
    } catch (error) {
      this.release(generation);
      const failure = error as Error;
      throw failure;
    }
  }

  private async readChunk(): Promise<Uint8Array> {
    const generation = this.activeGeneration;
    const pipe = this.pipe;
    const selected = this.selection;
    if (pipe === undefined || selected === undefined) {
      throw new Error('USB 连接已关闭');
    }
    try {
      const buffer = new Uint8Array(64 * 1024);
      const count = await usbManager.bulkTransfer(pipe, selected.input, buffer, 5000);
      this.checkGeneration(generation);
      if (!Number.isInteger(count) || count < 0 || count > buffer.length) {
        throw new Error('USB 读取失败或相机响应超时');
      }
      return buffer.slice(0, count);
    } catch (error) {
      this.release(generation);
      const failure = error as Error;
      throw failure;
    }
  }

  private async transact(code: number, transaction: number, parameters: number[]): Promise<Uint8Array> {
    const generation = this.activeGeneration;
    try {
      await this.send(PtpCodec.command(code, transaction, parameters));
      const reader = this.reader;
      if (reader === undefined) {
        throw new Error('USB 连接已关闭');
      }
      let container = await reader.receive();
      let result = new Uint8Array(0);
      if (container.type === PtpCodec.DATA) {
        if (container.transaction !== transaction || container.code !== code) {
          throw new Error('PTP 数据事务或操作不匹配');
        }
        result = container.payload;
        container = await reader.receive();
      }
      if (container.type !== PtpCodec.RESPONSE || container.transaction !== transaction || container.size > 32) {
        throw new Error('PTP 响应事务或长度无效');
      }
      this.checkGeneration(generation);
      if (container.code !== PtpCodec.OK) {
        throw new PtpResponseError(container.code, code, transaction);
      }
      return result;
    } catch (error) {
      const failure = error as Error;
      if (!(failure instanceof PtpResponseError)) {
        this.release(generation);
      } else if (this.opened && (failure.responseCode === 0x2003 || failure.responseCode === 0x201e)) {
        this.release(generation);
      }
      throw failure;
    }
  }
}
