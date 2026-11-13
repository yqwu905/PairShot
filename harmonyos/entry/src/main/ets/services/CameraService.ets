import { common } from '@kit.AbilityKit';
import { camera } from '@kit.CameraKit';
import { CapturedPhoto, LensInfo } from '../model/Models';
import { CameraCatalog, ConcurrentDevicePlan } from './CameraCatalog';
import { CameraChannel } from './CameraChannel';

/** Discovers actual CameraDevice objects and manages independent live camera channels. */
export class CameraService {
  onPreviewLayoutChanged: ((ratio: number) => void) | undefined = undefined;
  onSessionError: ((message: string) => void) | undefined = undefined;
  private manager: camera.CameraManager;
  private catalog: CameraCatalog;
  private catalogReady: boolean = false;
  private channels: CameraChannel[] = [];
  private lensIds: string[] = [];
  private surfaces: string[] = [];
  private lifecycle: Promise<void> = Promise.resolve();
  private lastStop: Promise<void> = Promise.resolve();
  private generation: number = 0;
  private groupReady: boolean = false;
  private captureInProgress: boolean = false;

  constructor(context: common.UIAbilityContext) {
    this.manager = camera.getCameraManager(context);
    this.catalog = new CameraCatalog(this.manager);
  }

  listCameras(): LensInfo[] {
    const lenses = this.catalog.refresh();
    this.catalogReady = true;
    return lenses;
  }

  getDiscoverySummary(): string {
    return this.catalog.summary();
  }

  getDiagnosticReport(): string {
    return this.catalog.report();
  }

  getConcurrentSupport(lensIds: string[]): string {
    if (lensIds.length < 2) {
      return '';
    }
    try {
      this.ensureCatalog();
      this.catalog.planConcurrent(lensIds);
      return '';
    } catch (error) {
      const reason = (error as Error).message || String(error);
      this.catalog.record('并发不可用：' + reason);
      return reason;
    }
  }

  private ensureCatalog(): void {
    if (!this.catalogReady) {
      this.listCameras();
    }
  }

  getCurrentLensId(): string {
    return this.channels.length > 0 ? this.channels[0].getCurrentLensId() : '';
  }

  getPreviewAspectRatio(lensId: string = ''): number {
    const index = lensId ? this.lensIds.indexOf(lensId) : 0;
    return index >= 0 && this.channels[index] ? this.channels[index].getPreviewAspectRatio() : 3 / 4;
  }

  getZoomRange(): number[] {
    if (this.channels.length === 0) {
      return [1, 1];
    }
    let min = 0;
    let max = Number.MAX_VALUE;
    for (const channel of this.channels) {
      const range = channel.getZoomRange();
      min = Math.max(min, range[0]);
      max = Math.min(max, range[1]);
    }
    return min <= max ? [min, max] : [1, 1];
  }

  setZoom(value: number): number {
    if (!Number.isFinite(value) || this.channels.length === 0) {
      throw new Error('摄像头尚未就绪或变焦倍率无效');
    }
    let primary = 1;
    for (let index = 0; index < this.channels.length; index++) {
      const channel = this.channels[index];
      const range = channel.getZoomRange();
      const applied = channel.setZoom(Math.max(range[0], Math.min(range[1], value)));
      if (index === 0) {
        primary = applied;
      }
    }
    return primary;
  }

  async startPreview(lensId: string, surfaceId: string): Promise<void> {
    if (this.channels.length === 1 && this.getCurrentLensId() === lensId && this.surfaces[0] === surfaceId) {
      return;
    }
    this.ensureCatalog();
    const device = this.catalog.getDevice(lensId);
    const caps = this.manager.getSupportedOutputCapability(device, camera.SceneMode.NORMAL_PHOTO);
    const plan = new ConcurrentDevicePlan(device, camera.CameraConcurrentType.CAMERA_FULL_CAPABILITY, caps);
    await this.openChannels([plan], [surfaceId], false);
  }

  async startConcurrent(lensIds: string[], surfaceIds: string[]): Promise<void> {
    if (this.isConcurrentReady(lensIds) && this.surfaces.every((surface: string, index: number): boolean =>
      surface === surfaceIds[index])) {
      return;
    }
    this.ensureCatalog();
    const plans = this.catalog.planConcurrent(lensIds);
    await this.openChannels(plans, surfaceIds, true);
  }

  private async openChannels(plans: ConcurrentDevicePlan[], surfaceIds: string[], concurrent: boolean): Promise<void> {
    if (surfaceIds.length !== plans.length || surfaceIds.some((surface: string): boolean => !surface) ||
      new Set<string>(surfaceIds).size !== surfaceIds.length) {
      throw new Error('每枚镜头需要独立的预览画面，请等待全部预览创建完成');
    }
    const token = ++this.generation;
    this.groupReady = false;
    for (const channel of this.channels) {
      channel.stop().catch((): void => {});
    }
    const operation = this.lifecycle.then(async (): Promise<void> => {
      await this.releaseChannels();
      this.requireGeneration(token);
      try {
        this.lensIds = plans.map((plan: ConcurrentDevicePlan): string => plan.device.cameraId);
        this.surfaces = surfaceIds.slice();
        for (let index = 0; index < plans.length; index++) {
          this.requireGeneration(token);
          const plan = plans[index];
          const channel = new CameraChannel(this.manager, plan.device, plan.capabilities,
            this.catalog.label(plan.device), concurrent ? plan.type : undefined);
          this.channels.push(channel);
          channel.onPreviewLayoutChanged = (): void => {
            if (token === this.generation && this.onPreviewLayoutChanged) {
              this.onPreviewLayoutChanged(this.getPreviewAspectRatio());
            }
          };
          channel.onSessionError = (message: string): void => {
            if (token !== this.generation) {
              return;
            }
            this.groupReady = false;
            this.catalog.record(`会话异常 ${plan.device.cameraId}：${message}`);
            if (this.onSessionError) {
              this.onSessionError(message);
            }
            this.stop().catch((): void => {});
          };
          await channel.startPreview(plan.device.cameraId, surfaceIds[index]);
          this.requireGeneration(token);
        }
        this.groupReady = concurrent;
        this.catalog.record((concurrent ? '并发会话已就绪：' : '单镜头已就绪：') + this.lensIds.join(', '));
      } catch (error) {
        await this.releaseChannels();
        const failure = error as Error;
        this.catalog.record('打开镜头失败：' + failure.message);
        throw failure;
      }
    });
    this.lifecycle = operation.catch((): void => {});
    await operation;
  }

  isConcurrentReady(lensIds: string[]): boolean {
    return this.groupReady && lensIds.length >= 2 && this.channels.length === lensIds.length &&
      lensIds.every((id: string, index: number): boolean => this.lensIds[index] === id &&
        this.channels[index].getCurrentLensId() === id);
  }

  capture(): Promise<CapturedPhoto> {
    if (this.channels.length !== 1) {
      return Promise.reject(new Error('请等待单镜头就绪，或使用并发拍摄接口'));
    }
    return this.channels[0].capture();
  }

  async captureConcurrent(onPhoto: (photo: CapturedPhoto) => Promise<void>): Promise<string[]> {
    if (this.captureInProgress) {
      throw new Error('上一组并发照片仍在处理中，未重复发出快门');
    }
    if (!this.isConcurrentReady(this.lensIds)) {
      throw new Error('多摄会话尚未全部就绪，未发出快门');
    }
    const token = this.generation;
    this.captureInProgress = true;
    try {
      const ids = this.lensIds.slice();
      // Submit all native requests before awaiting any JPEG or disk write.
      const requests = this.channels.map((channel: CameraChannel): Promise<CapturedPhoto> => channel.capture());
      const completions = requests.map(async (request: Promise<CapturedPhoto>, index: number): Promise<string> => {
        try {
          const photo = await request;
          photo.captureMode = 'CONCURRENT';
          await onPhoto(photo);
          return '';
        } catch (error) {
          return ids[index] + '：' + (error as Error).message;
        }
      });
      const results = await Promise.all(completions);
      const failures = results.filter((message: string): boolean => message.length > 0);
      if (failures.length > 0) {
        this.catalog.record('并发拍摄部分失败：' + failures.join('；'));
        if (token === this.generation) {
          await this.stop();
        } else {
          await this.lastStop;
        }
      }
      return failures;
    } finally {
      this.captureInProgress = false;
    }
  }

  async stop(): Promise<void> {
    ++this.generation;
    this.groupReady = false;
    const stopping = this.channels.map((channel: CameraChannel): Promise<void> => channel.stop());
    const operation = this.lifecycle.then(async (): Promise<void> => {
      await Promise.all(stopping);
      await this.releaseChannels();
    });
    this.lifecycle = operation.catch((): void => {});
    this.lastStop = operation;
    await operation;
  }

  private async releaseChannels(): Promise<void> {
    const channels = this.channels.slice();
    this.channels = [];
    this.lensIds = [];
    this.surfaces = [];
    this.groupReady = false;
    await Promise.all(channels.map((channel: CameraChannel): Promise<void> => channel.stop()));
  }

  private requireGeneration(token: number): void {
    if (token !== this.generation) {
      throw new Error('摄像头初始化已取消');
    }
  }
}
