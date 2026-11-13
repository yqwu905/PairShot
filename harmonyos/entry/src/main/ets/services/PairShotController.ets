import { abilityAccessCtrl, common } from '@kit.AbilityKit';
import { pasteboard } from '@kit.BasicServicesKit';
import { CameraPhoto, CapturedPhoto, LensInfo, SessionSummary, ShotRecord, MAX_PHONE_LENSES } from '../model/Models';
import { CameraService } from './CameraService';
import { PtpCamera, UsbCameraInfo } from './PtpCamera';
import { SessionStore } from './SessionStore';

export class ViewState {
  busy: boolean = false;
  status: string = '正在准备';
  error: string = '';
  permissionGranted: boolean = false;
  lenses: LensInfo[] = [];
  selectedLensIds: string[] = [];
  previewLensId: string = '';
  previewAspectRatio: number = 3 / 4;
  previewRatios: number[] = [];
  previewReady: boolean = false;
  captureMode: string = 'CONCURRENT';
  concurrentAvailable: boolean = false;
  concurrentReason: string = '';
  cameraSummary: string = '';
  zoom: number = 1;
  minZoom: number = 1;
  maxZoom: number = 1;
  mode: string = 'PHONE_ONLY';
  usbDevices: UsbCameraInfo[] = [];
  cameraLabel: string = '';
  cameraConnected: boolean = false;
  linked: boolean = false;
  sessionId: string = '';
  shots: ShotRecord[] = [];
  history: SessionSummary[] = [];
  lastExport: string = '';
}

class PendingCameraGroup {
  key: string = '';
  photos: CameraPhoto[] = [];
  lastChangedAt: number = 0;
  shotId: string = '';
  ignored: boolean = false;
}

// Coordinates foreground hardware use. PTP calls are serialized by PtpCamera.
// A linkage generation invalidates outstanding scans when the user stops a link.
export class PairShotController {
  private readonly context: common.UIAbilityContext;
  private readonly onChange: (state: ViewState) => void;
  private readonly camera: CameraService;
  private readonly ptp: PtpCamera = new PtpCamera();
  private readonly store: SessionStore;
  private state: ViewState = new ViewState();
  private initialized: boolean = false;
  private suspended: boolean = false;
  private disposed: boolean = false;
  private surfaceId: string = '';
  private lensSurfaces: Map<string, string> = new Map<string, string>();
  private generation: number = 0;
  private linkGeneration: number = 0;
  private pollTimer: number = -1;
  private scanning: boolean = false;
  private seenHandles: Set<number> = new Set<number>();
  private groups: Map<string, PendingCameraGroup> = new Map<string, PendingCameraGroup>();
  private metadataFailures: Map<number, number> = new Map<number, number>();
  private pendingPhoneShotId: string = '';
  private pendingPhoneAt: number = 0;
  private pendingPhoneGroup: string = '';
  private connectionNumber: number = 0;
  private lifecycleStop: Promise<void> | undefined = undefined;
  private previewRestoreRequested: boolean = false;
  private foregroundRefreshRequested: boolean = false;
  // A camera without a serial number is usable for import only during the
  // exact connection in which the record was made. This map is never persisted.
  private unverifiedConnections: Map<string, number> = new Map<string, number>();

  constructor(context: common.UIAbilityContext, onChange: (state: ViewState) => void) {
    this.context = context;
    this.onChange = onChange;
    this.camera = new CameraService(context);
    this.store = new SessionStore(context);
    this.camera.onPreviewLayoutChanged = (ratio: number): void => {
      this.state.previewAspectRatio = ratio;
      this.state.previewRatios = this.state.selectedLensIds.map((id: string): number => this.camera.getPreviewAspectRatio(id));
      this.emit();
    };
    this.camera.onSessionError = (message: string): void => {
      this.state.previewReady = false;
      this.state.error = message;
      this.emit();
    };
  }

  getState(): ViewState {
    const result = new ViewState();
    result.busy = this.state.busy;
    result.status = this.state.status;
    result.error = this.state.error;
    result.permissionGranted = this.state.permissionGranted;
    result.lenses = this.state.lenses.slice();
    result.selectedLensIds = this.state.selectedLensIds.slice();
    result.previewLensId = this.state.previewLensId;
    result.previewAspectRatio = this.state.previewAspectRatio;
    result.previewRatios = this.state.previewRatios.slice();
    result.previewReady = this.state.previewReady;
    result.captureMode = this.state.captureMode;
    result.concurrentAvailable = this.state.concurrentAvailable;
    result.concurrentReason = this.state.concurrentReason;
    result.cameraSummary = this.state.cameraSummary;
    result.zoom = this.state.zoom;
    result.minZoom = this.state.minZoom;
    result.maxZoom = this.state.maxZoom;
    result.mode = this.state.mode;
    result.usbDevices = this.state.usbDevices.slice();
    result.cameraLabel = this.state.cameraLabel;
    result.cameraConnected = this.state.cameraConnected;
    result.linked = this.state.linked;
    result.sessionId = this.state.sessionId;
    result.shots = this.state.shots.slice();
    result.history = this.state.history.slice();
    result.lastExport = this.state.lastExport;
    return result;
  }

  private emit(): void {
    if (this.initialized) {
      this.state.sessionId = this.store.getCurrent().id;
      this.state.shots = this.store.getCurrent().shots.slice();
    }
    this.state.cameraConnected = this.ptp.isConnected();
    if (!this.disposed) {
      this.onChange(this.getState());
    }
  }

  private errorText(error: Error): string {
    return error.message || String(error);
  }

  private async run(action: () => Promise<void>): Promise<void> {
    if (this.disposed || this.state.busy) {
      return;
    }
    this.state.busy = true;
    this.state.error = '';
    this.emit();
    try {
      await action();
    } catch (error) {
      this.state.error = this.errorText(error as Error);
      this.state.status = '操作未完成，请查看提示';
    } finally {
      this.state.busy = false;
      this.emit();
      if ((this.previewRestoreRequested || this.foregroundRefreshRequested) && !this.suspended && !this.disposed) {
        const refresh = this.foregroundRefreshRequested;
        this.previewRestoreRequested = false;
        this.foregroundRefreshRequested = false;
        if (refresh) {
          await this.resume();
        } else {
          await this.run(async () => this.restorePreview());
        }
      }
    }
  }

  async initialize(): Promise<void> {
    await this.run(async () => {
      if (this.initialized) {
        return;
      }
      await this.store.initialize();
      this.initialized = true;
      this.state.history = this.store.listSessions();
      this.refreshUsbInternal();
      await this.requestCameraPermission();
      this.state.status = this.state.permissionGranted ? '选择镜头；多镜头默认使用系统支持的并发拍摄' : '请授权摄像头以使用手机拍摄';
    });
  }

  private async requestCameraPermission(): Promise<void> {
    const manager = abilityAccessCtrl.createAtManager();
    const result = await manager.requestPermissionsFromUser(this.context, ['ohos.permission.CAMERA']);
    this.state.permissionGranted = result.authResults.length > 0 && result.authResults[0] === 0;
    if (!this.state.permissionGranted) {
      this.state.lenses = [];
      this.state.selectedLensIds = [];
      return;
    }
    this.state.lenses = this.camera.listCameras();
    this.state.cameraSummary = this.camera.getDiscoverySummary();
    this.state.selectedLensIds = this.state.selectedLensIds.filter((id: string) =>
      this.state.lenses.some((lens: LensInfo) => lens.id === id));
    if (this.state.selectedLensIds.length === 0 && this.state.lenses.length > 0) {
      this.state.selectedLensIds = [this.state.lenses[0].id];
    }
    if (this.state.lenses.length === 0) {
      this.state.status = '系统没有向应用开放可用摄像头';
    }
    await this.restorePreview();
  }

  async grantPermission(): Promise<void> {
    await this.run(async () => {
      await this.requestCameraPermission();
      this.state.status = this.state.permissionGranted ? '摄像头已授权' : '未获得摄像头授权，可在系统设置中开启';
    });
  }

  async attachSurface(surfaceId: string): Promise<void> {
    this.surfaceId = surfaceId;
    if (this.state.selectedLensIds.length > 0) {
      this.lensSurfaces.set(this.state.selectedLensIds[0], surfaceId);
    }
    if (this.state.busy && !this.suspended) {
      this.previewRestoreRequested = true;
    }
    if (!this.initialized || this.state.busy || this.suspended) {
      return;
    }
    await this.run(async () => this.restorePreview());
  }

  async attachLensSurface(lensId: string, surfaceId: string): Promise<void> {
    if (this.state.selectedLensIds.indexOf(lensId) < 0 || !surfaceId) {
      return;
    }
    this.lensSurfaces.set(lensId, surfaceId);
    if (lensId === this.state.selectedLensIds[0]) {
      this.surfaceId = surfaceId;
    }
    if (this.state.busy && !this.suspended) {
      this.previewRestoreRequested = true;
      return;
    }
    if (this.initialized && !this.suspended) {
      await this.run(async () => this.restorePreview());
    }
  }

  detachLensSurface(lensId: string, surfaceId: string = ''): void {
    if (surfaceId && this.lensSurfaces.get(lensId) !== surfaceId) {
      return;
    }
    this.lensSurfaces.delete(lensId);
    if (this.state.selectedLensIds.indexOf(lensId) >= 0) {
      this.state.previewReady = false;
      if (lensId === this.state.selectedLensIds[0]) {
        this.surfaceId = '';
      }
      this.camera.stop().catch((error: Error): void => {
        this.state.error = error.message;
        this.emit();
      });
      this.state.status = '预览画面已释放，等待重新创建';
      this.emit();
    }
  }

  async refreshCameras(): Promise<void> {
    await this.run(async () => {
      this.requireNoPending();
      if (!this.state.permissionGranted) {
        throw new Error('请先授权摄像头');
      }
      await this.camera.stop();
      this.state.lenses = this.camera.listCameras();
      this.state.cameraSummary = this.camera.getDiscoverySummary();
      this.state.selectedLensIds = this.state.selectedLensIds.filter((id: string): boolean =>
        this.state.lenses.some((lens: LensInfo): boolean => lens.id === id));
      if (this.state.selectedLensIds.length === 0 && this.state.lenses.length > 0) {
        this.state.selectedLensIds = [this.state.lenses[0].id];
      }
      this.emit();
      await this.restorePreview();
    });
  }

  async copyCameraDiagnostics(): Promise<void> {
    await this.run(async () => {
      const report = this.camera.getDiagnosticReport() + '\n拍摄模式：' + this.state.captureMode +
        '\n所选镜头：' + this.state.selectedLensIds.join(', ') + '\n当前并发结果：' +
        (this.state.concurrentReason || '能力查询通过或单镜头');
      pasteboard.getSystemPasteboard().setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, report));
      this.state.status = '镜头诊断已复制，可用于检查物理镜头枚举、过滤和并发能力';
    });
  }

  async setCaptureMode(mode: string): Promise<void> {
    await this.run(async () => {
      this.requireNoPending();
      if (this.state.linked) {
        throw new Error('请先停止联动再切换多镜头拍摄方式');
      }
      if (mode !== 'CONCURRENT' && mode !== 'SEQUENTIAL') {
        throw new Error('未知的多镜头拍摄方式');
      }
      this.state.captureMode = mode;
      this.state.previewReady = false;
      await this.restorePreview();
    });
  }

  private async restorePreview(): Promise<void> {
    this.state.previewReady = false;
    if (this.suspended || this.disposed || !this.state.permissionGranted ||
      this.state.selectedLensIds.length === 0) {
      return;
    }
    const ids = this.state.selectedLensIds.slice();
    const id = ids[0];
    const firstSurface = this.lensSurfaces.get(id) || this.surfaceId;
    this.state.concurrentReason = ids.length > 1 ? this.camera.getConcurrentSupport(ids) : '';
    this.state.concurrentAvailable = ids.length > 1 && this.state.concurrentReason.length === 0;
    const concurrent = ids.length > 1 && this.state.captureMode === 'CONCURRENT';
    if (concurrent && !this.state.concurrentAvailable) {
      if (firstSurface) {
        await this.camera.startPreview(id, firstSurface);
      }
      this.state.error = this.state.concurrentReason;
      this.state.status = '当前组合无法并发拍照，可更换镜头组合或手动选择顺序拍摄';
      return;
    }
    const surfaces = ids.map((lens: string): string => this.lensSurfaces.get(lens) || (lens === id ? firstSurface : ''));
    if (!firstSurface || (concurrent && surfaces.some((surface: string): boolean => !surface))) {
      this.state.status = '正在等待镜头预览画面创建';
      return;
    }
    if (concurrent) {
      await this.camera.startConcurrent(ids, surfaces);
    } else {
      await this.camera.startPreview(id, firstSurface);
    }
    if (this.suspended || this.disposed) {
      await this.camera.stop();
      return;
    }
    this.state.previewLensId = id;
    this.state.previewAspectRatio = this.camera.getPreviewAspectRatio();
    this.state.previewRatios = ids.map((lens: string): number => this.camera.getPreviewAspectRatio(lens));
    const range = this.camera.getZoomRange();
    this.state.minZoom = range.length > 0 ? range[0] : 1;
    this.state.maxZoom = range.length > 1 ? range[1] : this.state.minZoom;
    this.state.zoom = this.camera.setZoom(Math.min(this.state.maxZoom, Math.max(this.state.minZoom, this.state.zoom)));
    const activeRange = this.camera.getZoomRange();
    this.state.minZoom = activeRange[0];
    this.state.maxZoom = activeRange[1];
    this.state.previewReady = true;
    this.state.status = concurrent ? `${ids.length} 路并发会话就绪；快门会并发提交` :
      (ids.length > 1 ? '顺序拍摄就绪；镜头会依次曝光' : '单镜头已就绪');
  }

  async selectLens(id: string): Promise<void> {
    await this.run(async () => {
      this.requireNoPending();
      if (!this.state.lenses.some((lens: LensInfo) => lens.id === id)) {
        throw new Error('这枚镜头已不可用，请重新授权或返回前台刷新');
      }
      const current = this.state.selectedLensIds;
      if (current.indexOf(id) >= 0) {
        this.state.selectedLensIds = current.filter((value: string) => value !== id);
      } else {
        if (current.length >= MAX_PHONE_LENSES) {
          throw new Error(`最多选择 ${MAX_PHONE_LENSES} 枚镜头，请先取消一枚`);
        }
        this.state.selectedLensIds = current.concat([id]);
      }
      this.state.zoom = 1;
      this.state.previewReady = false;
      this.surfaceId = this.lensSurfaces.get(this.state.selectedLensIds[0]) || '';
      await this.camera.stop();
      this.emit();
      if (this.state.selectedLensIds.length === 0) {
        await this.camera.stop();
        this.state.previewLensId = '';
        this.state.status = '请选择至少一枚手机镜头';
      } else {
        await this.restorePreview();
      }
    });
  }

  async setZoom(value: number): Promise<void> {
    await this.run(async () => {
      if (!Number.isFinite(value)) {
        throw new Error('变焦倍率无效');
      }
      this.state.zoom = this.camera.setZoom(Math.min(this.state.maxZoom, Math.max(this.state.minZoom, value)));
      const range = this.camera.getZoomRange();
      this.state.minZoom = range[0];
      this.state.maxZoom = range[1];
    });
  }

  async setMode(mode: string): Promise<void> {
    await this.run(async () => {
      if (mode !== 'PHONE_ONLY' && mode !== 'PHONE_TO_CAMERA' && mode !== 'CAMERA_TO_PHONE') {
        throw new Error('未知的拍摄模式');
      }
      this.stopLinkInternal('切换模式后需要重新开启联动');
      this.state.mode = mode;
      if (this.ptp.isConnected()) {
        await this.ptp.useLocalShutter();
      }
      this.state.status = mode === 'PHONE_ONLY' ? '仅手机模式，无需连接外部相机' : '连接 USB 相机后开启联动';
    });
  }

  private refreshUsbInternal(): void {
    try {
      this.state.usbDevices = this.ptp.listDevices();
    } catch (error) {
      this.state.usbDevices = [];
      if (this.state.mode !== 'PHONE_ONLY') {
        this.state.error = 'USB 枚举不可用：' + this.errorText(error as Error);
      }
    }
  }

  async refreshUsb(): Promise<void> {
    await this.run(async () => {
      this.refreshUsbInternal();
      if (this.state.usbDevices.length === 0) {
        this.state.status = '未发现 PTP 相机，请检查 OTG 数据线、相机电源与 USB 模式';
      }
    });
  }

  async connectUsb(deviceName: string): Promise<void> {
    await this.run(async () => {
      this.stopLinkInternal('正在连接相机');
      await this.ptp.disconnect();
      this.connectionNumber += 1;
      this.state.cameraLabel = '';
      this.state.cameraLabel = await this.ptp.connect(deviceName);
      this.state.status = '相机已连接；开启联动会记录存储卡基线';
    });
  }

  async disconnectUsb(): Promise<void> {
    await this.run(async () => {
      this.stopLinkInternal('联动已停止');
      this.connectionNumber += 1;
      await this.ptp.disconnect();
      this.state.cameraLabel = '';
      this.state.status = '相机已断开，已保存的照片和记录仍然保留';
    });
  }

  private requirePhone(): void {
    if (this.suspended || this.disposed) {
      throw new Error('应用不在前台，拍摄已停止');
    }
    if (!this.state.permissionGranted) {
      throw new Error('请先授权手机摄像头');
    }
    if (this.state.selectedLensIds.length === 0 || this.surfaceId.length === 0) {
      throw new Error('请选择镜头并等待预览就绪');
    }
    if (this.state.selectedLensIds.length > 1 && this.state.captureMode === 'CONCURRENT' &&
      (!this.state.previewReady || !this.camera.isConcurrentReady(this.state.selectedLensIds))) {
      throw new Error(this.state.concurrentReason || '请等待全部并发镜头就绪，未发出快门');
    }
  }

  private requireNoPending(): void {
    if (this.pendingPhoneShotId.length > 0) {
      throw new Error('上一组仍在等待相机照片；请等待或停止联动');
    }
  }

  async startLink(): Promise<void> {
    await this.run(async () => {
      this.requirePhone();
      if (this.state.mode === 'PHONE_ONLY') {
        throw new Error('仅手机模式不需要开启联动');
      }
      if (!this.ptp.isConnected()) {
        throw new Error('请先连接并授权 USB 相机');
      }
      if (this.state.mode === 'PHONE_TO_CAMERA' && !this.ptp.supportsCapture()) {
        throw new Error('相机未声明支持已实现的远程快门，可尝试相机 → 手机模式');
      }
      this.stopLinkInternal('正在记录存储卡基线，请勿拍摄');
      const linkToken = this.linkGeneration;
      this.emit();
      await this.ptp.useLocalShutter();
      if (linkToken !== this.linkGeneration) {
        return;
      }
      const token = this.generation;
      const baseline = await this.ptp.listPhotoHandles();
      this.checkActive(token);
      if (linkToken !== this.linkGeneration) {
        return;
      }
      this.seenHandles = new Set<number>(baseline);
      this.groups.clear();
      this.metadataFailures.clear();
      this.state.linked = true;
      this.linkGeneration += 1;
      this.state.status = this.state.mode === 'PHONE_TO_CAMERA' ?
        '联动已开启；点击快门同时提交手机与相机拍摄请求' :
        '联动已开启；相机生成照片后，手机会延迟跟拍';
      this.schedulePoll();
    });
  }

  private stopLinkInternal(message: string): void {
    this.state.linked = false;
    this.linkGeneration += 1;
    if (this.pollTimer >= 0) {
      clearTimeout(this.pollTimer);
      this.pollTimer = -1;
    }
    if (this.pendingPhoneShotId && this.initialized) {
      const shot = this.findShot(this.pendingPhoneShotId);
      if (shot) {
        shot.status = this.store.isComplete(shot) ? 'complete' : 'incomplete';
        if (shot.cameraPhotos.length === 0 && !shot.error) {
          shot.error = '等待相机照片时联动被停止';
        }
        this.store.save();
      }
    }
    this.pendingPhoneShotId = '';
    this.pendingPhoneAt = 0;
    this.pendingPhoneGroup = '';
    this.state.status = message;
  }

  async stopLink(): Promise<void> {
    // Stopping is allowed while a read or capture is busy; it never retries a shutter.
    try {
      this.stopLinkInternal('联动已停止');
      if (this.ptp.isConnected()) {
        await this.ptp.useLocalShutter();
      }
    } catch (error) {
      this.state.error = this.errorText(error as Error);
    }
    this.emit();
  }

  private checkActive(token: number): void {
    if (this.disposed || this.suspended || token !== this.generation) {
      throw new Error('拍摄因离开前台或页面关闭而停止；已保存照片保留');
    }
  }

  private async capturePhones(shot: ShotRecord, token: number): Promise<string> {
    const requestedZoom = this.state.zoom;
    try {
      shot.phoneCaptureMode = shot.expectedLensIds.length > 1 ? this.state.captureMode : 'SINGLE';
      this.store.save();
      if (shot.expectedLensIds.length > 1 && this.state.captureMode === 'CONCURRENT') {
        this.checkActive(token);
        if (shot.mode !== 'PHONE_ONLY' && !this.state.linked) {
          throw new Error('联动已停止，未继续拍摄');
        }
        this.state.status = `正在并发拍摄 ${shot.expectedLensIds.length} 枚镜头`;
        this.emit();
        const failures = await this.camera.captureConcurrent(async (photo: CapturedPhoto): Promise<void> => {
          await this.store.addPhonePhoto(shot, photo);
          this.emit();
        });
        return failures.join('；');
      }
      for (let i = 0; i < shot.expectedLensIds.length; i++) {
        this.checkActive(token);
        if (shot.mode !== 'PHONE_ONLY' && !this.state.linked) {
          throw new Error('联动已停止，后续手机镜头未继续拍摄');
        }
        const id = shot.expectedLensIds[i];
        this.state.status = '正在拍摄手机镜头 ' + (i + 1).toString() + '/' + shot.expectedLensIds.length.toString();
        this.emit();
        await this.camera.startPreview(id, this.lensSurfaces.get(id) || this.surfaceId);
        this.state.previewLensId = id;
        this.state.previewAspectRatio = this.camera.getPreviewAspectRatio();
        this.emit();
        this.checkActive(token);
        const range = this.camera.getZoomRange();
        const min = range.length > 0 ? range[0] : 1;
        const max = range.length > 1 ? range[1] : min;
        this.camera.setZoom(Math.max(min, Math.min(max, requestedZoom)));
        const photo = await this.camera.capture();
        photo.captureMode = shot.expectedLensIds.length > 1 ? 'SEQUENTIAL' : 'SINGLE';
        // Preserve an already exposed photograph even if backgrounding happens
        // just after the camera's completion callback.
        await this.store.addPhonePhoto(shot, photo);
        this.emit();
      }
      return '';
    } catch (error) {
      return this.errorText(error as Error);
    } finally {
      if (!this.suspended && !this.disposed && token === this.generation) {
        try {
          await this.restorePreview();
        } catch (error) {
          this.state.error = '照片已处理，但预览恢复失败：' + this.errorText(error as Error);
        }
      }
    }
  }

  private registerShotConnection(shot: ShotRecord): void {
    if (!this.ptp.hasReliableIdentity()) {
      this.unverifiedConnections.set(shot.id, this.connectionNumber);
    }
  }

  private async requestCameraCapture(): Promise<string> {
    try {
      await this.ptp.capture();
      return '';
    } catch (error) {
      return this.errorText(error as Error);
    }
  }

  async capture(): Promise<void> {
    await this.run(async () => {
      this.requirePhone();
      this.requireNoPending();
      if (this.state.mode === 'CAMERA_TO_PHONE') {
        throw new Error('此模式请在相机上按快门，或切换为手机 → 相机');
      }
      const phoneOnly = this.state.mode === 'PHONE_ONLY';
      if (!phoneOnly && (!this.state.linked || !this.ptp.isConnected())) {
        throw new Error('请先连接相机并开启联动');
      }
      if (!phoneOnly && this.scanning) {
        throw new Error('正在核对相机文件，请稍后再次点击快门');
      }
      const token = this.generation;
      const shot = this.store.createShot(this.state.mode, this.state.selectedLensIds.slice(), phoneOnly ? '' : this.ptp.getIdentity());
      if (phoneOnly) {
        const failure = await this.capturePhones(shot, token);
        shot.error = failure;
        shot.status = this.store.isComplete(shot) ? 'complete' : 'incomplete';
        this.store.save();
        this.state.history = this.store.listSessions();
        this.state.status = failure ? '部分镜头未完成，已成功拍摄的照片已保存' : '手机照片已保存，可导出 ZIP';
        this.state.error = failure;
        return;
      }
      this.registerShotConnection(shot);
      this.pendingPhoneShotId = shot.id;
      this.pendingPhoneAt = Date.now();
      this.pendingPhoneGroup = '';
      const results: string[] = await Promise.all([
        this.requestCameraCapture(),
        this.capturePhones(shot, token)
      ]);
      shot.error = results.filter((message: string) => message.length > 0).join('；') || shot.error;
      if (this.state.linked) {
        shot.status = 'pending';
        this.state.status = '手机照片已处理，正在等待相机原片（最多 45 秒）';
      } else {
        shot.status = this.store.isComplete(shot) ? 'complete' : 'incomplete';
      }
      this.store.save();
      this.state.error = shot.error;
      this.state.history = this.store.listSessions();
    });
  }

  private schedulePoll(): void {
    if (!this.state.linked || this.suspended || this.disposed || this.pollTimer >= 0) {
      return;
    }
    this.pollTimer = setTimeout(() => {
      this.pollTimer = -1;
      this.poll().catch((error: Error) => {
        // Last-resort timer boundary: even a full disk while recording the
        // original failure must not leave a rejected background Promise.
        this.state.linked = false;
        this.linkGeneration += 1;
        if (this.pollTimer >= 0) {
          clearTimeout(this.pollTimer);
          this.pollTimer = -1;
        }
        this.pendingPhoneShotId = '';
        this.pendingPhoneAt = 0;
        this.pendingPhoneGroup = '';
        this.state.status = '后台检查已停止，请检查设备和剩余空间';
        this.state.error = this.errorText(error);
        this.emit();
      });
    }, 1500);
  }

  private findShot(id: string): ShotRecord | undefined {
    return this.store.getCurrent().shots.find((shot: ShotRecord) => shot.id === id);
  }

  private groupKey(photo: CameraPhoto): string {
    const dot = photo.filename.lastIndexOf('.');
    const stem = (dot > 0 ? photo.filename.substring(0, dot) : photo.filename).toUpperCase();
    return photo.storage.toString() + '/' + photo.parent.toString() + '/' + stem;
  }

  private recordPhoto(photo: CameraPhoto): void {
    const key = this.groupKey(photo);
    let group = this.groups.get(key);
    if (!group) {
      group = new PendingCameraGroup();
      group.key = key;
      this.groups.set(key, group);
    }
    if (group.photos.some((existing: CameraPhoto) => existing.handle === photo.handle)) {
      return;
    }
    group.photos.push(photo);
    group.lastChangedAt = Date.now();
    if (group.shotId) {
      const shot = this.findShot(group.shotId);
      if (shot) {
        shot.cameraPhotos.push(photo);
        shot.status = 'incomplete';
        this.store.save();
      }
      return;
    }
    if (this.state.mode === 'PHONE_TO_CAMERA') {
      if (this.pendingPhoneShotId && (!this.pendingPhoneGroup || this.pendingPhoneGroup === key)) {
        const shot = this.findShot(this.pendingPhoneShotId);
        if (shot) {
          this.pendingPhoneGroup = key;
          group.shotId = shot.id;
          shot.cameraPhotos = group.photos.slice();
          this.store.save();
        }
      } else {
        group.ignored = true;
        this.state.error = '检测到未配对的相机照片；手机触发模式下请勿同时在相机上拍摄';
      }
    }
  }

  private async poll(): Promise<void> {
    if (!this.state.linked || this.suspended || this.disposed || this.scanning) {
      return;
    }
    if (this.state.busy) {
      this.schedulePoll();
      return;
    }
    this.scanning = true;
    const linkToken = this.linkGeneration;
    try {
      const handles = await this.ptp.listPhotoHandles();
      for (let i = 0; i < handles.length; i++) {
        if (linkToken !== this.linkGeneration || !this.state.linked) {
          return;
        }
        const handle = handles[i];
        if (this.seenHandles.has(handle)) {
          continue;
        }
        try {
          const photo = await this.ptp.getPhotoInfo(handle);
          if (linkToken !== this.linkGeneration || !this.state.linked) {
            return;
          }
          this.seenHandles.add(handle);
          this.metadataFailures.delete(handle);
          this.recordPhoto(photo);
        } catch (error) {
          if (this.errorText(error as Error) === '该对象不是可导入的照片') {
            this.seenHandles.add(handle);
            continue;
          }
          const attempts = (this.metadataFailures.get(handle) || 0) + 1;
          this.metadataFailures.set(handle, attempts);
          if (attempts >= 3) {
            this.seenHandles.add(handle);
            this.state.error = '部分相机文件无法识别或读取：' + this.errorText(error as Error);
          }
        }
      }
      if (linkToken !== this.linkGeneration || !this.state.linked) {
        return;
      }
      await this.finishQuietGroups(linkToken);
      await this.expirePendingCapture();
      this.emit();
    } catch (error) {
      if (linkToken === this.linkGeneration) {
        const readFailure = this.errorText(error as Error);
        try {
          this.stopLinkInternal('相机读取失败，联动已停止');
          this.state.error = readFailure;
        } catch (saveError) {
          // stopLinkInternal cancels scheduling before attempting disk I/O.
          this.pendingPhoneShotId = '';
          this.pendingPhoneAt = 0;
          this.pendingPhoneGroup = '';
          this.state.status = '联动已停止；记录保存失败，请检查剩余空间';
          this.state.error = readFailure + '；保存记录失败：' + this.errorText(saveError as Error);
        }
        try {
          await this.ptp.disconnect();
        } catch (_) {
          // The original read error is more useful than a disconnect error.
        }
        this.connectionNumber += 1;
        this.emit();
      }
    } finally {
      this.scanning = false;
      this.schedulePoll();
    }
  }

  private async finishQuietGroups(linkToken: number): Promise<void> {
    const ready: PendingCameraGroup[] = [];
    this.groups.forEach((group: PendingCameraGroup) => {
      if (!group.ignored && Date.now() - group.lastChangedAt >= 3000) {
        ready.push(group);
      }
    });
    for (let i = 0; i < ready.length; i++) {
      if (linkToken !== this.linkGeneration || !this.state.linked || this.suspended) {
        return;
      }
      const group = ready[i];
      if (this.state.mode === 'PHONE_TO_CAMERA') {
        if (group.shotId && group.shotId === this.pendingPhoneShotId) {
          const shot = this.findShot(group.shotId);
          if (shot) {
            shot.status = 'incomplete';
            this.store.save();
          }
          this.pendingPhoneShotId = '';
          this.pendingPhoneAt = 0;
          this.pendingPhoneGroup = '';
          this.state.status = '本组照片已配对，点击“导入相机原片”保存相机文件';
        }
      } else if (this.state.mode === 'CAMERA_TO_PHONE' && !group.shotId && !this.state.busy) {
        this.requirePhone();
        const shot = this.store.createShot('CAMERA_TO_PHONE', this.state.selectedLensIds.slice(), this.ptp.getIdentity());
        this.registerShotConnection(shot);
        group.shotId = shot.id;
        shot.cameraPhotos = group.photos.slice();
        this.store.save();
        this.state.busy = true;
        this.emit();
        try {
          shot.error = await this.capturePhones(shot, this.generation);
          shot.status = 'incomplete';
          this.store.save();
          this.state.error = shot.error;
          this.state.status = shot.error ? '相机照片已记录，但手机跟拍未完成' : '已延迟跟拍，待导入相机原片';
          this.state.history = this.store.listSessions();
        } finally {
          this.state.busy = false;
          this.emit();
        }
      }
    }
  }

  private async expirePendingCapture(): Promise<void> {
    if (!this.pendingPhoneShotId || Date.now() - this.pendingPhoneAt < 45000) {
      return;
    }
    const shot = this.findShot(this.pendingPhoneShotId);
    if (shot) {
      shot.status = 'incomplete';
      shot.error = shot.error ? shot.error + '；等待相机文件超时' : '45 秒内未收到可配对的相机照片';
      this.store.save();
      this.state.error = shot.error;
    }
    // Requiring a new baseline after a timeout prevents a late file from being
    // incorrectly assigned to the next software shutter request.
    this.stopLinkInternal('等待相机文件超时，联动已停止；重新开启前请等待相机写卡完成');
    try {
      if (this.ptp.isConnected()) {
        await this.ptp.useLocalShutter();
      }
    } catch (error) {
      this.state.error += '；恢复相机本地快门失败：' + this.errorText(error as Error);
    }
  }

  async importPhotos(): Promise<void> {
    await this.run(async () => {
      this.requireNoPending();
      this.stopLinkInternal('正在导入相机原片，请勿拔线');
      if (!this.ptp.isConnected()) {
        throw new Error('请先重新连接拍摄时使用的相机');
      }
      await this.ptp.useLocalShutter();
      const identity = this.ptp.getIdentity();
      const reliable = this.ptp.hasReliableIdentity();
      const shots = this.store.getCurrent().shots;
      let imported = 0;
      let failed = 0;
      const token = this.generation;
      for (let i = 0; i < shots.length; i++) {
        const shot = shots[i];
        if (shot.mode === 'PHONE_ONLY' || shot.cameraPhotos.length === 0) {
          continue;
        }
        for (let j = 0; j < shot.cameraPhotos.length; j++) {
          const photo = shot.cameraPhotos[j];
          if (photo.relativePath && photo.sha256 && !photo.importError) {
            try {
              await this.store.verifyCameraPhoto(shot, photo);
              continue;
            } catch (_) {
              // Re-download missing or corrupt files using the original camera
              // metadata; never bless local damage by recomputing its digest.
            }
          }
          try {
            this.checkActive(token);
            if (!shot.cameraIdentity || shot.cameraIdentity !== identity) {
              throw new Error('当前相机身份与拍摄记录不一致，拒绝使用旧文件句柄');
            }
            if (!reliable && this.unverifiedConnections.get(shot.id) !== this.connectionNumber) {
              throw new Error('相机没有可靠序列号，断开或重启后无法安全复用旧文件句柄');
            }
            this.state.status = '正在导入 ' + photo.filename;
            this.emit();
            const path = this.store.getCameraPath(shot, photo);
            await this.ptp.downloadPhoto(photo, path);
            await this.store.commitCameraPhoto(shot, photo);
            photo.importError = '';
            imported += 1;
          } catch (error) {
            photo.importError = this.errorText(error as Error);
            failed += 1;
          }
          this.store.save();
          this.emit();
          if (this.suspended || token !== this.generation) {
            break;
          }
        }
        shot.status = this.store.isComplete(shot) ? 'complete' : 'incomplete';
        this.store.save();
        if (this.suspended || token !== this.generation) {
          break;
        }
      }
      this.state.status = '已导入 ' + imported.toString() + ' 个相机文件' + (failed > 0 ? '，失败 ' + failed.toString() + ' 个，可重试' : '');
      if (failed > 0) {
        this.state.error = '部分原片未导入成功，请查看拍摄记录中的错误';
      }
    });
  }

  async exportZip(allowIncomplete: boolean): Promise<void> {
    await this.run(async () => {
      this.stopLinkInternal('正在核验文件并导出 ZIP');
      if (this.ptp.isConnected()) {
        await this.ptp.useLocalShutter();
      }
      this.state.lastExport = await this.store.exportZip(allowIncomplete);
      this.state.status = 'ZIP 已保存到所选位置';
    });
  }

  async newSession(): Promise<void> {
    await this.run(async () => {
      this.stopLinkInternal('已创建新记录');
      if (this.ptp.isConnected()) {
        await this.ptp.useLocalShutter();
      }
      this.store.newSession();
      this.groups.clear();
      this.state.history = this.store.listSessions();
      this.state.lastExport = '';
    });
  }

  async loadSession(id: string): Promise<void> {
    await this.run(async () => {
      this.stopLinkInternal('已打开历史记录');
      if (this.ptp.isConnected()) {
        await this.ptp.useLocalShutter();
      }
      this.store.loadSession(id);
      this.groups.clear();
      this.state.history = this.store.listSessions();
      this.state.lastExport = '';
    });
  }

  private async stopResources(): Promise<void> {
    try {
      this.stopLinkInternal('已离开前台；联动和摄像头已停止');
    } catch (error) {
      this.state.error = this.errorText(error as Error);
    }
    try {
      await this.camera.stop();
    } catch (error) {
      this.state.error = this.errorText(error as Error);
    }
    try {
      await this.ptp.disconnect();
    } catch (error) {
      this.state.error = this.errorText(error as Error);
    }
    this.state.previewLensId = '';
    this.state.previewReady = false;
    this.state.cameraLabel = '';
    this.emit();
  }

  async suspend(): Promise<void> {
    this.suspended = true;
    this.previewRestoreRequested = false;
    this.foregroundRefreshRequested = false;
    this.generation += 1;
    this.connectionNumber += 1;
    if (this.state.busy || this.scanning) {
      try {
        this.ptp.abort();
      } catch (error) {
        this.state.error = this.errorText(error as Error);
      }
    }
    if (this.lifecycleStop) {
      await this.lifecycleStop;
      return;
    }
    this.lifecycleStop = this.stopResources();
    await this.lifecycleStop;
    this.lifecycleStop = undefined;
  }

  async resume(): Promise<void> {
    if (this.disposed) {
      return;
    }
    const token = this.generation;
    if (this.lifecycleStop) {
      await this.lifecycleStop;
    }
    if (this.disposed || token !== this.generation) {
      return;
    }
    this.suspended = false;
    if (!this.initialized) {
      return;
    }
    if (this.state.busy) {
      this.foregroundRefreshRequested = true;
      return;
    }
    await this.run(async () => {
      await this.camera.stop();
      this.state.previewReady = false;
      const manager = abilityAccessCtrl.createAtManager();
      const granted = await manager.checkAccessToken(this.context.applicationInfo.accessTokenId, 'ohos.permission.CAMERA');
      this.state.permissionGranted = granted === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
      if (this.state.permissionGranted) {
        this.state.lenses = this.camera.listCameras();
        this.state.cameraSummary = this.camera.getDiscoverySummary();
        this.state.selectedLensIds = this.state.selectedLensIds.filter((id: string) =>
          this.state.lenses.some((lens: LensInfo) => lens.id === id));
        if (this.state.selectedLensIds.length === 0 && this.state.lenses.length > 0) {
          this.state.selectedLensIds = [this.state.lenses[0].id];
        }
        await this.restorePreview();
      }
      this.refreshUsbInternal();
      if (!this.state.permissionGranted) {
        this.state.status = '请重新授权摄像头';
      } else if (this.state.mode !== 'PHONE_ONLY') {
        this.state.status += '；如需 USB 联动，请重新连接相机并开启';
      }
    });
  }

  async dispose(): Promise<void> {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    await this.suspend();
  }
}
